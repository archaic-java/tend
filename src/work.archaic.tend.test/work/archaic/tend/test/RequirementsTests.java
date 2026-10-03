package work.archaic.tend.test;

import com.google.gson.*;
import java.io.IOException;
import java.util.*;
import work.archaic.service.test.v02.*;

public record RequirementsTests() implements TestSuite {
    public void cases(Collection<TestCase> cases) {
        cases.add(new NamedMounts());
        cases.add(new MalformedIncusConfiguration());
        cases.add(new UnownedNetworkAcl());
        cases.add(new InvalidNumericAttribute());
        cases.add(new PublicOnlyIngress());
        cases.add(new FirstSliceExample());
        cases.add(new RequirementsExample());
        cases.add(new NamedConfigurationChanges());
        cases.add(new RemovedConfigurationFile());
        cases.add(new IngressProjection());
        cases.add(new IngressIdempotence());
        cases.add(new AuthorizationActivationFails());
        cases.add(new EgressProjection());
        cases.add(new EgressIdempotence());
        cases.add(new EgressDrift());
        cases.add(new UnsupportedNetwork());
        cases.add(new SharedNetworkAcl());
        cases.add(new MissingConfiguration());
        cases.add(new MountOverlap());
        cases.add(new MissingAuthorizationLoad());
        cases.add(new DuplicateIngressHost());
        cases.add(new UnboundIngress());
        cases.add(new HostnameEgress());
        cases.add(new ConflictingAclSettings());
    }
}
record NamedMounts() implements TestCase {
    public void run(TestTrail trail) throws Exception {
        try (var f = new DeploymentFixture()) {
            var state = RequirementsFixture.apply(f, RequirementsFixture.mounts());
            var devices = f.mock.resources.get(Garden.INSTANCE).getAsJsonObject("devices");
            assert devices.getAsJsonObject("settings").get("readonly").getAsString().equals("true") : "Configuration must be read-only inside the instance";
            String source = RequirementsFixture.source(f, "session");
            var file = f.mock.files.get("/1.0/storage-pools/pool/volumes/custom/" + source + "/value");
            assert file.uid().equals("1000") && file.mode().equals("0400") : "Secret delivery must use declared ownership and private mode";
            var volume = f.mock.resources.get("/1.0/storage-pools/pool/volumes/custom/" + source).getAsJsonObject("config");
            assert volume.get("initial.uid").getAsString().equals("1000") : "Private volume directory must be accessible to the consuming UID";
            byte[] original = file.bytes(); f.engine.reconcile(state);
            assert Arrays.equals(original, f.mock.files.get("/1.0/storage-pools/pool/volumes/custom/" + source + "/value").bytes()) : "Named secret references must retain generated values";
        }
    }
}
record NamedConfigurationChanges() implements TestCase {
    public void run(TestTrail trail) throws Exception {
        try (var f = new DeploymentFixture()) {
            RequirementsFixture.apply(f, RequirementsFixture.mounts());
            int starts = f.mock.starts;
            var changed = f.revision(RequirementsFixture.mounts(), "version=two\n"); f.engine.reconcile(changed);
            assert f.mock.starts == starts + 1 : "Changed named configuration must activate its consumer once";
            int mutations = f.mock.mutations; f.engine.reconcile(changed);
            assert f.mock.mutations == mutations : "Named configuration must converge without further writes";
        }
    }
}
record RemovedConfigurationFile() implements TestCase {
    public void run(TestTrail trail) throws Exception {
        try (var f = new DeploymentFixture()) {
            String expanded = RequirementsFixture.mounts().replace("</configuration>", "<file path=\"/extra.conf\" source=\"service.conf\"/></configuration>");
            RequirementsFixture.apply(f, expanded);
            String old = RequirementsFixture.source(f, "settings");
            RequirementsFixture.apply(f, RequirementsFixture.mounts());
            String current = RequirementsFixture.source(f, "settings");
            assert !old.equals(current) : "Removing a file must change the mounted file set";
            assert !f.mock.files.containsKey("/1.0/storage-pools/pool/volumes/custom/" + current + "/extra.conf") : "Removed configuration files must not remain visible";
        }
    }
}
record IngressProjection() implements TestCase {
    public void run(TestTrail trail) throws Exception {
        try (var f = new DeploymentFixture()) {
            RequirementsFixture.apply(f, RequirementsFixture.ingress());
            String caddy = RequirementsFixture.text(f, "Caddyfile");
            assert caddy.contains("forward_auth 10.20.0.11:9091") && caddy.contains("uri /api/authz/forward-auth") : "Protected routes must call Authelia before the backend";
            assert caddy.contains("reverse_proxy 10.20.0.10:8080") : "Backend reference must use the declared instance NIC address";
            String portal = caddy.substring(caddy.indexOf("auth.example.org"), caddy.indexOf("demo.example.org"));
            assert !portal.contains("forward_auth") : "Explicit public route must reach the authorization portal without a loop";
            assert caddy.contains("request_header -Remote-User") : "Client identity headers must be stripped before authorization";
            var access = JsonParser.parseString(RequirementsFixture.text(f, "access-control.json")).getAsJsonObject().getAsJsonObject("access_control");
            assert access.get("default_policy").getAsString().equals("deny") : "Authelia policy must deny undeclared hosts";
            assert access.getAsJsonArray("rules").get(0).getAsJsonObject().get("policy").getAsString().equals("two_factor") : "Protected ingress must default to two-factor authorization";
            var subjects = access.getAsJsonArray("rules").get(0).getAsJsonObject().getAsJsonArray("subject");
            assert subjects.size() == 2 && subjects.get(0).getAsJsonArray().get(0).getAsString().equals("group:gardeners") : "Groups must be alternative allowed subjects";
        }
    }
}
record IngressIdempotence() implements TestCase {
    public void run(TestTrail trail) throws Exception {
        try (var f = new DeploymentFixture()) {
            var state = RequirementsFixture.apply(f, RequirementsFixture.ingress());
            int mutations = f.mock.mutations; f.engine.reconcile(state);
            assert f.mock.mutations == mutations : "Rendered ingress and authorization policy must settle";
        }
    }
}
record AuthorizationActivationFails() implements TestCase {
    public void run(TestTrail trail) throws Exception {
        try (var f = new DeploymentFixture()) {
            f.mock.failStart = true;
            boolean rejected = false;
            try { RequirementsFixture.apply(f, RequirementsFixture.ingress()); } catch (IOException e) { rejected = true; }
            assert rejected : "Failed authorization activation must escape reconciliation";
            assert !f.mock.running.getOrDefault("/1.0/instances/caddy", false) : "Gateway must not start ahead of authorization activation";
        }
    }
}
record EgressProjection() implements TestCase {
    public void run(TestTrail trail) throws Exception {
        try (var f = new DeploymentFixture()) {
            RequirementsFixture.network(f, "ovn");
            RequirementsFixture.apply(f, RequirementsFixture.egress());
            var acl = f.mock.resources.get(RequirementsFixture.acl(f));
            assert acl.getAsJsonArray("egress").size() == 2 : "Declared destinations must become Incus ACL rules";
            var first = acl.getAsJsonArray("egress").get(0).getAsJsonObject();
            assert first.get("destination").getAsString().equals("10.20.0.11/32") && first.get("destination_port").getAsString().equals("9091") : "ACL must preserve destination and port";
            var nic = f.mock.resources.get(Garden.INSTANCE).getAsJsonObject("devices").getAsJsonObject("eth0");
            assert nic.get("security.acls.default.egress.action").getAsString().equals("reject") : "Unmatched outbound traffic must be rejected";
            assert nic.get("security.acls.default.ingress.action").getAsString().equals("allow") : "Egress declaration must not invent an ingress firewall policy";
        }
    }
}
record EgressIdempotence() implements TestCase {
    public void run(TestTrail trail) throws Exception {
        try (var f = new DeploymentFixture()) {
            RequirementsFixture.network(f, "ovn");
            var state = RequirementsFixture.apply(f, RequirementsFixture.egress());
            int mutations = f.mock.mutations; f.engine.reconcile(state);
            assert f.mock.mutations == mutations : "Unchanged network policies must cause no writes";
        }
    }
}
record EgressDrift() implements TestCase {
    public void run(TestTrail trail) throws Exception {
        try (var f = new DeploymentFixture()) {
            RequirementsFixture.network(f, "ovn");
            var state = RequirementsFixture.apply(f, RequirementsFixture.egress());
            f.mock.resources.get(RequirementsFixture.acl(f)).add("egress", new JsonArray());
            f.engine.reconcile(state);
            assert f.mock.resources.get(RequirementsFixture.acl(f)).getAsJsonArray("egress").size() == 2 : "Owned ACL drift must be repaired";
        }
    }
}
record UnsupportedNetwork() implements TestCase {
    public void run(TestTrail trail) throws Exception {
        try (var f = new DeploymentFixture()) {
            RequirementsFixture.network(f, "bridge");
            boolean rejected = false;
            try { RequirementsFixture.apply(f, RequirementsFixture.egress()); } catch (IOException e) { rejected = true; }
            assert rejected && f.mock.mutations == 0 : "Unsupported network must fail preflight before any deployment mutation";
        }
    }
}
record SharedNetworkAcl() implements TestCase {
    public void run(TestTrail trail) throws Exception {
        try (var f = new DeploymentFixture()) {
            RequirementsFixture.network(f, "ovn");
            f.mock.resources.get("/1.0/networks/garden-net").getAsJsonObject("config").addProperty("security.acls", "operator-policy");
            boolean rejected = false;
            try { RequirementsFixture.apply(f, RequirementsFixture.egress()); } catch (IOException e) { rejected = true; }
            assert rejected && f.mock.mutations == 0 : "Shared ACLs must not widen the declared outbound policy";
        }
    }
}
record MissingConfiguration() implements TestCase {
    public void run(TestTrail trail) throws Exception {
        try (var f = new DeploymentFixture()) {
            boolean rejected = false;
            try { f.revision(RequirementsFixture.mounts().replace("configuration=\"settings\"", "configuration=\"missing\""), "first"); } catch (IOException e) { rejected = true; }
            assert rejected && f.mock.mutations == 0 : "Invalid resource reference or policy must fail before mutation";
        }
    }
}
record MountOverlap() implements TestCase {
    public void run(TestTrail trail) throws Exception {
        try (var f = new DeploymentFixture()) {
            boolean rejected = false;
            try { f.revision(RequirementsFixture.mounts().replace("path=\"/etc/demo\"", "path=\"/data/nested\""), "first"); } catch (IOException e) { rejected = true; }
            assert rejected && f.mock.mutations == 0 : "Invalid resource reference or policy must fail before mutation";
        }
    }
}
record MissingAuthorizationLoad() implements TestCase {
    public void run(TestTrail trail) throws Exception {
        try (var f = new DeploymentFixture()) {
            boolean rejected = false;
            try { f.revision(RequirementsFixture.ingress().replace("/config/configuration.yml,/etc/tend-authorization/access-control.json", "/config/configuration.yml"), "first"); } catch (IOException e) { rejected = true; }
            assert rejected && f.mock.mutations == 0 : "Invalid resource reference or policy must fail before mutation";
        }
    }
}
record DuplicateIngressHost() implements TestCase {
    public void run(TestTrail trail) throws Exception {
        try (var f = new DeploymentFixture()) {
            boolean rejected = false;
            try { f.revision(RequirementsFixture.ingress().replace("auth.example.org", "demo.example.org"), "first"); } catch (IOException e) { rejected = true; }
            assert rejected && f.mock.mutations == 0 : "Invalid resource reference or policy must fail before mutation";
        }
    }
}
record UnboundIngress() implements TestCase {
    public void run(TestTrail trail) throws Exception {
        try (var f = new DeploymentFixture()) {
            boolean rejected = false;
            try { f.revision(RequirementsFixture.ingress().replaceAll("<ingress-gateway[^>]*/>", ""), "first"); } catch (IOException e) { rejected = true; }
            assert rejected && f.mock.mutations == 0 : "Invalid resource reference or policy must fail before mutation";
        }
    }
}
record HostnameEgress() implements TestCase {
    public void run(TestTrail trail) throws Exception {
        try (var f = new DeploymentFixture()) {
            boolean rejected = false;
            try { f.revision(RequirementsFixture.egress().replace("10.20.0.11/32", "auth.example.org"), "first"); } catch (IOException e) { rejected = true; }
            assert rejected && f.mock.mutations == 0 : "Invalid resource reference or policy must fail before mutation";
        }
    }
}
record ConflictingAclSettings() implements TestCase {
    public void run(TestTrail trail) throws Exception {
        try (var f = new DeploymentFixture()) {
            boolean rejected = false;
            try { f.revision(RequirementsFixture.egress().replace("<entry key=\"network\"", "<entry key=\"security.acls\" value=\"external\"/><entry key=\"network\""), "first"); } catch (IOException e) { rejected = true; }
            assert rejected && f.mock.mutations == 0 : "Invalid resource reference or policy must fail before mutation";
        }
    }
}

