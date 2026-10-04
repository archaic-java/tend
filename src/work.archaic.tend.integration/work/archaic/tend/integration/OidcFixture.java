package work.archaic.tend.integration;

import com.google.gson.*;
import java.io.IOException;
import java.net.*;
import java.net.http.*;
import java.nio.charset.StandardCharsets;
import java.nio.file.*;
import java.security.*;
import java.security.cert.CertificateFactory;
import java.time.Duration;
import java.util.*;
import java.util.concurrent.TimeUnit;
import javax.net.ssl.*;

/** Narrow confidential-client protocol probe; codes, cookies and token bodies stay private. */
final class OidcFixture implements AutoCloseable {
    static final String ISSUER = "https://auth.garden.internal";
    static final String CLIENT = "tend-smoke";
    static final String CALLBACK = "https://app.garden.internal/oauth/callback";
    record Flow(String state, String nonce, String verifier) {}
    // Deliberately not a record: diagnostic rendering must not include protocol credentials.
    static final class Reply {
        final int status;
        final JsonObject body;
        final URI location;
        Reply(int status, JsonObject body, URI location) { this.status = status; this.body = body; this.location = location; }
        String error() { return body.has("error") ? body.get("error").getAsString() : ""; }
    }
    private final HttpClient http;
    private final CookieManager cookies = new CookieManager(null, CookiePolicy.ACCEPT_ORIGINAL_SERVER);
    private final String secret;
    OidcFixture(Path directory) throws Exception {
        var trust = KeyStore.getInstance(KeyStore.getDefaultType()); trust.load(null, null);
        try (var input = Files.newInputStream(directory.resolve("root.crt"))) {
            trust.setCertificateEntry("caddy", CertificateFactory.getInstance("X.509").generateCertificate(input));
        }
        var managers = TrustManagerFactory.getInstance(TrustManagerFactory.getDefaultAlgorithm()); managers.init(trust);
        var context = SSLContext.getInstance("TLS"); context.init(null, managers.getTrustManagers(), null);
        http = HttpClient.newBuilder().sslContext(context).cookieHandler(cookies).followRedirects(HttpClient.Redirect.NEVER)
                .connectTimeout(Duration.ofSeconds(5)).build();
        Path file = Files.createTempFile(directory, "oidc-client-", ".private",
                java.nio.file.attribute.PosixFilePermissions.asFileAttribute(java.nio.file.attribute.PosixFilePermissions.fromString("rw-------")));
        Process pull = new ProcessBuilder("sudo", "-n", "incus", "exec", "tend-ci-auth-backend", "--", "cat", "/etc/tend-oidc-client/value")
                .redirectErrorStream(true).redirectOutput(file.toFile()).start();
        pull.getOutputStream().close();
        try {
            if (!pull.waitFor(10, TimeUnit.SECONDS) || pull.exitValue() != 0) throw new IOException("Cannot read mounted OIDC client credential");
            secret = Files.readString(file).strip();
            if (!secret.matches("[A-Za-z0-9_-]{72}")) throw new IOException("Unexpected OIDC client credential format");
        } catch (Exception e) { http.shutdownNow(); throw e; }
        finally { if (pull.isAlive()) pull.destroyForcibly(); Files.deleteIfExists(file); }
    }
    void session(JsonArray source) {
        cookies.getCookieStore().removeAll();
        for (var entry : source) {
            var value = entry.getAsJsonObject();
            var cookie = new HttpCookie(value.get("name").getAsString(), value.get("value").getAsString());
            cookie.setVersion(0); cookie.setDomain(value.get("domain").getAsString());
            cookie.setPath(value.get("path").getAsString()); cookie.setSecure(true);
            cookies.getCookieStore().add(URI.create(ISSUER), cookie);
        }
    }
    static Flow flow() { return new Flow(random(), random(), random()); }
    private static String random() { byte[] value = new byte[32]; new SecureRandom().nextBytes(value); return Base64.getUrlEncoder().withoutPadding().encodeToString(value); }
    Reply discovery() throws Exception { return get(URI.create(ISSUER + "/.well-known/openid-configuration")); }
    Reply keys() throws Exception { return get(URI.create(ISSUER + "/jwks.json")); }
    Reply authorize(Flow flow, String redirect) throws Exception {
        String challenge = Base64.getUrlEncoder().withoutPadding().encodeToString(MessageDigest.getInstance("SHA-256").digest(flow.verifier().getBytes(StandardCharsets.US_ASCII)));
        return get(URI.create(ISSUER + "/api/oidc/authorization?" + form(Map.of("client_id", CLIENT, "redirect_uri", redirect,
                "response_type", "code", "response_mode", "query", "scope", "openid profile email groups", "state", flow.state(), "nonce", flow.nonce(),
                "code_challenge", challenge, "code_challenge_method", "S256"))));
    }
    Reply consent(URI portal, boolean accept) throws Exception {
        String flow = query(portal).get("flow_id");
        if (flow == null) throw new IOException("OIDC response did not request explicit consent");
        var information = get(URI.create(ISSUER + "/api/oidc/consent?flow_id=" + encode(flow)));
        if (information.status != 200 || !information.body.has("data")) throw new IOException("Cannot read OIDC consent request");
        var data = information.body.getAsJsonObject("data");
        if (!data.get("client_id").getAsString().equals(CLIENT) || data.get("require_login").getAsBoolean()) throw new IOException("Unexpected OIDC consent client or authentication state");
        var body = new JsonObject(); body.addProperty("flow_id", flow); body.addProperty("client_id", CLIENT);
        body.addProperty("consent", accept); body.addProperty("pre_configure", false); body.add("claims", data.get("claims"));
        var reply = send(HttpRequest.newBuilder(URI.create(ISSUER + "/api/oidc/consent")).header("Content-Type", "application/json")
                .header("Origin", ISSUER).POST(HttpRequest.BodyPublishers.ofString(body.toString())));
        if (reply.status != 200 || !reply.body.has("data")) throw new IOException("OIDC consent response failed");
        return get(URI.create(reply.body.getAsJsonObject("data").get("redirect_uri").getAsString()));
    }
    Reply exchange(String code, String verifier, boolean validSecret) throws Exception {
        String basic = Base64.getEncoder().encodeToString((encode(CLIENT) + ":" + encode(validSecret ? secret : "incorrect-secret")).getBytes(StandardCharsets.US_ASCII));
        return send(HttpRequest.newBuilder(URI.create(ISSUER + "/api/oidc/token")).header("Authorization", "Basic " + basic)
                .header("Content-Type", "application/x-www-form-urlencoded").POST(HttpRequest.BodyPublishers.ofString(form(Map.of(
                        "grant_type", "authorization_code", "code", code, "redirect_uri", CALLBACK, "code_verifier", verifier)))));
    }
    static String code(Reply reply, Flow flow) throws IOException {
        var parameters = query(reply.location);
        if (reply.status != 302 || !reply.location.toString().startsWith(CALLBACK + "?") || !flow.state().equals(parameters.get("state"))
                || parameters.containsKey("error") || parameters.getOrDefault("code", "").isBlank())
            throw new IOException("OIDC callback URI, state or authorization code is invalid");
        return parameters.get("code");
    }
    Reply userinfo(String accessToken) throws Exception {
        return send(HttpRequest.newBuilder(URI.create(ISSUER + "/api/oidc/userinfo")).header("Authorization", "Bearer " + accessToken));
    }
    private Reply get(URI uri) throws Exception { return send(HttpRequest.newBuilder(uri)); }
    private Reply send(HttpRequest.Builder builder) throws Exception {
        try {
            URI target = builder.build().uri();
            if (!target.getScheme().equals("https") || !target.getAuthority().equals("auth.garden.internal")) throw new IOException("OIDC probe refuses a foreign endpoint");
            var response = http.send(builder.timeout(Duration.ofSeconds(10)).build(), HttpResponse.BodyHandlers.ofString());
            JsonObject body = response.body().stripLeading().startsWith("{") ? JsonParser.parseString(response.body()).getAsJsonObject() : new JsonObject();
            URI location = response.headers().firstValue("Location").map(target::resolve).orElse(null);
            return new Reply(response.statusCode(), body, location);
        } catch (InterruptedException e) { throw e; }
        catch (Exception e) { throw new IOException("Private OIDC HTTP probe failed; response details withheld"); }
    }
    static Map<String, String> query(URI uri) throws IOException {
        if (uri == null) throw new IOException("OIDC response did not redirect");
        Map<String, String> result = new HashMap<>();
        if (uri.getRawQuery() != null) for (String part : uri.getRawQuery().split("&")) {
            String[] pair = part.split("=", 2);
            if (result.putIfAbsent(URLDecoder.decode(pair[0], StandardCharsets.UTF_8), pair.length == 2 ? URLDecoder.decode(pair[1], StandardCharsets.UTF_8) : "") != null)
                throw new IOException("Duplicate OIDC response parameter");
        }
        return result;
    }
    private static String form(Map<String, String> values) { return values.entrySet().stream().map(e -> encode(e.getKey()) + "=" + encode(e.getValue())).collect(java.util.stream.Collectors.joining("&")); }
    private static String encode(String value) { return URLEncoder.encode(value, StandardCharsets.UTF_8); }
    public void close() { http.shutdownNow(); cookies.getCookieStore().removeAll(); }
}
