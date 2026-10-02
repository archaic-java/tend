package work.archaic.tend.test;

import com.google.gson.*;
import com.sun.net.httpserver.*;
import java.io.*;
import java.net.*;
import java.nio.charset.StandardCharsets;
import java.util.*;
import java.util.concurrent.*;

/** Stateful subset of the documented Incus HTTP API; never used by production. */
final class IncusMock implements AutoCloseable {
    private final HttpServer server;
    private final ExecutorService executor = Executors.newVirtualThreadPerTaskExecutor();
    final Map<String, JsonObject> resources = new LinkedHashMap<>();
    final Map<String, StoredFile> files = new HashMap<>();
    final Map<String, Boolean> running = new HashMap<>();
    final List<String> requests = new ArrayList<>();
    final Map<String, Pending> operations = new HashMap<>();
    int mutations;
    int starts;
    int nextOperation;
    int nextEtag;
    String failurePath;
    boolean failStart;
    boolean stall;
    boolean conflict;
    boolean loseResponse;
    record StoredFile(byte[] bytes, String uid, String gid, String mode) {}
    record Pending(Runnable action, boolean failed) {}

    IncusMock() throws IOException {
        server = HttpServer.create(new InetSocketAddress("127.0.0.1", 0), 0);
        server.setExecutor(executor);
        server.createContext("/", this::handle);
        server.start();
    }
    URI endpoint() { return URI.create("http://127.0.0.1:" + server.getAddress().getPort()); }
    synchronized void seed(String path, JsonObject object) { resources.put(path, object.deepCopy()); }
    synchronized void drift(String path, String key, String value) { resources.get(path).getAsJsonObject("config").addProperty(key, value); nextEtag++; }

