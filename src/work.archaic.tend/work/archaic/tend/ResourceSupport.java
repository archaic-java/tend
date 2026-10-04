package work.archaic.tend;

import com.google.gson.*;
import java.nio.charset.StandardCharsets;
import java.security.MessageDigest;
import java.util.*;
import work.archaic.tend.incus.IncusClient;

/** Ownership metadata and deterministic activation digests shared by resource reconcilers. */
final class ResourceSupport {
    private final String owner;
    ResourceSupport(String owner) { this.owner = owner; }
    static final String OWNER = "user.tend.owner";
    static final String KEYS = "user.tend.keys";
    static final String DEVICES = "user.tend.devices";
    static final String ACTIVATED = "user.tend.activated";
    static final String IMAGE = "user.tend.image";

    JsonObject managedConfig(IncusClient.Resource current, Map<String, String> desired) throws ReconciliationException {
        checkOwner(current);
        JsonObject config = current == null ? new JsonObject() : current.value().getAsJsonObject("config").deepCopy();
        for (String key : tracked(config, KEYS)) config.remove(key);
        JsonArray keys = new JsonArray();
        for (var entry : new TreeMap<>(desired).entrySet()) { config.addProperty(entry.getKey(), entry.getValue()); keys.add(entry.getKey()); }
        config.addProperty(OWNER, owner); config.addProperty(KEYS, keys.toString());
        return config;
    }
    void checkOwner(IncusClient.Resource resource) throws ReconciliationException {
        if (resource != null && !owner.equals(value(resource.value().getAsJsonObject("config"), OWNER)))
            throw new ReconciliationException("Existing resource is not owned by this controller");
    }
    static List<String> tracked(JsonObject config, String key) throws ReconciliationException {
        String text = value(config, key);
        if (text.isEmpty()) return List.of();
        try { return JsonParser.parseString(text).getAsJsonArray().asList().stream().map(JsonElement::getAsString).toList(); }
        catch (JsonParseException | IllegalStateException | UnsupportedOperationException | ClassCastException e) { throw new ReconciliationException("Invalid Tend ownership metadata", e); }
    }
    static String value(JsonObject config, String key) { return config.has(key) ? config.get(key).getAsString() : ""; }
    static String instancePath(String name) { return "/1.0/instances/" + IncusClient.encode(name); }
    static MessageDigest digest() {
        try { return MessageDigest.getInstance("SHA-256"); }
        catch (java.security.NoSuchAlgorithmException e) { throw new AssertionError("JDK must support SHA-256", e); }
    }
    static void update(MessageDigest digest, String value) {
        byte[] bytes = value.getBytes(StandardCharsets.UTF_8);
        digest.update(java.nio.ByteBuffer.allocate(4).putInt(bytes.length).array()); digest.update(bytes);
    }
}
