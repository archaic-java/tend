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
        if (etag == null && !path.endsWith("/state")) throw new IOException("Incus resource omitted ETag");
        return new Resource(envelope.getAsJsonObject("metadata"), etag);
    }
    public void mutate(String method, String path, JsonObject value, String etag) throws IOException, InterruptedException {
        Map<String, String> headers = new LinkedHashMap<>();
        headers.put("Content-Type", "application/json");
        if (etag != null) headers.put("If-Match", etag);
        JsonObject response = envelope(send(method, path, value.toString().getBytes(StandardCharsets.UTF_8), headers));
        if ("async".equals(response.get("type").getAsString())) {
            String operation = response.get("operation").getAsString();
            if (!operation.matches("/1\\.0/operations/[a-zA-Z0-9-]+")) throw new IOException("Unexpected operation URL");
            long deadline = System.nanoTime() + timeout.toNanos();
            do {
                JsonObject status = envelope(send("GET", operation + "/wait?timeout=1", null, Map.of())).getAsJsonObject("metadata");
                int code = status.get("status_code").getAsInt();
                if (code == 200) return;
                if (code >= 400) throw new IOException("Incus operation failed (status " + code + ")");
            } while (System.nanoTime() < deadline);
            throw new IOException("Incus operation timed out; next pass must reobserve");
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
        return response.headers().firstValue("X-Incus-" + suffix).orElseThrow(() -> new IOException("File metadata missing " + suffix));
    }
    private HttpResponse<byte[]> send(String method, String path, byte[] body, Map<String, String> headers)
            throws IOException, InterruptedException {
        if (!path.startsWith("/1.0/")) throw new IOException("Unexpected Incus path");
        URI uri = endpoint.resolve(path + (path.contains("?") ? "&" : "?") + "project=" + encode(project));
        HttpRequest.Builder request = HttpRequest.newBuilder(uri).timeout(timeout).method(method,
                body == null ? HttpRequest.BodyPublishers.noBody() : HttpRequest.BodyPublishers.ofByteArray(body));
        headers.forEach(request::header);
        return http.send(request.build(), HttpResponse.BodyHandlers.ofByteArray());
    }
    private static void requireSuccess(HttpResponse<byte[]> response) throws IOException {
        if (response.statusCode() < 200 || response.statusCode() >= 300)
            throw new IOException("Incus request failed (HTTP " + response.statusCode() + ")");
    }
    private static JsonObject envelope(HttpResponse<byte[]> response) throws IOException {
        requireSuccess(response);
        try {
            JsonObject result = JsonParser.parseString(new String(response.body(), StandardCharsets.UTF_8)).getAsJsonObject();
            String type = result.get("type").getAsString();
            if (!type.equals("sync") && !type.equals("async")) throw new IOException("Incus returned an error envelope");
            return result;
        } catch (RuntimeException e) { throw new IOException("Invalid Incus response", e); }
    }
    @Override public void close() { http.close(); }
}
