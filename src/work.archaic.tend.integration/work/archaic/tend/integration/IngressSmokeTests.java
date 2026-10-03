package work.archaic.tend.integration;

import java.io.IOException;
import java.nio.file.*;
import java.time.Duration;
import java.util.*;
import work.archaic.service.test.v02.*;

/** Public ingress milestone: generated Caddyfile, verified TLS, Git change and live drift repair. */
public record IngressSmokeTests() implements TestSuite {
    public void cases(Collection<TestCase> cases) { cases.add(new HttpsIngressChangesBackendAndRepairsConfiguration()); }
}
record HttpsIngressChangesBackendAndRepairsConfiguration() implements TestCase {
    private static final String GATEWAY = "tend-ci-gateway";
    private static final String BACKEND = "tend-ci-ingress-backend";
    private static final String CLIENT = "tend-ci-ingress-client";
    private static final String HOST = "app.tend.localhost";
    public void run(TestTrail trail) throws Exception {
        var incus = new IncusCommands("ingress");
        String fingerprint = Files.readString(Path.of("out/incus-smoke/caddy-fingerprint.txt")).strip();
        if (!fingerprint.matches("[a-f0-9]{64}")) throw new IOException("Expected cached Caddy image fingerprint");
        try (var garden = new RealGarden(incus)) {
            String first = garden.commitXml(xml(garden.fingerprint, fingerprint, 8080), "Route public HTTPS to first backend");
            garden.reconcile();
            assert garden.lastSuccess().equals(first) : "Tend must activate the ingress main revision";
            prepareBackend(incus, garden.directory);
            incus.require("init", "tend-ci-probe", CLIENT, "--no-profiles", "--storage", "tend-ci-pool");
            incus.require("config", "device", "add", CLIENT, "eth0", "nic", "network=tend-ci-ovn", "name=eth0", "ipv4.address=10.77.1.33");
            incus.require("start", CLIENT);
            String root = rootCertificate(incus);
            assert root.contains("BEGIN CERTIFICATE") : "Real Caddy must generate a local CA";
            Path ca = garden.directory.resolve("root.crt"); Files.writeString(ca, root);
            incus.require("file", "push", ca.toString(), CLIENT + "/root/caddy-ca.crt");
            var untrusted = untrusted(incus);
            assert untrusted.status() == 60 : "Client must reject Caddy's CA until explicitly trusted: " + untrusted.output();
            var one = reachable(incus, "backend-one");
            assert one.status() == 0 && one.output().contains("backend-one") && one.output().contains("status=200") : "Generated route must reach the first backend over verified HTTPS: " + one.output();
            assert !one.output().contains("forged") : "Public ingress must strip all four supplied identity headers";
            var unknown = probe(incus, true, "unknown.tend.localhost");
            assert unknown.status() == 0 && unknown.output().contains("status=404") && !unknown.output().contains("backend-") : "Unknown HTTP host must not fall through to the backend";
            String started = garden.started(GATEWAY);
            garden.reconcile();
            assert garden.started(GATEWAY).equals(started) : "Unchanged generated ingress must not restart Caddy";
            trail.note("Generated Caddy route serves verified HTTPS and strips forged public identity headers");

            String second = garden.commitXml(xml(garden.fingerprint, fingerprint, 8081), "Route HTTPS to second backend");
            assert !second.equals(first) : "Backend change must be a distinct main commit";
            garden.reconcile();
            assert garden.lastSuccess().equals(second) : "Tend must fetch the new ingress revision";
            var two = reachable(incus, "backend-two");
            assert two.status() == 0 && two.output().contains("backend-two") && !two.output().contains("backend-one") && two.output().contains("status=200") : "New main must activate the changed backend: " + two.output();
            String generated = output(incus, "exec", GATEWAY, "--", "cat", "/etc/caddy/Caddyfile");
            assert generated.contains("10.77.1.31:8081") : "Mounted generated configuration must reflect the new main";
            String volume = output(incus, "config", "device", "get", GATEWAY, "tend-ingress", "source").strip();
            assert volume.matches("tend-[a-f0-9]{32}") : "Gateway must use a Tend-generated backing volume";
            Path drift = garden.directory.resolve("drift.Caddyfile");
            Files.writeString(drift, generated.replace("10.77.1.31:8081", "10.77.1.31:8080"));
            overwrite(incus, volume, drift);
            incus.require("exec", GATEWAY, "--", "/usr/local/bin/caddy", "reload", "--config", "/etc/caddy/Caddyfile", "--adapter", "caddyfile");
            var wrong = reachable(incus, "backend-one");
            assert wrong.status() == 0 && wrong.output().contains("backend-one") : "Fixture must prove configuration drift changes live routing";
            garden.reconcile();
            assert garden.lastSuccess().equals(second) : "Repair must use the same desired ingress revision";
            var repaired = reachable(incus, "backend-two");
            assert repaired.status() == 0 && repaired.output().contains("backend-two") && !repaired.output().contains("backend-one") : "Same revision must restore live routing: " + repaired.output();
            assert output(incus, "exec", GATEWAY, "--", "cat", "/etc/caddy/Caddyfile").equals(generated) : "Repair must restore generated Caddyfile bytes";
            assert rootCertificate(incus).equals(root) : "Git activation and drift repair must preserve Caddy's CA";
            started = garden.started(GATEWAY);
            garden.reconcile();
            assert garden.started(GATEWAY).equals(started) : "Repaired ingress must settle without further restarts";
            System.out.println("Ingress smoke: generated Caddy serves verified HTTPS, strips forged identity, activates new main and repairs live routing drift.");
        }
    }
    private static IncusCommands.Result probe(IncusCommands incus, boolean trusted, String host) throws IOException, InterruptedException {
        var args = new ArrayList<>(List.of("exec", CLIENT, "--", "curl", "--silent", "--show-error", "--noproxy", "*",
                "--connect-timeout", "2", "--max-time", "5", "--resolve", HOST + ":443:10.77.1.30", "--write-out", "\nstatus=%{http_code}\n"));
        if (trusted) args.addAll(List.of("--cacert", "/root/caddy-ca.crt"));
        if (host != null) args.addAll(List.of("-H", "Host: " + host));
        for (String header : List.of("Remote-User", "Remote-Groups", "Remote-Email", "Remote-Name")) args.addAll(List.of("-H", header + ": forged"));
        args.add("https://" + HOST + "/cgi-bin/probe");
        return incus.run(Duration.ofSeconds(10), args.toArray(String[]::new));
    }
    private static IncusCommands.Result untrusted(IncusCommands incus) throws IOException, InterruptedException {
        long deadline = System.nanoTime() + Duration.ofSeconds(60).toNanos();
        IncusCommands.Result result;
        do {
            result = probe(incus, false, null);
            if (result.status() == 60 || result.status() == 0) return result;
            Thread.sleep(Duration.ofMillis(300));
        } while (System.nanoTime() < deadline);
        return result;
    }
    private static IncusCommands.Result reachable(IncusCommands incus, String marker) throws IOException, InterruptedException {
        long deadline = System.nanoTime() + Duration.ofSeconds(60).toNanos();
        IncusCommands.Result result;
        do {
            result = probe(incus, true, null);
            if (result.status() == 0 && result.output().contains(marker) && result.output().contains("status=200")) return result;
            Thread.sleep(Duration.ofMillis(300));
        } while (System.nanoTime() < deadline);
        return result;
    }
    private static String rootCertificate(IncusCommands incus) throws IOException, InterruptedException {
        long deadline = System.nanoTime() + Duration.ofSeconds(60).toNanos();
        do {
            var result = incus.run(Duration.ofSeconds(8), "exec", GATEWAY, "--", "cat", "/var/lib/caddy/caddy/pki/authorities/local/root.crt");
            if (result.status() == 0) return result.output();
            Thread.sleep(Duration.ofMillis(300));
        } while (System.nanoTime() < deadline);
        throw new IOException("Caddy did not provision its local CA");
    }
    private static String output(IncusCommands incus, String... args) throws IOException, InterruptedException {
        var result = incus.run(Duration.ofSeconds(90), args);
        if (result.status() != 0) throw new IOException("Ingress observation failed: " + result.output());
        return result.output();
    }
    private static void overwrite(IncusCommands incus, String volume, Path file) throws IOException, InterruptedException {
        String credentials = Files.readString(Path.of("out/incus-smoke/credentials-directory.txt")).strip();
        var result = incus.command(Duration.ofSeconds(30), List.of("curl", "-fsS", "--cert", credentials + "/client.crt", "--key", credentials + "/client.key",
                "--cacert", credentials + "/server.crt", "-X", "POST", "-H", "Content-Type: application/octet-stream", "-H", "X-Incus-type: file",
                "-H", "X-Incus-write: overwrite", "-H", "X-Incus-uid: 0", "-H", "X-Incus-gid: 0", "-H", "X-Incus-mode: 0644", "--data-binary", "@" + file,
                "https://127.0.0.1:8443/1.0/storage-pools/tend-ci-pool/volumes/custom/" + volume + "/files?path=/Caddyfile&project=default"));
        if (result.status() != 0) throw new IOException("Failed to establish generated-volume drift: " + result.output());
    }
    private static void prepareBackend(IncusCommands incus, Path directory) throws IOException, InterruptedException {
        incus.require("file", "push", "/bin/busybox", BACKEND + "/root/busybox", "--mode=0755");
        for (int port : new int[]{8080, 8081}) {
            String path = "/root/http-" + port;
            incus.require("exec", BACKEND, "--", "mkdir", "-p", path + "/cgi-bin");
            Path script = directory.resolve("probe-" + port);
            Files.writeString(script, """
                    #!/bin/sh
                    printf 'Content-Type: text/plain\r\n\r\nbackend-%s\n'
                    printf 'user=%%s groups=%%s email=%%s name=%%s\n' "${HTTP_REMOTE_USER:-absent}" "${HTTP_REMOTE_GROUPS:-absent}" "${HTTP_REMOTE_EMAIL:-absent}" "${HTTP_REMOTE_NAME:-absent}"
                    """.formatted(port == 8080 ? "one" : "two"));
            incus.require("file", "push", script.toString(), BACKEND + path + "/cgi-bin/probe", "--mode=0755");
            incus.require("exec", BACKEND, "--", "/root/busybox", "httpd", "-p", Integer.toString(port), "-h", path);
        }
    }
    private static String xml(String alpine, String caddy, int port) {
        return "<incus project=\"default\">\n"
                + instance(GATEWAY, caddy, "10.77.1.30", "running", "")
                + instance(BACKEND, alpine, "10.77.1.31", "running", "")
                + instance("tend-ci-ingress-authorization", alpine, "10.77.1.32", "stopped", "<config><entry key=\"environment.X_AUTHELIA_CONFIG\" value=\"/config/configuration.yml,/etc/tend-authorization/access-control.json\"/></config>")
                + """
                  <ingress-gateway instance="tend-ci-gateway" pool="tend-ci-pool" path="/etc/caddy" authorization-instance="tend-ci-ingress-authorization" authorization-device="eth0" authorization-path="/etc/tend-authorization"/>
                  <ingress name="demo" host="app.tend.localhost" instance="tend-ci-ingress-backend" device="eth0" port="%d"><public/></ingress>
                </incus>
                """.formatted(port);
    }
    private static String instance(String name, String fingerprint, String address, String state, String config) {
        return """
                  <instance name="%s" fingerprint="%s" state="%s">%s
                    <device name="root" type="disk"><config><entry key="path" value="/"/><entry key="pool" value="tend-ci-pool"/></config></device>
                    <device name="eth0" type="nic"><config><entry key="network" value="tend-ci-ovn"/><entry key="name" value="eth0"/><entry key="ipv4.address" value="%s"/></config></device>
                  </instance>
                """.formatted(name, fingerprint, state, config, address);
    }
}
