package work.archaic.tend;

import com.google.gson.*;
import java.io.IOException;
import java.security.MessageDigest;
import java.util.*;
import work.archaic.tend.incus.IncusClient;
import work.archaic.tend.state.DesiredState;
import static work.archaic.tend.ResourceSupport.*;

/** Converges an instance and persists successful activation after state operations complete. */
final class Instances {
    private final IncusClient incus;
    private final ResourceSupport resources;
    Instances(IncusClient incus, ResourceSupport resources) { this.incus = incus; this.resources = resources; }
    void reconcile(DesiredState.Instance desired, Map<String, String> volumeDigests) throws IOException, InterruptedException {
        String path = instancePath(desired.name());
        var current = incus.get(path);
        JsonObject config = resources.managedConfig(current, desired.config());
        config.addProperty(IMAGE, desired.fingerprint());
        JsonObject devices = devices(current, desired.devices());
        JsonArray names = new JsonArray(); new TreeMap<>(desired.devices()).keySet().forEach(names::add);
        config.addProperty(DEVICES, names.toString());
        String activation = activation(desired, volumeDigests);
        boolean activated = current != null && activation.equals(value(current.value().getAsJsonObject("config"), ACTIVATED));
        if (current == null) {
            create(desired, config, devices);
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
    private JsonObject devices(IncusClient.Resource current, Map<String, Map<String, String>> desired) throws IOException {
        JsonObject devices = current == null ? new JsonObject() : current.value().getAsJsonObject("devices").deepCopy();
        if (current != null) for (String name : tracked(current.value().getAsJsonObject("config"), DEVICES)) devices.remove(name);
        for (var entry : new TreeMap<>(desired).entrySet()) {
            JsonObject properties = new JsonObject(); entry.getValue().forEach(properties::addProperty);
            devices.add(entry.getKey(), properties);
        }
        return devices;
    }
    private static String activation(DesiredState.Instance desired, Map<String, String> volumeDigests) {
        MessageDigest digest = digest();
        update(digest, new TreeMap<>(desired.config()).toString());
        for (var entry : new TreeMap<>(desired.devices()).entrySet()) {
            update(digest, entry.getKey()); update(digest, new TreeMap<>(entry.getValue()).toString());
        }
        for (var entry : new TreeMap<>(desired.devices()).entrySet()) {
            var d = entry.getValue();
            if (d.containsKey("source")) update(digest, volumeDigests.getOrDefault(d.get("pool") + "/" + d.get("source"), ""));
        }
        return HexFormat.of().formatHex(digest.digest());
    }
    private void create(DesiredState.Instance desired, JsonObject config, JsonObject devices) throws IOException, InterruptedException {
        JsonObject body = new JsonObject(); body.addProperty("name", desired.name()); body.addProperty("type", desired.type());
        body.add("config", config); body.add("devices", devices); body.add("profiles", new JsonArray());
        JsonObject source = new JsonObject(); source.addProperty("type", "image"); source.addProperty("fingerprint", desired.fingerprint());
        body.add("source", source); body.addProperty("start", false);
        incus.mutate("POST", "/1.0/instances", body, null);
    }
    void markPending(String path) throws IOException, InterruptedException {
        var current = incus.get(path);
        if (current == null) return;
        resources.checkOwner(current);
        JsonObject config = current.value().getAsJsonObject("config").deepCopy();
        if (!config.has(ACTIVATED)) return;
        config.remove(ACTIVATED);
        // Empty string means deletion to Incus; omission alone would keep the old field in our merge.
        config.addProperty(ACTIVATED, "");
        updateInstance(path, config, current.value().getAsJsonObject("devices"));
    }
    private void updateInstance(String path, JsonObject config, JsonObject devices) throws IOException, InterruptedException {
        var current = incus.get(path);
        if (current == null) throw new ReconciliationException("Instance disappeared during update");
        resources.checkOwner(current);
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
    private boolean running(String path) throws IOException, InterruptedException {
        var state = incus.get(path + "/state");
        if (state == null) throw new ReconciliationException("Instance state disappeared");
        int status = state.value().get("status_code").getAsInt();
        if (status != 102 && status != 103) throw new ReconciliationException("Instance is in a transitional or unsupported state");
        return status == 103;
    }
    private void action(String path, String action) throws IOException, InterruptedException {
        JsonObject body = new JsonObject(); body.addProperty("action", action); body.addProperty("timeout", 30);
        body.addProperty("force", false); body.addProperty("stateful", false);
        incus.mutate("PUT", path + "/state", body, null);
        if (running(path) != action.equals("start")) throw new ReconciliationException("Instance state did not converge");
    }
}
