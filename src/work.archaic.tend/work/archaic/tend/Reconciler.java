package work.archaic.tend;

import com.google.gson.*;
import java.io.IOException;
import java.nio.charset.StandardCharsets;
import java.security.MessageDigest;
import java.util.*;
import work.archaic.tend.incus.IncusClient;
import work.archaic.tend.secrets.SecretStore;
import work.archaic.tend.state.DesiredState;

/** One serialized convergence pass; completion markers follow successful activation. */
public final class Reconciler {
    private final IncusClient incus;
    private final SecretStore secrets;
    private final String project;
    private final String owner;
    private static final String OWNER = "user.tend.owner";
    private static final String KEYS = "user.tend.keys";
    private static final String DEVICES = "user.tend.devices";
    private static final String ACTIVATED = "user.tend.activated";
    private static final String IMAGE = "user.tend.image";

    public Reconciler(IncusClient incus, SecretStore secrets, String project, String owner) {
        if (owner.isBlank()) throw new IllegalArgumentException("Owner must be stable and nonempty");
        this.incus = incus; this.secrets = secrets; this.project = project; this.owner = owner;
    }

    public synchronized void reconcile(DesiredState desired) throws Exception {
        if (!desired.project().equals(project)) throw new IOException("Desired project differs from controller scope");
        // Preflight existing ownership and immutable fields before mutation.
        for (var volume : desired.volumes()) checkOwner(incus.get(IncusClient.volumePath(volume.pool(), volume.name())));
        for (var instance : desired.instances()) {
            var observed = incus.get(instancePath(instance.name()));
            checkOwner(observed);
            if (observed != null && (!instance.type().equals(observed.value().get("type").getAsString()) ||
                    !instance.fingerprint().equals(value(observed.value().getAsJsonObject("config"), IMAGE))))
                throw new IOException("Image/type replacement is not implemented in this slice");
        }
        Map<String, byte[]> values = new HashMap<>();
        for (var secret : desired.secrets()) values.put(secret.name(), secrets.getOrCreate(secret.name(), secret.bytes()));
        Map<String, String> volumeDigests = new HashMap<>();
        for (var volume : desired.volumes()) {
            String path = IncusClient.volumePath(volume.pool(), volume.name());
            var current = incus.get(path);
            JsonObject config = managedConfig(current, volume.config());
            if (current == null) {
                JsonObject body = new JsonObject();
                body.addProperty("name", volume.name()); body.addProperty("type", "custom");
                body.addProperty("content_type", "filesystem"); body.add("config", config);
                incus.mutate("POST", "/1.0/storage-pools/" + IncusClient.encode(volume.pool()) + "/volumes/custom", body, null);
            } else if (!config.equals(current.value().getAsJsonObject("config"))) {
                JsonObject body = new JsonObject(); body.add("config", config);
                body.addProperty("description", value(current.value(), "description"));
                incus.mutate("PUT", path, body, current.etag());
            }
            MessageDigest digest = MessageDigest.getInstance("SHA-256");
            for (var file : volume.files().stream().sorted(Comparator.comparing(DesiredState.File::path)).toList()) {
                byte[] content = file.secret().isBlank() ? file.content() : values.get(file.secret());
                var old = incus.file(path, file.path());
                if (old == null || !Arrays.equals(content, old.content()) || !old.uid().equals(Integer.toString(file.uid())) ||
                        !old.gid().equals(Integer.toString(file.gid())) || !old.mode().equals(file.mode()) || !old.type().equals("file")) {
                    // Persist pending activation before touching a file visible to a process.
                    for (var instance : desired.instances())
                        if (instance.devices().values().stream().anyMatch(d ->
                                volume.pool().equals(d.get("pool")) && volume.name().equals(d.get("source"))))
                            markPending(instancePath(instance.name()));
                    incus.writeFile(path, file.path(), content, file.uid(), file.gid(), file.mode());
                }
                update(digest, file.path()); update(digest, Integer.toString(file.uid()));
                update(digest, Integer.toString(file.gid())); update(digest, file.mode());
                digest.update(MessageDigest.getInstance("SHA-256").digest(content));
            }
            volumeDigests.put(volume.pool() + "/" + volume.name(), HexFormat.of().formatHex(digest.digest()));
        }
        for (var instance : desired.instances()) reconcileInstance(instance, volumeDigests);
    }

