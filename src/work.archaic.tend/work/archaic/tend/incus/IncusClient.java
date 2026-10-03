package work.archaic.tend.incus;

import com.google.gson.*;
import java.io.IOException;
import java.net.*;
import java.net.http.*;
import java.nio.charset.StandardCharsets;
import java.time.Duration;
import java.util.*;

/** Narrow HTTP adapter: JSON envelopes, conditional updates, operations and volume files. */
public final class IncusClient implements AutoCloseable {
    private final URI endpoint;
    private final String project;
    private final HttpClient http;
    private final Duration timeout;

    public IncusClient(URI endpoint, String project, HttpClient http, Duration timeout) {
        if (!Set.of("http", "https").contains(endpoint.getScheme()) || endpoint.getHost() == null ||
                endpoint.getRawQuery() != null || endpoint.getRawUserInfo() != null ||
                !(endpoint.getPath().isEmpty() || endpoint.getPath().equals("/")))
            throw new IllegalArgumentException("Incus endpoint must be an HTTP(S) origin");
        if (!project.matches("[a-zA-Z0-9][a-zA-Z0-9_.-]{0,62}")) throw new IllegalArgumentException("Invalid project");
        this.endpoint = endpoint;
        this.project = project;
        this.http = http;
        this.timeout = timeout;
    }

    public record Resource(JsonObject value, String etag) {}
    public record File(byte[] content, String uid, String gid, String mode, String type) {}

