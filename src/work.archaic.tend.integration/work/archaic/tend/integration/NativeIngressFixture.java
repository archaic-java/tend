package work.archaic.tend.integration;

import java.io.IOException;
import java.nio.file.*;
import java.time.Duration;
import java.util.*;

/** Upstream OCI services and an independent HTTP/WebSocket backend on ordinary private bridges. */
final class NativeIngressFixture {
    static final String GATEWAY = "tend-ci-native-gateway", AUTH = "tend-ci-native-auth";
    private static final String BACKEND = "tend-ci-native-backend", CLIENT = "tend-ci-native-client";
    private final IncusCommands incus;
    private final RealGarden garden;
    NativeIngressFixture(IncusCommands incus, RealGarden garden) { this.incus = incus; this.garden = garden; }

    String configuration() {
        return """
                {"server":{"address":"tcp://0.0.0.0:9091/"},"log":{"level":"warn"},
                 "authentication_backend":{"file":{"path":"/etc/private-users/users.yml","watch":false}},
                 "session":{"cookies":[{"domain":"native.localhost","authelia_url":"https://auth.native.localhost","default_redirection_url":"https://pi.native.localhost"}]},
                 "storage":{"local":{"path":"/var/lib/authelia/db.sqlite3"}},
                 "notifier":{"filesystem":{"filename":"/var/lib/authelia/notifications.txt"}}}
                """;
    }
    String xml(String group) throws IOException {
        String caddy = fingerprint("caddy"), authelia = fingerprint("authelia");
        return """
                <incus project="default">
                  <secret name="native-session"/><secret name="native-storage"/><secret name="native-reset"/>
                  <configuration name="native-base"><file path="/configuration.json" source="native-authelia.json"/></configuration>
                  <volume pool="tend-ci-pool" name="tend-ci-native-users" private-owner="tend-ci-controller" private-kind="users" private-uid="1000"/>
                  <volume pool="tend-ci-pool" name="tend-ci-native-caddy-data"><config><entry key="security.shifted" value="true"/><entry key="initial.mode" value="0700"/></config></volume>
                  <volume pool="tend-ci-pool" name="tend-ci-native-caddy-config"><config><entry key="security.shifted" value="true"/><entry key="initial.mode" value="0700"/></config></volume>
                  <volume pool="tend-ci-pool" name="tend-ci-native-auth-data"><config><entry key="security.shifted" value="true"/><entry key="initial.mode" value="0700"/><entry key="initial.uid" value="1000"/><entry key="initial.gid" value="1000"/></config></volume>
                """
                + instance(GATEWAY, caddy, "10.79.0.20", "", disk("data", "tend-ci-native-caddy-data", "/data", false)
                    + disk("runtime", "tend-ci-native-caddy-config", "/config", false) + """
                      <device name="lan" type="nic"><config><entry key="network" value="tend-ci-nlan"/><entry key="name" value="eth1"/><entry key="ipv4.address" value="10.80.0.20"/></config></device>
                    """)
                + instance(AUTH, authelia, "10.79.0.21", """
                    <config>
                      <entry key="oci.uid" value="1000"/><entry key="oci.gid" value="1000"/>
                      <entry key="oci.entrypoint" value="/app/authelia --config /etc/tend-base/configuration.json --config /etc/tend-authorization/access-control.json"/>
                      <entry key="environment.X_AUTHELIA_CONFIG" value="/etc/tend-base/configuration.json,/etc/tend-authorization/access-control.json"/>
                      <entry key="environment.AUTHELIA_SESSION_SECRET_FILE" value="/etc/tend-native-session/value"/>
                      <entry key="environment.AUTHELIA_STORAGE_ENCRYPTION_KEY_FILE" value="/etc/tend-native-storage/value"/>
                      <entry key="environment.AUTHELIA_IDENTITY_VALIDATION_RESET_PASSWORD_JWT_SECRET_FILE" value="/etc/tend-native-reset/value"/>
                    </config>
                    """, disk("data", "tend-ci-native-auth-data", "/var/lib/authelia", false)
                    + disk("users", "tend-ci-native-users", "/etc/private-users", true) + """
                    <mount name="base" configuration="native-base" pool="tend-ci-pool" path="/etc/tend-base" uid="1000" gid="1000" mode="0644"/>
                    <mount name="session" secret="native-session" pool="tend-ci-pool" path="/etc/tend-native-session" uid="1000" gid="1000"/>
                    <mount name="storage" secret="native-storage" pool="tend-ci-pool" path="/etc/tend-native-storage" uid="1000" gid="1000"/>
                    <mount name="reset" secret="native-reset" pool="tend-ci-pool" path="/etc/tend-native-reset" uid="1000" gid="1000"/>
                    """)
                + instance(BACKEND, garden.fingerprint, "10.79.0.22", "", "")
                + """
                  <ingress-gateway instance="tend-ci-native-gateway" pool="tend-ci-pool" path="/etc/caddy" authorization-instance="tend-ci-native-auth" authorization-device="eth0" authorization-path="/etc/tend-authorization" authorization-uid="1000" authorization-gid="1000"><metrics device="eth0"/></ingress-gateway>
                  <ingress name="auth" host="auth.native.localhost" instance="tend-ci-native-auth" device="eth0" port="9091"><public/></ingress>
                  <ingress name="oidc" host="oidc.native.localhost" instance="tend-ci-native-backend" device="eth0" port="8080"><public/></ingress>
                  <ingress name="pi" host="pi.native.localhost" instance="tend-ci-native-backend" device="eth0" port="8080"><authorization policy="one_factor"><group name="%s"/></authorization></ingress>
                </incus>
                """.formatted(group);
    }
    private static String fingerprint(String name) throws IOException {
        String value = Files.readString(Path.of("out/incus-smoke/native-" + name + "-fingerprint.txt")).strip();
        if (!value.matches("[a-f0-9]{64}")) throw new IOException("Expected cached upstream OCI fingerprint");
        return value;
    }
    private static String instance(String name, String fingerprint, String address, String config, String devices) {
        return """
                <instance name="%s" fingerprint="%s">%s
                  <device name="root" type="disk"><config><entry key="path" value="/"/><entry key="pool" value="tend-ci-pool"/></config></device>
                  <device name="eth0" type="nic"><config><entry key="network" value="tend-ci-ctl"/><entry key="name" value="eth0"/><entry key="ipv4.address" value="%s"/></config></device>
                  %s
                </instance>
                """.formatted(name, fingerprint, config, address, devices);
    }
    private static String disk(String name, String volume, String path, boolean readonly) {
        return """
                <device name="%s" type="disk"><config><entry key="pool" value="tend-ci-pool"/><entry key="source" value="%s"/><entry key="path" value="%s"/><entry key="readonly" value="%s"/></config></device>
                """.formatted(name, volume, path, readonly);
    }
    void prepare() throws IOException, InterruptedException {
        Path backend = garden.directory.resolve("native-backend");
        Files.writeString(backend, """
                #!/bin/sh
                IFS= read -r request
                user=absent groups=absent email=absent name=absent forwarded=absent
                while IFS= read -r header; do
                  header=$(printf '%s' "$header" | tr -d '\\r')
                  [ -n "$header" ] || break
                  case "$header" in
                    Remote-User:*) user=${header#*: };; Remote-Groups:*) groups=${header#*: };;
                    Remote-Email:*) email=${header#*: };; Remote-Name:*) name=${header#*: };;
                    X-Forwarded-User:*|X-Forwarded-Email:*|X-Forwarded-Groups:*) forwarded=present;;
                  esac
                done
                printf 'request\\n' >>/root/requests
                case "$request" in
                  'GET /ws '*)
                    printf 'HTTP/1.1 101 Switching Protocols\\r\\nUpgrade: websocket\\r\\nConnection: Upgrade\\r\\nSec-WebSocket-Accept: s3pPLMBiTxaQ9kYGzzhZRbK+xOo=\\r\\n\\r\\n'
                    printf '\\201\\005hello';;
                  *)
                    body="native-backend user=$user groups=$groups email=$email name=$name forwarded=$forwarded"
                    printf 'HTTP/1.1 200 OK\\r\\nContent-Type: text/plain\\r\\nContent-Length: %s\\r\\nConnection: close\\r\\n\\r\\n%s' "${#body}" "$body";;
                esac
                """);
        incus.require("file", "push", "/bin/busybox", BACKEND + "/root/busybox", "--mode=0755");
        incus.require("file", "push", backend.toString(), BACKEND + "/root/respond", "--mode=0755");
        incus.require("exec", BACKEND, "--", "touch", "/root/requests");
        incus.require("exec", BACKEND, "--", "/root/busybox", "nc", "-ll", "-p", "8080", "-e", "/root/respond");
        incus.require("init", "tend-ci-probe", CLIENT, "--no-profiles", "--storage", "tend-ci-pool", "--network", "tend-ci-ctl");
        incus.require("config", "device", "add", CLIENT, "lan", "nic", "network=tend-ci-nlan", "name=eth1");
        incus.require("start", CLIENT);
        Path ca = garden.directory.resolve("native-root.crt");
        long deadline = System.nanoTime() + Duration.ofSeconds(90).toNanos();
        do {
            var result = incus.run(Duration.ofSeconds(8), "exec", GATEWAY, "--", "cat", "/data/caddy/pki/authorities/local/root.crt");
            if (result.status() == 0) { Files.writeString(ca, result.output()); break; }
            Thread.sleep(300);
        } while (System.nanoTime() < deadline);
        if (!Files.exists(ca)) throw new IOException("Native Caddy did not issue its disposable local CA");
        incus.require("file", "push", ca.toString(), CLIENT + "/root/native-ca.crt", "--mode=0644");
        String fixture = Files.readString(Path.of("out/incus-smoke/authelia-fixture-directory.txt")).strip();
        for (String user : List.of("alice", "bob", "carol")) {
            var input = com.google.gson.JsonParser.parseString(Files.readString(Path.of(fixture, user + ".json"))).getAsJsonObject();
            input.addProperty("targetURL", "https://pi.native.localhost/");
            Path login = garden.directory.resolve("native-" + user + ".json"); Files.writeString(login, input.toString());
            incus.require("file", "push", login.toString(), CLIENT + "/root/" + user + ".json", "--mode=0600");
        }
        ready();
    }
    void ready() throws IOException, InterruptedException {
        long deadline = System.nanoTime() + Duration.ofSeconds(90).toNanos();
        do {
            var result = request("auth.native.localhost", "/api/health", null, false);
            if (result.status() == 0 && result.output().contains("status=200")) return;
            Thread.sleep(300);
        } while (System.nanoTime() < deadline);
        throw new IOException("Native Authelia/Caddy did not become healthy within 90 seconds");
    }
    IncusCommands.Result login(String user) throws IOException, InterruptedException {
        var args = curl("auth.native.localhost", true);
        args.addAll(List.of("--output", "/dev/null", "--write-out", "%{http_code}", "--cookie-jar", "/root/" + user + ".cookie",
                "-H", "Content-Type: application/json", "--data-binary", "@/root/" + user + ".json", "https://auth.native.localhost/api/firstfactor"));
        return incus.run(Duration.ofSeconds(12), args.toArray(String[]::new));
    }
    IncusCommands.Result request(String host, String path, String user, boolean websocket) throws IOException, InterruptedException {
        var args = curl(host, true);
        if (user != null) args.addAll(List.of("--cookie", "/root/" + user + ".cookie"));
        for (String header : List.of("Remote-User", "Remote-Groups", "Remote-Email", "Remote-Name", "X-Forwarded-User", "X-Forwarded-Email", "X-Forwarded-Groups")) args.addAll(List.of("-H", header + ": forged"));
        if (websocket) args.addAll(List.of("--http1.1", "-H", "Connection: Upgrade", "-H", "Upgrade: websocket", "-H", "Sec-WebSocket-Version: 13", "-H", "Sec-WebSocket-Key: dGhlIHNhbXBsZSBub25jZQ==", "--dump-header", "/dev/stdout", "--output", "/root/ws-body"));
        args.addAll(List.of("--write-out", "\\nstatus=%{http_code}\\n", "https://" + host + path));
        return incus.run(Duration.ofSeconds(12), args.toArray(String[]::new));
    }
    String websocketFrame() throws IOException, InterruptedException {
        var result = incus.run(Duration.ofSeconds(10), "exec", CLIENT, "--", "od", "-An", "-tx1", "/root/ws-body");
        if (result.status() != 0) throw new IOException("WebSocket frame observation failed");
        return result.output().replaceAll("\\s+", "");
    }
    IncusCommands.Result untrusted() throws IOException, InterruptedException {
        var args = curl("auth.native.localhost", false); args.add("https://auth.native.localhost/api/health");
        return incus.run(Duration.ofSeconds(12), args.toArray(String[]::new));
    }
    IncusCommands.Result metrics(boolean privateSide) throws IOException, InterruptedException {
        return incus.run(Duration.ofSeconds(12), "exec", CLIENT, "--", "curl", "--silent", "--show-error", "--noproxy", "*", "--connect-timeout", "2", "--max-time", "8", "http://" + (privateSide ? "10.79.0.20" : "10.80.0.20") + ":9180/metrics");
    }
    String requests() throws IOException, InterruptedException {
        var result = incus.run(Duration.ofSeconds(10), "exec", BACKEND, "--", "wc", "-l", "/root/requests");
        if (result.status() != 0) throw new IOException("Backend request observation failed");
        return result.output();
    }
    String ca() throws IOException, InterruptedException {
        var result = incus.run(Duration.ofSeconds(10), "exec", GATEWAY, "--", "cat", "/data/caddy/pki/authorities/local/root.crt");
        if (result.status() != 0) throw new IOException("Public CA observation failed");
        return result.output();
    }
    private static ArrayList<String> curl(String host, boolean trust) {
        var args = new ArrayList<>(List.of("exec", CLIENT, "--", "curl", "--silent", "--show-error", "--noproxy", "*", "--connect-timeout", "2", "--max-time", "8", "--resolve", host + ":443:10.79.0.20"));
        if (trust) args.addAll(List.of("--cacert", "/root/native-ca.crt"));
        return args;
    }
}