    private synchronized void handle(HttpExchange x) throws IOException {
        try {
            String method = x.getRequestMethod(), path = x.getRequestURI().getPath();
            Map<String, String> query = query(x.getRequestURI().getRawQuery());
            if (!"garden".equals(query.get("project"))) { error(x, 400); return; }
            requests.add(method + " " + path);
            if (failurePath != null && path.equals(failurePath)) { failurePath = null; error(x, 503); return; }
            if (path.endsWith("/wait")) {
                String op = path.substring(0, path.length() - 5);
                Pending pending = operations.get(op);
                if (pending == null) { error(x, 404); return; }
                JsonObject status = new JsonObject();
                if (stall) { status.addProperty("status_code", 103); }
                else if (pending.failed()) { status.addProperty("status_code", 400); status.addProperty("err", "simulated failure"); }
                else { if (pending.action() != null) { pending.action().run(); operations.put(op, new Pending(null, false)); } status.addProperty("status_code", 200); }
                sync(x, status); return;
            }
            if (path.endsWith("/files")) { file(x, method, path, query.get("path")); return; }
            if (path.endsWith("/state")) {
                String instance = path.substring(0, path.length() - 6);
                if (!resources.containsKey(instance)) { error(x, 404); return; }
                if (method.equals("GET")) {
                    JsonObject state = new JsonObject(); state.addProperty("status_code", running.getOrDefault(instance, false) ? 103 : 102);
                    // Actual state observations need no ETag; conditional updates concern configuration.
                    sync(x, state); return;
                }
                JsonObject body = body(x);
                String action = body.get("action").getAsString();
                if (!Set.of("start", "stop").contains(action) || body.get("force").getAsBoolean()) { error(x, 400); return; }
                boolean failed = action.equals("start") && failStart;
                if (action.equals("start")) failStart = false;
                async(x, () -> { running.put(instance, action.equals("start")); if (action.equals("start")) starts++; }, failed);
                return;
            }
            if (method.equals("GET")) {
                JsonObject resource = resources.get(path);
                if (resource == null) { error(x, 404); return; }
                x.getResponseHeaders().set("ETag", etag()); sync(x, resource); return;
            }
            if (method.equals("PUT")) {
                if (!resources.containsKey(path)) { error(x, 404); return; }
                if (conflict) { conflict = false; nextEtag++; error(x, 412); return; }
                if (!etag().equals(x.getRequestHeaders().getFirst("If-Match"))) { error(x, 412); return; }
                JsonObject body = body(x);
                if (path.startsWith("/1.0/instances/")) {
                    if (body.has("source") || body.has("name") || body.has("type")) { error(x, 400); return; }
                    async(x, () -> { body.entrySet().forEach(e -> resources.get(path).add(e.getKey(), e.getValue())); nextEtag++; }, false);
                } else { body.entrySet().forEach(e -> resources.get(path).add(e.getKey(), e.getValue())); mutations++; nextEtag++; sync(x, new JsonObject()); }
                return;
            }
            if (method.equals("POST")) {
                JsonObject body = body(x);
                String target = path + "/" + body.get("name").getAsString();
                if (resources.containsKey(target)) { error(x, 409); return; }
                if (path.equals("/1.0/instances")) {
                    if (!body.getAsJsonObject("source").get("type").getAsString().equals("image") ||
                            !body.getAsJsonObject("source").get("fingerprint").getAsString().matches("[a-f0-9]{64}") ||
                            !body.getAsJsonArray("profiles").isEmpty() || body.get("start").getAsBoolean()) { error(x, 400); return; }
                    async(x, () -> {
                        JsonObject resource = body.deepCopy(); resource.remove("source"); resource.remove("start");
                        resource.getAsJsonObject("config").addProperty("volatile.test", "preserve");
                        resources.put(target, resource); running.put(target, false); nextEtag++;
                    }, false);
                } else if (path.endsWith("/volumes/custom")) {
                    if (!body.get("type").getAsString().equals("custom") || !body.get("content_type").getAsString().equals("filesystem")) { error(x, 400); return; }
                    resources.put(target, body); mutations++; nextEtag++;
                    if (loseResponse) { loseResponse = false; x.close(); return; }
                    sync(x, new JsonObject());
                } else { error(x, 404); }
                return;
            }
            error(x, 405);
        } catch (RuntimeException e) { error(x, 400); }
        finally { x.close(); }
    }
    private void file(HttpExchange x, String method, String path, String file) throws IOException {
        String volume = path.substring(0, path.length() - 6);
        if (!resources.containsKey(volume)) { error(x, 404); return; }
        String key = volume + file;
        if (method.equals("GET")) {
            StoredFile existing = files.get(key);
            if (existing == null) { error(x, 404); return; }
            x.getResponseHeaders().set("Content-Type", "application/octet-stream");
            x.getResponseHeaders().set("X-Incus-type", "file");
            x.getResponseHeaders().set("X-Incus-uid", existing.uid());
            x.getResponseHeaders().set("X-Incus-gid", existing.gid());
            x.getResponseHeaders().set("X-Incus-mode", existing.mode());
            x.sendResponseHeaders(200, existing.bytes().length); x.getResponseBody().write(existing.bytes());
        } else if (method.equals("POST")) {
            var h = x.getRequestHeaders();
            if (!"file".equals(h.getFirst("X-Incus-type")) || !"overwrite".equals(h.getFirst("X-Incus-write"))) { error(x, 400); return; }
            files.put(key, new StoredFile(x.getRequestBody().readAllBytes(), h.getFirst("X-Incus-uid"), h.getFirst("X-Incus-gid"), h.getFirst("X-Incus-mode")));
            mutations++; sync(x, new JsonObject());
        } else error(x, 405);
    }
    private void async(HttpExchange x, Runnable action, boolean failed) throws IOException {
        mutations++;
        String operation = "/1.0/operations/op-" + ++nextOperation;
        operations.put(operation, new Pending(action, failed));
        JsonObject result = new JsonObject(); result.addProperty("type", "async"); result.addProperty("status_code", 100);
        result.addProperty("operation", operation); result.add("metadata", new JsonObject());
        json(x, 202, result);
    }
    private String etag() { return "\"revision-" + nextEtag + "\""; }
    private static JsonObject body(HttpExchange x) throws IOException {
        return JsonParser.parseString(new String(x.getRequestBody().readAllBytes(), StandardCharsets.UTF_8)).getAsJsonObject();
    }
    private static Map<String, String> query(String raw) {
        Map<String, String> result = new HashMap<>();
        if (raw != null) for (String part : raw.split("&")) {
            String[] pair = part.split("=", 2);
            result.put(URLDecoder.decode(pair[0], StandardCharsets.UTF_8), pair.length == 2 ? URLDecoder.decode(pair[1], StandardCharsets.UTF_8) : "");
        }
        return result;
    }
    private static void sync(HttpExchange x, JsonElement metadata) throws IOException {
        JsonObject result = new JsonObject(); result.addProperty("type", "sync"); result.addProperty("status_code", 200); result.add("metadata", metadata); json(x, 200, result);
    }
    private static void error(HttpExchange x, int code) throws IOException {
        JsonObject result = new JsonObject(); result.addProperty("type", "error"); result.addProperty("error_code", code); result.addProperty("error", "simulated failure"); json(x, code, result);
    }
    private static void json(HttpExchange x, int code, JsonElement json) throws IOException {
        byte[] bytes = json.toString().getBytes(StandardCharsets.UTF_8);
        x.getResponseHeaders().set("Content-Type", "application/json");
        x.sendResponseHeaders(code, bytes.length); x.getResponseBody().write(bytes);
    }
    @Override public void close() { server.stop(0); executor.close(); }
}
