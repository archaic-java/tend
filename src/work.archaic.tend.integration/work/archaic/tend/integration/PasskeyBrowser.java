package work.archaic.tend.integration;

import com.google.gson.*;
import java.io.IOException;
import java.net.URI;
import java.net.http.*;
import java.nio.file.*;
import java.time.Duration;
import java.util.*;
import java.util.concurrent.*;
import java.util.regex.Pattern;

/** Test-only CDP probe. Credential material never enters command evidence or exception messages. */
final class PasskeyBrowser implements AutoCloseable {
    private final Path directory;
    private Process process;
    private WebSocket socket;
    private HttpClient http;
    private final BlockingQueue<JsonObject> responses = new LinkedBlockingQueue<>();
    private int sequence;
    private String authenticator;
    PasskeyBrowser(IncusCommands incus, Path directory) throws Exception {
        this.directory = directory.resolve("browser");
        Files.createDirectory(this.directory);
        incus.require("config", "device", "add", AuthorizationFixture.GATEWAY, "browser-probe", "proxy",
                "listen=tcp:127.0.0.1:443", "connect=tcp:127.0.0.1:443");
        Path ca = directory.resolve("root.crt");
        Path nss = Path.of(System.getProperty("user.home"), ".pki/nssdb");
        Files.createDirectories(nss);
        if (!Files.exists(nss.resolve("cert9.db"))) {
            var result = incus.command(Duration.ofSeconds(15), List.of("certutil", "-N", "--empty-password", "-d", "sql:" + nss));
            if (result.status() != 0) throw new IOException("Cannot initialize browser CA trust");
        }
        var trust = incus.command(Duration.ofSeconds(15), List.of("certutil", "-A", "-d", "sql:" + nss, "-n", "Tend disposable Caddy CA", "-t", "C,,", "-i", ca.toString()));
        if (trust.status() != 0) throw new IOException("Cannot trust disposable gateway CA");
        try {
            process = new ProcessBuilder("google-chrome", "--headless=new", "--no-first-run", "--no-default-browser-check",
                    "--no-proxy-server", "--host-resolver-rules=MAP *.garden.internal 127.0.0.1",
                    "--remote-debugging-address=127.0.0.1", "--remote-debugging-port=0", "--user-data-dir=" + this.directory, "about:blank")
                    .redirectErrorStream(true).redirectOutput(this.directory.resolve("chrome.log").toFile()).start();
            process.getOutputStream().close();
            Path portFile = this.directory.resolve("DevToolsActivePort");
            long deadline = System.nanoTime() + Duration.ofSeconds(30).toNanos();
            while (!Files.exists(portFile) && process.isAlive() && System.nanoTime() < deadline) Thread.sleep(100);
            if (!Files.exists(portFile)) throw new IOException("Browser did not start its loopback probe endpoint");
            int port = Integer.parseInt(Files.readAllLines(portFile).getFirst());
            http = HttpClient.newBuilder().connectTimeout(Duration.ofSeconds(5)).build();
            {
                var reply = http.send(HttpRequest.newBuilder(URI.create("http://127.0.0.1:" + port + "/json/list"))
                        .timeout(Duration.ofSeconds(5)).build(), HttpResponse.BodyHandlers.ofString());
                var page = JsonParser.parseString(reply.body()).getAsJsonArray().get(0).getAsJsonObject();
                socket = http.newWebSocketBuilder().connectTimeout(Duration.ofSeconds(5))
                        .buildAsync(URI.create(page.get("webSocketDebuggerUrl").getAsString()), new WebSocket.Listener() {
                            private final StringBuilder text = new StringBuilder();
                            public void onOpen(WebSocket ws) { ws.request(1); }
                            public CompletionStage<?> onText(WebSocket ws, CharSequence part, boolean last) {
                                text.append(part);
                                if (last) {
                                    var message = JsonParser.parseString(text.toString()).getAsJsonObject(); text.setLength(0);
                                    if (message.has("id")) responses.add(message);
                                }
                                ws.request(1); return null;
                            }
                        }).get(10, TimeUnit.SECONDS);
                call("Runtime.enable", new JsonObject());
                call("Page.enable", new JsonObject());
                call("WebAuthn.enable", JsonParser.parseString("{\"enableUI\":false}").getAsJsonObject());
                call("Page.navigate", JsonParser.parseString("{\"url\":\"https://auth.garden.internal/\"}").getAsJsonObject());
                deadline = System.nanoTime() + Duration.ofSeconds(15).toNanos();
                while (!evaluate("location.origin === 'https://auth.garden.internal' && document.readyState === 'complete'").getAsBoolean()) {
                    if (System.nanoTime() > deadline) throw new IOException("Browser could not load verified gateway HTTPS");
                    Thread.sleep(100);
                }
                evaluate(Files.readString(Path.of("scripts/incus-smoke/passkey-probe.js")));
            }
        } catch (Exception error) { close(); throw error; }
    }
    JsonElement evaluate(String expression) throws Exception {
        var parameters = new JsonObject(); parameters.addProperty("expression", expression);
        parameters.addProperty("awaitPromise", true); parameters.addProperty("returnByValue", true); parameters.addProperty("userGesture", true);
        var result = call("Runtime.evaluate", parameters);
        if (result.has("exceptionDetails")) throw new IOException("Browser probe evaluation failed (private details withheld)");
        var value = result.getAsJsonObject("result");
        return value.has("value") ? value.get("value") : JsonNull.INSTANCE;
    }
    private JsonObject call(String method, JsonObject parameters) throws Exception {
        var request = new JsonObject(); request.addProperty("id", ++sequence); request.addProperty("method", method); request.add("params", parameters);
        socket.sendText(request.toString(), true).get(5, TimeUnit.SECONDS);
        var response = responses.poll(20, TimeUnit.SECONDS);
        if (response == null || response.get("id").getAsInt() != sequence || response.has("error"))
            throw new IOException("Browser protocol command failed: " + method);
        return response.getAsJsonObject("result");
    }
    void authenticator() throws Exception {
        if (authenticator != null) call("WebAuthn.removeVirtualAuthenticator", id());
        var options = JsonParser.parseString("""
                {"options":{"protocol":"ctap2","transport":"internal","hasResidentKey":true,
                "hasUserVerification":true,"isUserVerified":true,"automaticPresenceSimulation":true}}
                """).getAsJsonObject();
        authenticator = call("WebAuthn.addVirtualAuthenticator", options).get("authenticatorId").getAsString();
    }
    private JsonObject id() { var result = new JsonObject(); result.addProperty("authenticatorId", authenticator); return result; }
    void verified(boolean value) throws Exception {
        var parameters = id(); parameters.addProperty("isUserVerified", value); call("WebAuthn.setUserVerified", parameters);
    }
    void clearSession() throws Exception { call("Network.clearBrowserCookies", new JsonObject()); }
    int password(String user) throws Exception {
        String fixture = Files.readString(Path.of("out/incus-smoke/authelia-fixture-directory.txt")).strip();
        String json = Files.readString(Path.of(fixture, user + ".json"));
        return evaluate("tendProbe.password(" + json + ")").getAsInt();
    }
    int startElevation() throws Exception { return evaluate("tendProbe.elevation()").getAsInt(); }
    int finishElevation() throws Exception {
        // Read privately rather than using IncusCommands, whose output is uploaded as evidence.
        Path notification = directory.resolve("notification.txt");
        var pull = new ProcessBuilder("sudo", "-n", "incus", "exec", AuthorizationFixture.AUTH, "--", "cat", "/var/lib/authelia/notifications.txt")
                .redirectErrorStream(true).redirectOutput(notification.toFile()).start();
        pull.getOutputStream().close();
        try {
            if (!pull.waitFor(10, TimeUnit.SECONDS) || pull.exitValue() != 0) throw new IOException("Cannot read private enrollment notification");
            var match = Pattern.compile("-{40}\\s+([A-Z0-9]{6,20})\\s+-{40}").matcher(Files.readString(notification));
            if (!match.find()) throw new IOException("Enrollment notification did not contain a code");
            return evaluate("tendProbe.verify(" + new Gson().toJson(match.group(1)) + ")").getAsInt();
        } finally { if (pull.isAlive()) pull.destroyForcibly(); Files.deleteIfExists(notification); }
    }
    JsonObject register() throws Exception { return evaluate("tendProbe.register()").getAsJsonObject(); }
    JsonObject login(String mode) throws Exception { return evaluate("tendProbe.login(" + new Gson().toJson(mode) + ")").getAsJsonObject(); }
    IncusCommands.Result request(IncusCommands incus, AuthorizationFixture fixture) throws Exception {
        var parameters = JsonParser.parseString("{\"urls\":[\"https://auth.garden.internal\"]}").getAsJsonObject();
        var cookies = call("Network.getCookies", parameters).getAsJsonArray("cookies");
        var text = new StringBuilder("# Netscape HTTP Cookie File\n");
        for (var entry : cookies) {
            var cookie = entry.getAsJsonObject();
            String domain = cookie.get("domain").getAsString();
            text.append(domain).append('\t').append(domain.startsWith(".") ? "TRUE" : "FALSE")
                    .append('\t').append(cookie.get("path").getAsString()).append("\tTRUE\t0\t")
                    .append(cookie.get("name").getAsString()).append('\t').append(cookie.get("value").getAsString()).append('\n');
        }
        Path file = directory.resolve("session.cookies");
        Files.writeString(file, text);
        Files.setPosixFilePermissions(file, java.nio.file.attribute.PosixFilePermissions.fromString("rw-------"));
        try {
            incus.require("file", "push", file.toString(), "tend-ci-auth-client/root/passkey.cookies", "--mode=0600");
            return fixture.request("passkey");
        } finally { Files.deleteIfExists(file); }
    }
    public void close() {
        if (socket != null) socket.abort();
        if (http != null) http.shutdownNow();
        if (process != null && process.isAlive()) {
            process.descendants().forEach(ProcessHandle::destroy);
            process.destroy();
            try { if (!process.waitFor(5, TimeUnit.SECONDS)) { process.destroyForcibly(); process.waitFor(5, TimeUnit.SECONDS); } }
            catch (InterruptedException error) { process.destroyForcibly(); Thread.currentThread().interrupt(); }
        }
    }
}
