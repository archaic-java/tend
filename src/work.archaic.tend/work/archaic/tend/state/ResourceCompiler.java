package work.archaic.tend.state;

import com.google.gson.*;
import java.nio.charset.StandardCharsets;
import java.security.*;
import java.util.*;
import work.archaic.tend.state.DesiredState.*;

/** Lowers application requirements into explicit Incus volumes, disks and network ACLs. */
public final class ResourceCompiler {
    public record Acl(String name, String network, JsonArray egress) {}
    public record Deployment(String project, List<Secret> secrets, List<Volume> volumes, List<Instance> instances, List<Acl> acls) {}
    public Deployment compile(DesiredState state) throws StateException {
        var instances = new LinkedHashMap<String, Instance>();
        state.instances().forEach(i -> instances.put(i.name(), i));
        var volumes = new ArrayList<>(state.volumes());
        for (var instance : state.instances()) instances.put(instance.name(), mounts(instance, state, volumes));
        gateway(state, instances, volumes);
        var acls = new ArrayList<Acl>();
        Set<String> consumers = new HashSet<>();
        for (var egress : state.egresses()) {
            if (!consumers.add(egress.instance() + "/" + egress.device())) throw new StateException("Only one egress resource per NIC");
            var instance = instance(instances, egress.instance());
            var nic = device(instance, egress.device());
            if (!nic.containsKey("network") || nic.containsKey("nictype") || nic.containsKey("parent"))
                throw new StateException("Egress requires an explicitly managed OVN network NIC");
            if (nic.keySet().stream().anyMatch(k -> k.startsWith("security.acls")))
                throw new StateException("Egress owns the NIC security.acls settings");
            var rules = rules(egress);
            String name = generatedName("acl/" + egress.name());
            var devices = new LinkedHashMap<>(instance.devices());
            var properties = new LinkedHashMap<>(nic);
            properties.put("security.acls", name);
            properties.put("security.acls.default.egress.action", "reject");
            properties.put("security.acls.default.ingress.action", "allow");
            devices.put(egress.device(), properties);
            instances.put(instance.name(), copy(instance, devices));
            acls.add(new Acl(name, nic.get("network"), rules));
        }
        return new Deployment(state.project(), state.secrets(), List.copyOf(volumes), List.copyOf(instances.values()), List.copyOf(acls));
    }
    private Instance mounts(Instance instance, DesiredState state, List<Volume> volumes) throws StateException {
        var devices = new LinkedHashMap<>(instance.devices());
        for (var mount : instance.mounts()) {
            if (mount.uid() < 0 || mount.gid() < 0) throw new StateException("Mount UID and GID must be nonnegative");
            boolean secret = !mount.secret().isEmpty();
            if (secret == !mount.configuration().isEmpty()) throw new StateException("Mount requires exactly one configuration or secret");
            var files = secret ? secretFiles(mount, state) : configurationFiles(mount, state);
            attach(instance, devices, volumes, mount.name(), mount.pool(), mount.path(), files);
        }
        return copy(instance, devices);
    }
    private List<DesiredState.File> secretFiles(Mount mount, DesiredState state) throws StateException {
        if (state.secrets().stream().noneMatch(s -> s.name().equals(mount.secret()))) throw new StateException("Undeclared secret reference");
        if ((Integer.parseInt(mount.mode(), 8) & 0077) != 0) throw new StateException("Secret file must exclude group and other access");
        return List.of(new DesiredState.File("/value", null, mount.secret(), mount.uid(), mount.gid(), mount.mode()));
    }
    private List<DesiredState.File> configurationFiles(Mount mount, DesiredState state) throws StateException {
        var configuration = state.configurations().stream().filter(c -> c.name().equals(mount.configuration())).findFirst()
                .orElseThrow(() -> new StateException("Undeclared configuration reference"));
        return configuration.files().stream().map(f -> new DesiredState.File(f.path(), f.content(), "", mount.uid(), mount.gid(), mount.mode())).toList();
    }
    private void gateway(DesiredState state, Map<String, Instance> instances, List<Volume> volumes) throws StateException {
        if (state.gateway() == null) {
            if (!state.ingresses().isEmpty()) throw new StateException("Ingress requires an explicit gateway binding");
            return;
        }
        var binding = state.gateway();
        if (binding.uid() < 0 || binding.gid() < 0 || binding.authorizationUid() < 0 || binding.authorizationGid() < 0)
            throw new StateException("Gateway UID and GID must be nonnegative");
        if (binding.instance().equals(binding.authorizationInstance())) throw new StateException("Gateway and authorization require separate instances");
        var proxy = instance(instances, binding.instance());
        var authorization = instance(instances, binding.authorizationInstance());
        String address = address(authorization, binding.authorizationDevice());
        String policyFile = binding.authorizationPath() + "/access-control.json";
        String paths = authorization.config().getOrDefault("environment.X_AUTHELIA_CONFIG", "");
        if (!Arrays.asList(paths.split(",")).getLast().equals(policyFile))
            throw new StateException("Authelia must load generated access-control.json last via X_AUTHELIA_CONFIG");
        StringBuilder caddy = new StringBuilder();
        JsonArray accessRules = new JsonArray();
        Set<String> hosts = new HashSet<>();
        for (var ingress : state.ingresses().stream().sorted(Comparator.comparing(Ingress::host)).toList()) {
            if (!ingress.host().matches("[a-z0-9](?:[a-z0-9-]*[a-z0-9])?(?:\\.[a-z0-9](?:[a-z0-9-]*[a-z0-9])?)+") || !hosts.add(ingress.host()))
                throw new StateException("Ingress hosts must be unique literal DNS names");
            String backend = address(instance(instances, ingress.instance()), ingress.device());
            caddy.append(ingress.host()).append(" {\n    route {\n");
            // Untrusted client identity headers must not survive a public or protected route.
            caddy.append("        request_header -Remote-User\n        request_header -Remote-Groups\n        request_header -Remote-Email\n        request_header -Remote-Name\n");
            if (!ingress.publicAccess()) {
                caddy.append("        forward_auth ").append(address).append(':').append(binding.authorizationPort()).append(" {\n")
                        .append("            uri /api/authz/forward-auth\n            copy_headers Remote-User Remote-Groups Remote-Email Remote-Name\n        }\n");
                accessRules.add(accessRule(ingress));
            }
            caddy.append("        reverse_proxy ").append(backend).append(':').append(ingress.port()).append("\n    }\n}\n");
        }
        if (accessRules.isEmpty()) {
            JsonObject deny = new JsonObject(); JsonArray domains = new JsonArray(); domains.add("*");
            deny.add("domain", domains); deny.addProperty("policy", "deny"); accessRules.add(deny);
        }
        JsonObject policy = new JsonObject(); policy.addProperty("default_policy", "deny"); policy.add("rules", accessRules);
        JsonObject document = new JsonObject(); document.add("access_control", policy);
        instances.put(authorization.name(), generated(authorization, volumes, "authorization", binding.pool(), binding.authorizationPath(), "access-control.json", document.toString(), binding.authorizationUid(), binding.authorizationGid()));
        instances.put(proxy.name(), generated(proxy, volumes, "ingress", binding.pool(), binding.path(), "Caddyfile", caddy.toString(), binding.uid(), binding.gid()));
    }
    private static JsonObject accessRule(Ingress ingress) throws StateException {
        if (ingress.groups().isEmpty()) throw new StateException("Protected ingress requires at least one group");
        JsonObject rule = new JsonObject(); JsonArray domains = new JsonArray(); domains.add(ingress.host());
        rule.add("domain", domains); rule.addProperty("policy", ingress.policy());
        JsonArray subjects = new JsonArray();
        for (String group : ingress.groups()) { JsonArray alternatives = new JsonArray(); alternatives.add("group:" + group); subjects.add(alternatives); }
        rule.add("subject", subjects); return rule;
    }
    private Instance generated(Instance instance, List<Volume> volumes, String name, String pool, String path, String file, String text, int uid, int gid) throws StateException {
        var devices = new LinkedHashMap<>(instance.devices());
        attach(instance, devices, volumes, "tend-" + name, pool, path,
                List.of(new DesiredState.File("/" + file, text.getBytes(StandardCharsets.UTF_8), "", uid, gid, "0644")));
        return copy(instance, devices);
    }
    private void attach(Instance instance, Map<String, Map<String, String>> devices, List<Volume> volumes,
                        String name, String pool, String path, List<DesiredState.File> files) throws StateException {
        if (devices.containsKey(name) || devices.values().stream().anyMatch(d -> path.equals(d.get("path"))))
            throw new StateException("Configuration mount collides with an existing device or path");
        if (devices.values().stream().filter(d -> "disk".equals(d.get("type"))).map(d -> d.get("path"))
                .filter(Objects::nonNull).filter(p -> !p.equals("/")).anyMatch(p -> p.startsWith(path + "/") || path.startsWith(p + "/")))
            throw new StateException("Overlapping configuration mounts are not supported");
        // File-set changes use another volume: removed files cannot remain visible in the new mount.
        String fileSet = files.stream().map(DesiredState.File::path).sorted().toList().toString();
        String volume = generatedName("mount/" + instance.name() + "/" + name + "/" + files.getFirst().uid() + "/" + files.getFirst().gid() + "/" + fileSet);
        if (volumes.stream().anyMatch(v -> v.pool().equals(pool) && v.name().equals(volume))) throw new StateException("Generated volume collision");
        volumes.add(new Volume(pool, volume, Map.of("initial.mode", "0700", "initial.uid", Integer.toString(files.getFirst().uid()), "initial.gid", Integer.toString(files.getFirst().gid())), files));
        devices.put(name, Map.of("type", "disk", "pool", pool, "source", volume, "path", path, "readonly", "true"));
    }
    private static Instance copy(Instance i, Map<String, Map<String, String>> devices) {
        return new Instance(i.name(), i.type(), i.fingerprint(), i.running(), i.config(), devices, List.of());
    }
    private static Instance instance(Map<String, Instance> instances, String name) throws StateException {
        var instance = instances.get(name);
        if (instance == null) throw new StateException("Undeclared instance reference: " + name);
        return instance;
    }
    private static Map<String, String> device(Instance instance, String name) throws StateException {
        var device = instance.devices().get(name);
        if (device == null || !"nic".equals(device.get("type"))) throw new StateException("Reference requires a declared NIC");
        return device;
    }
    private static String address(Instance instance, String name) throws StateException {
        String address = device(instance, name).getOrDefault("ipv4.address", "");
        if (!ipv4(address)) throw new StateException("Ingress requires a declared static IPv4 address");
        return address;
    }
    private static JsonArray rules(Egress egress) throws StateException {
        JsonArray rules = new JsonArray();
        for (var allow : egress.rules()) {
            String[] parts = allow.address().split("/", -1);
            if (parts.length > 2 || !ipv4(parts[0]) || (parts.length == 2 && !parts[1].matches("(?:[0-9]|[12][0-9]|3[0-2])")))
                throw new StateException("Egress destinations must be literal IPv4 addresses or CIDRs");
            JsonObject rule = new JsonObject(); rule.addProperty("action", "allow"); rule.addProperty("state", "enabled");
            rule.addProperty("destination", allow.address()); rule.addProperty("protocol", allow.protocol()); rule.addProperty("destination_port", Integer.toString(allow.port()));
            rules.add(rule);
        }
        return rules;
    }
    private static boolean ipv4(String text) {
        if (!text.matches("(?:0|[1-9][0-9]{0,2})(?:\\.(?:0|[1-9][0-9]{0,2})){3}")) return false;
        return Arrays.stream(text.split("\\.")).allMatch(p -> Integer.parseInt(p) <= 255);
    }
    private static String generatedName(String identity) {
        try {
            byte[] digest = MessageDigest.getInstance("SHA-256").digest(identity.getBytes(StandardCharsets.UTF_8));
            return "tend-" + HexFormat.of().formatHex(digest, 0, 16);
        } catch (NoSuchAlgorithmException e) { throw new AssertionError("JDK must support SHA-256", e); }
    }
}