record FirstSliceExample() implements TestCase {
    public void run(TestTrail trail) throws Exception {
        try (var f = new DeploymentFixture()) {
            var directory = f.garden.author.resolve("examples"); java.nio.file.Files.createDirectories(directory);
            java.nio.file.Files.copy(java.nio.file.Path.of("examples/service.conf"), directory.resolve("service.conf"));
            var state = f.revision(java.nio.file.Files.readString(java.nio.file.Path.of("examples/first-slice.xml")), "unused");
            f.engine.reconcile(state);
            assert f.mock.running.get(Garden.INSTANCE) : "Published first-slice example must reconcile through real adapters";
        }
    }
}
record RequirementsExample() implements TestCase {
    public void run(TestTrail trail) throws Exception {
        try (var f = new DeploymentFixture()) {
            var directory = f.garden.author.resolve("examples"); java.nio.file.Files.createDirectories(directory);
            java.nio.file.Files.copy(java.nio.file.Path.of("examples/service.conf"), directory.resolve("service.conf"));
            RequirementsFixture.network(f, "ovn");
            var state = f.revision(java.nio.file.Files.readString(java.nio.file.Path.of("examples/requirements.xml")), "unused");
            f.engine.reconcile(state);
            assert f.mock.running.get("/1.0/instances/caddy") : "Published requirements example must activate its gateway";
            assert f.mock.resources.containsKey(RequirementsFixture.acl(f)) : "Published requirements example must reconcile its egress ACL";
        }
    }
}

