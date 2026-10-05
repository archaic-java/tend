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
    private final Set<String> privateValues = ConcurrentHashMap.newKeySet();
    private final StringBuilder navigationEvidence = new StringBuilder();
    PasskeyBrowser(IncusCommands incus, Path directory) throws Exception {
        this.directory = directory.resolve("browser");
        Files.createDirectory(this.directory);
        incus.require("config", "device", "add", AuthorizationFixture.GATEWAY, "browser-probe", "proxy",
                "listen=tcp:127.0.0.1:443", "connect=tcp:127.0.0.1:443");
        Path ca = directory.resolve("root.crt");
        var proxy = incus.command(Duration.ofSeconds(10), List.of("curl", "--silent", "--show-error", "--noproxy", "*",
                "--connect-timeout", "2", "--max-time", "8", "--cacert", ca.toString(), "--resolve", "auth.garden.internal:443:127.0.0.1",
                "--output", "/dev/null", "--write-out", "%{http_code}", "https://auth.garden.internal/api/health"));
        if (proxy.status() != 0 || !proxy.output().strip().equals("200")) throw new IOException("Loopback HTTPS proxy probe failed: " + proxy.output());
        var portal = incus.command(Duration.ofSeconds(10), List.of("curl", "--silent", "--show-error", "--noproxy", "*",
                "--connect-timeout", "2", "--max-time", "8", "--cacert", ca.toString(), "--resolve", "auth.garden.internal:443:127.0.0.1",
                "--output", "/dev/null", "--write-out", "%{http_code}:%{content_type}", "https://auth.garden.internal/"));
        if (portal.status() != 0 || !portal.output().startsWith("200:text/html")) throw new IOException("Portal HTTPS response probe failed: " + portal.output());
        Path nss = Path.of(System.getProperty("user.home"), ".pki/nssdb");
        Files.createDirectories(nss);
        initializeTrustDatabase(incus, nss);
        var trust = incus.command(Duration.ofSeconds(15), List.of("certutil", "-A", "-d", "sql:" + nss, "-n", "Tend disposable Caddy CA", "-t", "C,,", "-i", ca.toString()));
        if (trust.status() != 0) throw new IOException("Cannot trust disposable gateway CA");
        try {
            process = new ProcessBuilder("google-chrome", "--headless=new", "--no-first-run", "--no-default-browser-check",
                    "--disable-background-networking", "--disable-default-apps", "--disable-extensions",
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
            initializeBrowser(port);
        } catch (Exception error) { close(); throw error; }
    }
    private static void initializeTrustDatabase(IncusCommands incus, Path nss) throws Exception {
        if (Files.exists(nss.resolve("cert9.db"))) return;
        var result = incus.command(Duration.ofSeconds(15), List.of("certutil", "-N", "--empty-password", "-d", "sql:" + nss));
        if (result.status() != 0) throw new IOException("Cannot initialize browser CA trust");
    }
    private void initializeBrowser(int port) throws Exception {
        var page = blankPage(port);
        socket = http.newWebSocketBuilder().connectTimeout(Duration.ofSeconds(5))
                .buildAsync(URI.create(page.get("webSocketDebuggerUrl").getAsString()), new WebSocket.Listener() {
                    private final StringBuilder text = new StringBuilder();
                    public void onOpen(WebSocket ws) { ws.request(1); }
                    public CompletionStage<?> onText(WebSocket ws, CharSequence part, boolean last) {
                        text.append(part);
                        if (!last) { ws.request(1); return null; }
                        var message = JsonParser.parseString(text.toString()).getAsJsonObject();
                        text.setLength(0);
                        receive(message);
                        ws.request(1);
                        return null;
                    }
                }).get(10, TimeUnit.SECONDS);
        call("Runtime.enable", new JsonObject());
        call("Page.enable", new JsonObject());
        call("Network.enable", new JsonObject());
        call("Security.enable", new JsonObject());
        call("WebAuthn.enable", JsonParser.parseString("{\"enableUI\":false}").getAsJsonObject());
        JsonObject navigation = null;
        for (int attempt = 0; attempt < 3; attempt++) {
            navigation = call("Page.navigate", JsonParser.parseString("{\"url\":\"https://auth.garden.internal/\"}").getAsJsonObject());
            if (!navigation.has("errorText") || !navigation.get("errorText").getAsString().equals("net::ERR_ABORTED")) break;
            Thread.sleep(300);
        }
        if (navigation.has("errorText")) throw new IOException("Browser HTTPS navigation failed: " + navigation.get("errorText").getAsString()
                + "; download=" + navigation.get("isDownload") + "; " + navigationEvidence
                + "; document=" + evaluate("({origin:location.origin,ready:document.readyState,title:document.title})"));
        long deadline = System.nanoTime() + Duration.ofSeconds(15).toNanos();
        while (!evaluate("location.origin === 'https://auth.garden.internal' && document.readyState === 'complete'").getAsBoolean()) {
            if (System.nanoTime() > deadline) throw new IOException("Browser could not load verified gateway HTTPS: " + evaluate("({origin:location.origin,ready:document.readyState})"));
            Thread.sleep(100);
        }
        evaluate(Files.readString(Path.of("scripts/incus-smoke/passkey-probe.js")));
    }
    private JsonObject blankPage(int port) throws Exception {
        long deadline = System.nanoTime() + Duration.ofSeconds(15).toNanos();
        while (true) {
            var reply = http.send(HttpRequest.newBuilder(URI.create("http://127.0.0.1:" + port + "/json/list"))
                    .timeout(Duration.ofSeconds(5)).build(), HttpResponse.BodyHandlers.ofString());
            var page = findBlankPage(reply.body());
            if (page != null) return page;
            if (System.nanoTime() > deadline) throw new IOException("Browser did not expose its blank page target");
            Thread.sleep(100);
        }
    }
    private static JsonObject findBlankPage(String body) {
        for (var entry : JsonParser.parseString(body).getAsJsonArray()) {
            var target = entry.getAsJsonObject();
            if (!target.get("type").getAsString().equals("page") || !target.get("url").getAsString().equals("about:blank")) continue;
            return target;
        }
        return null;
    }
    private void receive(JsonObject message) {
        if (message.has("id")) { responses.add(message); return; }
        if (!message.has("method")) return;
        String method = message.get("method").getAsString();
        var parameters = message.getAsJsonObject("params");
        if (method.equals("Network.requestWillBeSent")) rememberAuthorizationCode(parameters);
        if (navigationEvidence.length() >= 3000) return;
        switch (method) {
            case "Network.loadingFailed" -> navigationEvidence.append(" loadingFailed=").append(parameters.get("errorText")).append("; blocked=").append(parameters.get("blockedReason")).append("; canceled=").append(parameters.get("canceled"));
            case "Security.certificateError" -> navigationEvidence.append(" certificateError=").append(parameters.get("errorType"));
            case "Network.responseReceived" -> recordResponse(parameters);
            case "Page.frameRequestedNavigation" -> navigationEvidence.append(" navigationReason=").append(parameters.get("reason"));
            default -> { }
        }
    }
    private void rememberAuthorizationCode(JsonObject parameters) {
        var uri = URI.create(parameters.getAsJsonObject("request").get("url").getAsString());
        if (uri.getHost() == null || !uri.getHost().endsWith(".garden.internal")) return;
        Map<String, String> query;
        try { query = OidcFixture.query(uri); }
        catch (IOException malformedQuery) { navigationEvidence.append(" malformedQuery"); return; }
        String code = query.get("code");
        if (code != null) privateValues.add(code);
    }
    private void recordResponse(JsonObject parameters) {
        var response = parameters.getAsJsonObject("response");
        navigationEvidence.append(" response=").append(parameters.get("type")).append(':').append(response.get("status")).append(':').append(response.get("mimeType"));
    }
    JsonElement evaluate(String expression) throws Exception {
        var parameters = new JsonObject(); parameters.addProperty("expression", expression);
        parameters.addProperty("awaitPromise", true); parameters.addProperty("returnByValue", true); parameters.addProperty("userGesture", true);
        var result = call("Runtime.evaluate", parameters);
        if (result.has("exceptionDetails")) throw new IOException("Browser probe evaluation failed (private details withheld)");
        var value = result.getAsJsonObject("result");
        return value.has("value") ? value.get("value") : JsonNull.INSTANCE;
    }
    void navigate(String url, String origin) throws Exception {
        var parameters = new JsonObject(); parameters.addProperty("url", url);
        var reply = call("Page.navigate", parameters);
        if (reply.has("errorText")) throw new IOException("Application browser navigation failed (private URL withheld)");
        long deadline = System.nanoTime() + Duration.ofSeconds(20).toNanos();
        while (System.nanoTime() < deadline) {
            try {
                if (evaluate("location.origin === " + new Gson().toJson(origin) + " && document.readyState === 'complete'").getAsBoolean()) return;
            } catch (IOException transientNavigation) { /* Document execution context can change during redirects. */ }
            Thread.sleep(100);
        }
        throw new IOException("Application browser did not reach the expected origin");
    }
    void portal() throws Exception {
        navigate("https://auth.garden.internal/", "https://auth.garden.internal");
        probe();
    }
    void probe() throws Exception { evaluate(Files.readString(Path.of("scripts/incus-smoke/passkey-probe.js"))); }
    JsonObject consent() throws Exception {
        return evaluate("""
                (async () => {
                    const flow = new URL(location.href).searchParams.get('flow_id');
                    const response = await fetch('/api/oidc/consent?flow_id=' + encodeURIComponent(flow), {signal:AbortSignal.timeout(8000)});
                    const body = await response.json();
                    if (response.status !== 200 || !body.data) return {status:response.status};
                    return {status:response.status,client:body.data.client_id,login:body.data.require_login};
                })()
                """).getAsJsonObject();
    }
    void acceptConsent(String client, String destination) throws Exception {
        // Keep the authorization code and callback within the real browser; Grafana redeems it.
        String redirect = evaluate("""
                (async () => {
                    const flow = new URL(location.href).searchParams.get('flow_id');
                    const info = await fetch('/api/oidc/consent?flow_id=' + encodeURIComponent(flow), {signal:AbortSignal.timeout(8000)});
                    const data = (await info.json()).data;
                    if (info.status !== 200 || data.require_login || data.client_id !== %s) throw Error('consent');
                    const response = await fetch('/api/oidc/consent', {method:'POST',signal:AbortSignal.timeout(8000),
                        headers:{'Content-Type':'application/json'},
                        body:JSON.stringify({flow_id:flow,client_id:data.client_id,consent:true,pre_configure:false,claims:data.claims})});
                    if (response.status !== 200) throw Error('consent');
                    return (await response.json()).data.redirect_uri;
                })()
                """.formatted(new Gson().toJson(client))).getAsString();
        URI uri = URI.create(redirect);
        if (!uri.getScheme().equals("https") || !uri.getAuthority().equals("auth.garden.internal") || !uri.getPath().equals("/api/oidc/authorization"))
            throw new IOException("Unexpected consent continuation");
        navigate(redirect, destination);
    }
    JsonObject applicationUser() throws Exception {
        return evaluate("""
                (async () => {
                    const response = await fetch('/api/user', {signal:AbortSignal.timeout(8000)});
                    const body = await response.json();
                    return {status:response.status,id:body.id,login:body.login,email:body.email,name:body.name,
                        admin:body.isGrafanaAdmin,message:body.message};
                })()
                """).getAsJsonObject();
    }
    JsonObject webuiUser() throws Exception {
        return evaluate("""
                (async () => {
                    const response = await fetch('/api/v1/auths/', {signal:AbortSignal.timeout(8000)});
                    if (response.status !== 200) return {status:response.status};
                    const body = await response.json();
                    return {status:response.status,id:body.id,email:body.email,name:body.name,role:body.role};
                })()
                """).getAsJsonObject();
    }
    void clearWebuiSession() throws Exception {
        navigate(OpenWebuiFixture.ORIGIN + "/health", OpenWebuiFixture.ORIGIN);
        evaluate("localStorage.clear(); sessionStorage.clear(); true");
        privateValues(); clearSession(); portal();
    }
    Set<String> privateValues() throws Exception {
        var cookies = call("Network.getAllCookies", new JsonObject()).getAsJsonArray("cookies");
        for (var cookie : cookies) privateValues.add(cookie.getAsJsonObject().get("value").getAsString());
        return Set.copyOf(privateValues);
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
    JsonArray sessionCookies() throws Exception {
        return call("Network.getCookies", JsonParser.parseString("{\"urls\":[\"https://auth.garden.internal\"]}").getAsJsonObject()).getAsJsonArray("cookies");
    }
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
        if (process == null || !process.isAlive()) return;
        process.descendants().forEach(ProcessHandle::destroy);
        process.destroy();
        try {
            if (process.waitFor(5, TimeUnit.SECONDS)) return;
            process.destroyForcibly();
            process.waitFor(5, TimeUnit.SECONDS);
        } catch (InterruptedException error) { process.destroyForcibly(); Thread.currentThread().interrupt(); }
    }
}