    private void reconcileInstance(DesiredState.Instance desired, Map<String, String> volumeDigests) throws Exception {
        String path = instancePath(desired.name());
        var current = incus.get(path);
        JsonObject config = managedConfig(current, desired.config());
        config.addProperty(IMAGE, desired.fingerprint());
        JsonObject devices = current == null ? new JsonObject() : current.value().getAsJsonObject("devices").deepCopy();
        if (current != null) for (String name : tracked(current.value().getAsJsonObject("config"), DEVICES)) devices.remove(name);
        JsonArray names = new JsonArray();
        for (var entry : new TreeMap<>(desired.devices()).entrySet()) {
            JsonObject properties = new JsonObject(); entry.getValue().forEach(properties::addProperty);
            devices.add(entry.getKey(), properties); names.add(entry.getKey());
        }
        config.addProperty(DEVICES, names.toString());
        MessageDigest digest = MessageDigest.getInstance("SHA-256");
        update(digest, new TreeMap<>(desired.config()).toString());
        for (var entry : new TreeMap<>(desired.devices()).entrySet()) {
            update(digest, entry.getKey()); update(digest, new TreeMap<>(entry.getValue()).toString());
        }
        for (var entry : new TreeMap<>(desired.devices()).entrySet()) {
            var d = entry.getValue();
            if (d.containsKey("source")) update(digest, volumeDigests.getOrDefault(d.get("pool") + "/" + d.get("source"), ""));
        }
        String activation = HexFormat.of().formatHex(digest.digest());
        boolean activated = current != null && activation.equals(value(current.value().getAsJsonObject("config"), ACTIVATED));
        if (current == null) {
            JsonObject body = new JsonObject(); body.addProperty("name", desired.name()); body.addProperty("type", desired.type());
            body.add("config", config); body.add("devices", devices); body.add("profiles", new JsonArray());
            JsonObject source = new JsonObject(); source.addProperty("type", "image"); source.addProperty("fingerprint", desired.fingerprint());
            body.add("source", source); body.addProperty("start", false);
            incus.mutate("POST", "/1.0/instances", body, null);
        } else if (!config.equals(current.value().getAsJsonObject("config")) || !devices.equals(current.value().getAsJsonObject("devices")) ||
                !current.value().getAsJsonArray("profiles").isEmpty()) {
            if (running(path)) action(path, "stop");
            updateInstance(path, config, devices);
            activated = false;
        }
        if (!activated && running(path)) action(path, "stop");
        if (desired.running() && !running(path)) action(path, "start");
        if (!desired.running() && running(path)) action(path, "stop");
        if (!activated) {
            config.addProperty(ACTIVATED, activation);
            updateInstance(path, config, devices);
        }
    }

    private void markPending(String path) throws Exception {
        var current = incus.get(path);
        if (current == null) return;
        checkOwner(current);
        JsonObject config = current.value().getAsJsonObject("config").deepCopy();
        if (!config.has(ACTIVATED)) return;
        config.remove(ACTIVATED);
        // Empty string means deletion to Incus; omission alone would keep the old field in our merge.
        config.addProperty(ACTIVATED, "");
        updateInstance(path, config, current.value().getAsJsonObject("devices"));
    }

    private void updateInstance(String path, JsonObject config, JsonObject devices) throws Exception {
        var current = incus.get(path);
        JsonObject body = new JsonObject();
        for (String key : List.of("architecture", "description", "ephemeral"))
            if (current.value().has(key)) body.add(key, current.value().get(key));
        JsonObject merged = current.value().getAsJsonObject("config").deepCopy();
        for (String key : tracked(merged, KEYS)) merged.remove(key);
        Set<String> managed = new HashSet<>(tracked(config, KEYS));
        for (var entry : config.entrySet())
            if (entry.getKey().startsWith("user.tend.") || managed.contains(entry.getKey())) {
                if (entry.getValue().getAsString().isEmpty()) merged.remove(entry.getKey());
                else merged.add(entry.getKey(), entry.getValue());
            }
        body.add("config", merged); body.add("devices", devices); body.add("profiles", new JsonArray());
        incus.mutate("PUT", path, body, current.etag());
    }
    private boolean running(String path) throws Exception {
        var state = incus.get(path + "/state");
        if (state == null) throw new IOException("Instance state disappeared");
        int status = state.value().get("status_code").getAsInt();
        if (status != 102 && status != 103) throw new IOException("Instance is in a transitional or unsupported state");
        return status == 103;
    }
    private void action(String path, String action) throws Exception {
        JsonObject body = new JsonObject(); body.addProperty("action", action); body.addProperty("timeout", 30);
        body.addProperty("force", false); body.addProperty("stateful", false);
        incus.mutate("PUT", path + "/state", body, null);
        if (running(path) != action.equals("start")) throw new IOException("Instance state did not converge");
    }
    private JsonObject managedConfig(IncusClient.Resource current, Map<String, String> desired) throws IOException {
        checkOwner(current);
        JsonObject config = current == null ? new JsonObject() : current.value().getAsJsonObject("config").deepCopy();
        for (String key : tracked(config, KEYS)) config.remove(key);
        JsonArray keys = new JsonArray();
        for (var entry : new TreeMap<>(desired).entrySet()) { config.addProperty(entry.getKey(), entry.getValue()); keys.add(entry.getKey()); }
        config.addProperty(OWNER, owner); config.addProperty(KEYS, keys.toString());
        return config;
    }
    private void checkOwner(IncusClient.Resource resource) throws IOException {
        if (resource != null && !owner.equals(value(resource.value().getAsJsonObject("config"), OWNER)))
            throw new IOException("Existing resource is not owned by this controller");
    }
    private static List<String> tracked(JsonObject config, String key) throws IOException {
        String text = value(config, key);
        if (text.isEmpty()) return List.of();
        try { return JsonParser.parseString(text).getAsJsonArray().asList().stream().map(JsonElement::getAsString).toList(); }
        catch (RuntimeException e) { throw new IOException("Invalid Tend ownership metadata", e); }
    }
    private static String value(JsonObject config, String key) { return config.has(key) ? config.get(key).getAsString() : ""; }
    private static String instancePath(String name) { return "/1.0/instances/" + IncusClient.encode(name); }
    private static void update(MessageDigest digest, String value) {
        byte[] bytes = value.getBytes(StandardCharsets.UTF_8);
        digest.update(java.nio.ByteBuffer.allocate(4).putInt(bytes.length).array()); digest.update(bytes);
    }
}
