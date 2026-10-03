package work.archaic.tend.integration;

import java.io.IOException;
import java.nio.file.*;
import java.time.Duration;
import java.util.*;

/** Private credentials, bounded probes and real application fixtures; assertions remain in the case. */
final class AuthorizationFixture {
    static final String GATEWAY = "tend-ci-auth-gateway";
    static final String AUTH = "tend-ci-auth-service";
    private static final String CLIENT = "tend-ci-auth-client";
    private static final String BACKEND = "tend-ci-auth-backend";
    private final IncusCommands incus;
    private final RealGarden garden;
    AuthorizationFixture(IncusCommands incus, RealGarden garden) { this.incus = incus; this.garden = garden; }
    void prepare() throws IOException, InterruptedException {
        incus.require("exec", BACKEND, "--", "mkdir", "-p", "/root/cgi-bin");
        incus.require("exec", BACKEND, "--", "touch", "/root/requests");
        Path script = garden.directory.resolve("probe");
        Files.writeString(script, """
                #!/bin/sh
                printf '%s\n' "${HTTP_REMOTE_USER:-anonymous}" >>/root/requests
                printf 'Content-Type: text/plain\r\n\r\nprotected-backend\n'
                printf 'user=%s groups=%s email=%s name=%s\n' "${HTTP_REMOTE_USER:-absent}" "${HTTP_REMOTE_GROUPS:-absent}" "${HTTP_REMOTE_EMAIL:-absent}" "${HTTP_REMOTE_NAME:-absent}"
                """);
        incus.require("file", "push", script.toString(), BACKEND + "/root/cgi-bin/probe", "--mode=0755");
        incus.require("file", "push", "/bin/busybox", BACKEND + "/root/busybox", "--mode=0755");
        incus.require("exec", BACKEND, "--", "/root/busybox", "httpd", "-p", "8080", "-h", "/root");
        incus.require("init", "tend-ci-probe", CLIENT, "--no-profiles", "--storage", "tend-ci-pool");
        incus.require("config", "device", "add", CLIENT, "eth0", "nic", "network=tend-ci-ovn", "name=eth0", "ipv4.address=10.77.1.43");
        incus.require("start", CLIENT);
        long deadline = System.nanoTime() + Duration.ofSeconds(60).toNanos();
        IncusCommands.Result root;
        do {
            root = incus.run(Duration.ofSeconds(8), "exec", GATEWAY, "--", "cat", "/var/lib/caddy/caddy/pki/authorities/local/root.crt");
            if (root.status() == 0) break;
            Thread.sleep(Duration.ofMillis(300));
        } while (System.nanoTime() < deadline);
        if (root.status() != 0) throw new IOException("Authorization gateway did not provision its CA");
        Path ca = garden.directory.resolve("root.crt"); Files.writeString(ca, root.output());
        incus.require("file", "push", ca.toString(), CLIENT + "/root/caddy-ca.crt");
        String fixture = Files.readString(Path.of("out/incus-smoke/authelia-fixture-directory.txt")).strip();
        for (String user : List.of("alice", "bob", "carol"))
            incus.require("file", "push", Path.of(fixture, user + ".json").toString(), CLIENT + "/root/" + user + ".json", "--mode=0600");
        ready();
    }
    void ready() throws IOException, InterruptedException {
        long deadline = System.nanoTime() + Duration.ofSeconds(60).toNanos();
        do {
            var args = curl("auth.garden.internal"); args.addAll(List.of("--output", "/dev/null", "--write-out", "%{http_code}", "https://auth.garden.internal/api/health"));
            var result = incus.run(Duration.ofSeconds(10), args.toArray(String[]::new));
            if (result.status() == 0 && result.output().strip().equals("200")) return;
            Thread.sleep(Duration.ofMillis(300));
        } while (System.nanoTime() < deadline);
        throw new IOException("Authelia did not become healthy through the HTTPS gateway");
    }
    IncusCommands.Result login(String user) throws IOException, InterruptedException {
        var args = curl("auth.garden.internal");
        args.addAll(List.of("--output", "/dev/null", "--write-out", "%{http_code}", "--cookie-jar", "/root/" + user + ".cookies", "-H", "Content-Type: application/json",
                "-H", "Origin: https://auth.garden.internal", "--data-binary", "@/root/" + user + ".json", "https://auth.garden.internal/api/firstfactor"));
        return incus.run(Duration.ofSeconds(15), args.toArray(String[]::new));
    }
    IncusCommands.Result request(String user) throws IOException, InterruptedException {
        var args = curl("app.garden.internal");
        args.addAll(List.of("-H", "Accept: text/html"));
        if (user != null) args.addAll(List.of("--cookie", "/root/" + user + ".cookies"));
        for (String header : List.of("Remote-User", "Remote-Groups", "Remote-Email", "Remote-Name")) args.addAll(List.of("-H", header + ": forged"));
        args.addAll(List.of("--write-out", "\nstatus=%{http_code}\nredirect=%{redirect_url}\n", "https://app.garden.internal/cgi-bin/probe"));
        return incus.run(Duration.ofSeconds(10), args.toArray(String[]::new));
    }
    private static ArrayList<String> curl(String host) {
        return new ArrayList<>(List.of("exec", CLIENT, "--", "curl", "--silent", "--show-error", "--noproxy", "*", "--connect-timeout", "2", "--max-time", "8",
                "--cacert", "/root/caddy-ca.crt", "--resolve", host + ":443:10.77.1.40"));
    }
    String requests() throws IOException, InterruptedException { return output("exec", BACKEND, "--", "cat", "/root/requests"); }
    String policy() throws IOException, InterruptedException { return output("exec", AUTH, "--", "cat", "/etc/tend-authorization/access-control.json"); }
    void drift(String text) throws IOException, InterruptedException {
        String volume = output("config", "device", "get", AUTH, "tend-authorization", "source").strip();
        if (!volume.matches("tend-[a-f0-9]{32}")) throw new IOException("Expected generated authorization backing volume");
        Path file = garden.directory.resolve("drift.json"); Files.writeString(file, text);
        String credentials = Files.readString(Path.of("out/incus-smoke/credentials-directory.txt")).strip();
        var result = incus.command(Duration.ofSeconds(30), List.of("curl", "-fsS", "--cert", credentials + "/client.crt", "--key", credentials + "/client.key",
                "--cacert", credentials + "/server.crt", "-X", "POST", "-H", "Content-Type: application/octet-stream", "-H", "X-Incus-type: file", "-H", "X-Incus-write: overwrite",
                "-H", "X-Incus-uid: 0", "-H", "X-Incus-gid: 0", "-H", "X-Incus-mode: 0644", "--data-binary", "@" + file,
                "https://127.0.0.1:8443/1.0/storage-pools/tend-ci-pool/volumes/custom/" + volume + "/files?path=/access-control.json&project=default"));
        if (result.status() != 0) throw new IOException("Failed to introduce authorization drift");
        incus.require("restart", AUTH);
    }
    private String output(String... args) throws IOException, InterruptedException {
        var result = incus.run(Duration.ofSeconds(90), args);
        if (result.status() != 0) throw new IOException("Authorization observation failed: " + result.output());
        return result.output();
    }
    String baseConfiguration() {
        return """
                {"server":{"address":"tcp://0.0.0.0:9091/"},"log":{"level":"info"},
                 "authentication_backend":{"file":{"path":"/etc/authelia/users.yml"}},
                 "session":{"cookies":[{"domain":"garden.internal","authelia_url":"https://auth.garden.internal","default_redirection_url":"https://app.garden.internal"}]},
                 "storage":{"local":{"path":"/var/lib/authelia/db.sqlite3"}},
                 "notifier":{"filesystem":{"filename":"/var/lib/authelia/notifications.txt"}}}
                """;
    }
    String xml(String group) throws IOException {
        String caddy = Files.readString(Path.of("out/incus-smoke/caddy-fingerprint.txt")).strip();
        String authelia = Files.readString(Path.of("out/incus-smoke/authelia-fingerprint.txt")).strip();
        if (!caddy.matches("[a-f0-9]{64}") || !authelia.matches("[a-f0-9]{64}")) throw new IOException("Expected cached application image fingerprints");
        return """
                <incus project="default">
                  <secret name="auth-session"/><secret name="auth-storage"/><secret name="auth-reset"/>
                  <configuration name="authelia-base"><file path="/configuration.json" source="authelia.json"/></configuration>
                """
                + instance(GATEWAY, caddy, "10.77.1.40", "", "")
                + instance(BACKEND, garden.fingerprint, "10.77.1.41", "", "")
                + instance(AUTH, authelia, "10.77.1.42", """
                  <config>
                    <entry key="environment.X_AUTHELIA_CONFIG" value="/etc/tend-base/configuration.json,/etc/tend-authorization/access-control.json"/>
                    <entry key="environment.AUTHELIA_SESSION_SECRET_FILE" value="/etc/tend-session/value"/>
                    <entry key="environment.AUTHELIA_STORAGE_ENCRYPTION_KEY_FILE" value="/etc/tend-storage/value"/>
                    <entry key="environment.AUTHELIA_IDENTITY_VALIDATION_RESET_PASSWORD_JWT_SECRET_FILE" value="/etc/tend-reset/value"/>
                  </config>
                """, """
                  <mount name="base" configuration="authelia-base" pool="tend-ci-pool" path="/etc/tend-base" mode="0644"/>
                  <mount name="session" secret="auth-session" pool="tend-ci-pool" path="/etc/tend-session"/>
                  <mount name="storage" secret="auth-storage" pool="tend-ci-pool" path="/etc/tend-storage"/>
                  <mount name="reset" secret="auth-reset" pool="tend-ci-pool" path="/etc/tend-reset"/>
                """)
                + """
                  <ingress-gateway instance="tend-ci-auth-gateway" pool="tend-ci-pool" path="/etc/caddy" authorization-instance="tend-ci-auth-service" authorization-device="eth0" authorization-path="/etc/tend-authorization"/>
                  <ingress name="portal" host="auth.garden.internal" instance="tend-ci-auth-service" device="eth0" port="9091"><public/></ingress>
                  <ingress name="app" host="app.garden.internal" instance="tend-ci-auth-backend" device="eth0" port="8080"><authorization policy="one_factor"><group name="%s"/></authorization></ingress>
                </incus>
                """.formatted(group);
    }
    private static String instance(String name, String fingerprint, String address, String config, String mounts) {
        return """
                  <instance name="%s" fingerprint="%s">%s
                    <device name="root" type="disk"><config><entry key="path" value="/"/><entry key="pool" value="tend-ci-pool"/></config></device>
                    <device name="eth0" type="nic"><config><entry key="network" value="tend-ci-ovn"/><entry key="name" value="eth0"/><entry key="ipv4.address" value="%s"/></config></device>
                    %s
                  </instance>
                """.formatted(name, fingerprint, config, address, mounts);
    }
}