    public Resource get(String path) throws IOException, InterruptedException {
        var response = send("GET", path, null, Map.of());
        if (response.statusCode() == 404) return null;
        JsonObject envelope = envelope(response);
        String etag = response.headers().firstValue("ETag").orElse(null);
        if (etag == null && !path.endsWith("/state")) throw new IncusException("Incus resource omitted ETag");
        JsonObject metadata = metadata(envelope);
        validateResource(path, metadata);
        return new Resource(metadata, etag);
    }
    public void mutate(String method, String path, JsonObject value, String etag) throws IOException, InterruptedException {
        Map<String, String> headers = new LinkedHashMap<>();
        headers.put("Content-Type", "application/json");
        if (etag != null) headers.put("If-Match", etag);
        JsonObject response = envelope(send(method, path, value.toString().getBytes(StandardCharsets.UTF_8), headers));
        if ("async".equals(response.get("type").getAsString())) {
            String operation = string(response, "operation");
            if (!operation.matches("/1\\.0/operations/[a-zA-Z0-9-]+")) throw new IncusException("Unexpected operation URL");
            long deadline = System.nanoTime() + timeout.toNanos();
            do {
                JsonObject status = metadata(envelope(send("GET", operation + "/wait?timeout=1", null, Map.of())));
                int code = statusCode(status);
                if (code == 200) return;
                if (code >= 400) throw new IncusException("Incus operation failed (status " + code + ")");
            } while (System.nanoTime() < deadline);
            throw new IncusException("Incus operation timed out; next pass must reobserve");
        }
    }
    public File file(String volumePath, String filePath) throws IOException, InterruptedException {
        var response = send("GET", volumePath + "/files?path=" + encode(filePath), null, Map.of());
        if (response.statusCode() == 404) return null;
        requireSuccess(response);
        return new File(response.body(), header(response, "uid"), header(response, "gid"),
                header(response, "mode"), header(response, "type"));
    }
    public void writeFile(String volumePath, String filePath, byte[] bytes, int uid, int gid, String mode)
            throws IOException, InterruptedException {
        envelope(send("POST", volumePath + "/files?path=" + encode(filePath), bytes, Map.of(
                "Content-Type", "application/octet-stream", "X-Incus-uid", Integer.toString(uid),
                "X-Incus-gid", Integer.toString(gid), "X-Incus-mode", mode,
                "X-Incus-type", "file", "X-Incus-write", "overwrite")));
    }
    public static String volumePath(String pool, String name) {
        return "/1.0/storage-pools/" + encode(pool) + "/volumes/custom/" + encode(name);
    }
    public static String encode(String value) { return URLEncoder.encode(value, StandardCharsets.UTF_8).replace("+", "%20"); }
    private static String header(HttpResponse<?> response, String suffix) throws IOException {
        return response.headers().firstValue("X-Incus-" + suffix).orElseThrow(() -> new IncusException("File metadata missing " + suffix));
    }
    private HttpResponse<byte[]> send(String method, String path, byte[] body, Map<String, String> headers)
            throws IOException, InterruptedException {
        if (!path.startsWith("/1.0/")) throw new IncusException("Unexpected Incus path");
        URI uri = endpoint.resolve(path + (path.contains("?") ? "&" : "?") + "project=" + encode(project));
        HttpRequest.Builder request = HttpRequest.newBuilder(uri).timeout(timeout).method(method,
                body == null ? HttpRequest.BodyPublishers.noBody() : HttpRequest.BodyPublishers.ofByteArray(body));
        headers.forEach(request::header);
        return http.send(request.build(), HttpResponse.BodyHandlers.ofByteArray());
    }
    private static void requireSuccess(HttpResponse<byte[]> response) throws IOException {
        if (response.statusCode() < 200 || response.statusCode() >= 300)
            throw new IncusException("Incus request failed (HTTP " + response.statusCode() + ")");
    }
    private static JsonObject envelope(HttpResponse<byte[]> response) throws IOException {
        requireSuccess(response);
        try {
            JsonObject result = JsonParser.parseString(new String(response.body(), StandardCharsets.UTF_8)).getAsJsonObject();
            String type = string(result, "type");
            if (!type.equals("sync") && !type.equals("async")) throw new IncusException("Incus returned an error envelope");
            return result;
        } catch (JsonParseException | IllegalStateException | UnsupportedOperationException | ClassCastException e) { throw new IncusException("Invalid Incus response", e); }
    }
    private static JsonObject metadata(JsonObject envelope) throws IncusException {
        JsonElement value = envelope.get("metadata");
        if (value == null || !value.isJsonObject()) throw new IncusException("Incus metadata must be an object");
        return value.getAsJsonObject();
    }
    private static String string(JsonObject object, String key) throws IncusException {
        JsonElement value = object.get(key);
        if (value == null || !value.isJsonPrimitive() || !value.getAsJsonPrimitive().isString())
            throw new IncusException("Incus string field missing or malformed: " + key);
        return value.getAsString();
    }
    private static int statusCode(JsonObject object) throws IncusException {
        JsonElement value = object.get("status_code");
        if (value == null || !value.isJsonPrimitive() || !value.getAsJsonPrimitive().isNumber())
            throw new IncusException("Incus status code missing or malformed");
        try { return value.getAsBigDecimal().intValueExact(); }
        catch (ArithmeticException | NumberFormatException e) { throw new IncusException("Incus status code out of bounds", e); }
    }
    private static void validateResource(String path, JsonObject object) throws IncusException {
        if (path.endsWith("/state")) { statusCode(object); return; }
        stringMap(object.get("config"));
        if (!path.startsWith("/1.0/instances/")) return;
        string(object, "type");
        JsonElement devices = object.get("devices"), profiles = object.get("profiles");
        if (devices == null || !devices.isJsonObject() || profiles == null || !profiles.isJsonArray())
            throw new IncusException("Incus instance devices or profiles missing or malformed");
        for (var device : devices.getAsJsonObject().entrySet()) stringMap(device.getValue());
        for (var profile : profiles.getAsJsonArray()) {
            if (!profile.isJsonPrimitive() || !profile.getAsJsonPrimitive().isString()) throw new IncusException("Malformed Incus profile name");
        }
    }
    private static void stringMap(JsonElement element) throws IncusException {
        if (element == null || !element.isJsonObject()) throw new IncusException("Incus configuration must be an object");
        for (var field : element.getAsJsonObject().entrySet()) string(element.getAsJsonObject(), field.getKey());
    }
    @Override public void close() { http.close(); }
}