record MalformedIncusConfiguration() implements TestCase {
    public void run(TestTrail trail) throws Exception {
        try (var f = new DeploymentFixture()) {
            var volume = new JsonObject(); volume.add("config", new JsonArray()); f.mock.seed(Garden.VOLUME, volume);
            boolean rejected = false;
            try { f.deploy(); } catch (work.archaic.tend.incus.IncusException e) { rejected = true; }
            assert rejected && f.mock.mutations == 0 : "Malformed API metadata must be a checked protocol failure before mutation";
        }
    }
}
record UnownedNetworkAcl() implements TestCase {
    public void run(TestTrail trail) throws Exception {
        try (var f = new DeploymentFixture()) {
            RequirementsFixture.network(f, "ovn");
            var state = f.revision(RequirementsFixture.egress(), "first");
            String name = new work.archaic.tend.state.ResourceCompiler().compile(state).acls().getFirst().name();
            var acl = new JsonObject(); acl.add("config", new JsonObject()); f.mock.seed("/1.0/network-acls/" + name, acl);
            boolean rejected = false;
            try { f.engine.reconcile(state); } catch (IOException e) { rejected = true; }
            assert rejected && f.mock.mutations == 0 : "Unowned ACL must fail preflight without adopting or widening it";
        }
    }
}
record InvalidNumericAttribute() implements TestCase {
    public void run(TestTrail trail) throws Exception {
        try (var f = new DeploymentFixture()) {
            boolean rejected = false;
            try { f.revision(Garden.xml().replace("uid=\"1000\"", "uid=\"99999999999999999999\""), "first"); }
            catch (work.archaic.tend.state.StateException e) { rejected = true; }
            assert rejected : "Oversized XML integers must become checked validation failures";
        }
    }
}
record PublicOnlyIngress() implements TestCase {
    public void run(TestTrail trail) throws Exception {
        try (var f = new DeploymentFixture()) {
            String xml = RequirementsFixture.ingress().replaceAll("<authorization>.*?</authorization>", "<public/>");
            RequirementsFixture.apply(f, xml);
            String caddy = RequirementsFixture.text(f, "Caddyfile");
            assert !caddy.contains("forward_auth") : "Explicit public routes must not require authentication";
            var policy = JsonParser.parseString(RequirementsFixture.text(f, "access-control.json")).getAsJsonObject().getAsJsonObject("access_control");
            assert policy.getAsJsonArray("rules").get(0).getAsJsonObject().get("policy").getAsString().equals("deny") : "Unused authorization policy must remain valid and deny by default";
        }
    }
}
