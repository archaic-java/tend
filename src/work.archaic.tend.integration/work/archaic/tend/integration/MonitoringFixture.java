package work.archaic.tend.integration;

import com.google.gson.*;
import java.io.IOException;
import java.net.URLEncoder;
import java.nio.charset.StandardCharsets;
import java.nio.file.*;
import java.time.Duration;
import java.util.*;

/** Actual Prometheus TLS scraping and separately mounted Grafana provisioning. */
final class MonitoringFixture {
    static final String PROMETHEUS = "tend-ci-prometheus", GRAFANA = "tend-ci-monitor-grafana";
    private final IncusCommands incus;
    private final RealGarden garden;
    private final NativeIngressFixture ingress;
    private final PrivateCommands privateCommands;
    private String adminPassword;
    MonitoringFixture(IncusCommands incus, RealGarden garden, NativeIngressFixture ingress) {
        this.incus = incus; this.garden = garden; this.ingress = ingress; privateCommands = new PrivateCommands(garden.directory);
    }
    void sources() throws IOException {
        garden.source("native-authelia.json", ingress.configuration().replace("\"server\":", "\"telemetry\":{\"metrics\":{\"enabled\":true,\"address\":\"tcp://0.0.0.0:9959/\"}},\"server\":"));
        garden.source("prometheus.json", configuration());
        garden.source("monitor-grafana.ini", """
                [server]
                http_addr = 0.0.0.0
                [auth]
                disable_login_form = true
                [auth.basic]
                enabled = true
                [users]
                allow_sign_up = false
                [metrics]
                enabled = true
                [log]
                mode = file
                level = warn
                """);
        garden.source("datasources.json", """
                {"apiVersion":1,"datasources":[{"name":"Prometheus","uid":"prometheus","type":"prometheus","access":"proxy","url":"http://10.79.0.24:9090","isDefault":true,"editable":false}]}
                """);
        garden.source("dashboards.json", """
                {"apiVersion":1,"providers":[{"name":"homelab","orgId":1,"folder":"Homelab","type":"file","disableDeletion":false,"allowUiUpdates":false,"options":{"path":"/etc/tend-dashboards"}}]}
                """);
        garden.source("incus.json", Files.readString(Path.of("examples/monitoring/incus.json")));
    }
    String configuration() {
        return """
                {"global":{"scrape_interval":"2s","scrape_timeout":"1s"},"scrape_configs":[
                  {"job_name":"prometheus","static_configs":[{"targets":["10.79.0.24:9090"]}]},
                  {"job_name":"caddy","static_configs":[{"targets":["10.79.0.20:9180"]}]},
                  {"job_name":"authelia","static_configs":[{"targets":["10.79.0.21:9959"]}]},
                  {"job_name":"grafana","static_configs":[{"targets":["10.79.0.25:3000"]}]},
                  {"job_name":"incus","scheme":"https","metrics_path":"/1.0/metrics","static_configs":[{"targets":["10.79.0.1:8443"]}],
                   "tls_config":{"ca_file":"/etc/incus-tls/server.crt","cert_file":"/etc/incus-tls/client.crt","key_file":"/etc/incus-tls/client.key","server_name":"10.79.0.1"}},
                  {"job_name":"llama","metrics_path":"/metrics","params":{"autoload":["false"]},"static_configs":[{"targets":["10.79.0.30:8080"],"labels":{"model":"tiny-ci"}}],
                   "relabel_configs":[{"source_labels":["model"],"target_label":"__param_model"}]}
                ]}
                """;
    }
    String xml() throws IOException {
        String prometheus = fingerprint("prometheus"), grafana = fingerprint("grafana");
        String configs = """
                <configuration name="monitor-prom"><file path="/prometheus.yml" source="prometheus.json"/></configuration>
                <configuration name="monitor-grafana"><file path="/grafana.ini" source="monitor-grafana.ini"/></configuration>
                <configuration name="monitor-datasource"><file path="/datasources.yml" source="datasources.json"/></configuration>
                <configuration name="monitor-provider"><file path="/provider.yml" source="dashboards.json"/></configuration>
                <configuration name="monitor-dashboard"><file path="/incus.json" source="incus.json"/></configuration>
                """;
        String volumes = """
                <volume pool="tend-ci-pool" name="tend-ci-metrics-tls" private-owner="tend-ci-controller" private-kind="metrics" private-uid="65534"/>
                <volume pool="tend-ci-pool" name="tend-ci-prom-data"><config><entry key="security.shifted" value="true"/><entry key="initial.uid" value="65534"/><entry key="initial.gid" value="65534"/><entry key="initial.mode" value="0700"/></config></volume>
                <volume pool="tend-ci-pool" name="tend-ci-monitor-grafana-data"><config><entry key="security.shifted" value="true"/><entry key="initial.uid" value="472"/><entry key="initial.gid" value="0"/><entry key="initial.mode" value="0700"/></config></volume>
                """;
        String instances = instance(PROMETHEUS, prometheus, "10.79.0.24", """
                <config><entry key="oci.uid" value="65534"/><entry key="oci.gid" value="65534"/></config>
                """, disk("data", "tend-ci-prom-data", "/prometheus", false) + disk("metrics", "tend-ci-metrics-tls", "/etc/incus-tls", true)
                + mount("settings", "monitor-prom", "/etc/prometheus", 65534, 65534))
                + instance(GRAFANA, grafana, "10.79.0.25", """
                <config><entry key="oci.uid" value="472"/><entry key="oci.gid" value="0"/>
                  <entry key="environment.GF_PATHS_CONFIG" value="/etc/tend-grafana/grafana.ini"/>
                  <entry key="environment.GF_SECURITY_ADMIN_PASSWORD__FILE" value="/etc/tend-monitor-admin/value"/>
                  <entry key="environment.GF_SECURITY_SECRET_KEY__FILE" value="/etc/tend-monitor-encryption/value"/>
                </config>
                """, disk("data", "tend-ci-monitor-grafana-data", "/var/lib/grafana", false)
                + mount("settings", "monitor-grafana", "/etc/tend-grafana", 472, 0)
                + mount("datasource", "monitor-datasource", "/etc/grafana/provisioning/datasources", 472, 0)
                + mount("provider", "monitor-provider", "/etc/grafana/provisioning/dashboards", 472, 0)
                + mount("dashboard", "monitor-dashboard", "/etc/tend-dashboards", 472, 0) + """
                  <mount name="admin" secret="monitor-admin" pool="tend-ci-pool" path="/etc/tend-monitor-admin" uid="472" gid="0"/>
                  <mount name="encryption" secret="monitor-encryption" pool="tend-ci-pool" path="/etc/tend-monitor-encryption" uid="472" gid="0"/>
                """);
        return ingress.xml("admins").replace("<configuration name=\"native-base\">", "<secret name=\"monitor-admin\"/><secret name=\"monitor-encryption\"/><configuration name=\"native-base\">")
                .replace("<volume pool=\"tend-ci-pool\" name=\"tend-ci-native-users\"", configs + "<volume pool=\"tend-ci-pool\" name=\"tend-ci-native-users\"")
                .replace("<instance name=\"" + NativeIngressFixture.GATEWAY + "\"", volumes + "<instance name=\"" + NativeIngressFixture.GATEWAY + "\"")
                .replace("<ingress-gateway ", instances + "<ingress-gateway ");
    }
    private static String fingerprint(String name) throws IOException {
        String value = Files.readString(Path.of("out/incus-smoke/" + name + "-fingerprint.txt")).strip();
        if (!value.matches("[a-f0-9]{64}")) throw new IOException("Expected cached monitoring OCI image");
        return value;
    }
    private static String instance(String name, String fingerprint, String address, String config, String devices) {
        return """
                <instance name="%s" fingerprint="%s">%s
                  <device name="root" type="disk"><config><entry key="pool" value="tend-ci-pool"/><entry key="path" value="/"/></config></device>
                  <device name="eth0" type="nic"><config><entry key="network" value="tend-ci-ctl"/><entry key="name" value="eth0"/><entry key="ipv4.address" value="%s"/></config></device>
                  %s
                </instance>
                """.formatted(name, fingerprint, config, address, devices);
    }
    private static String disk(String name, String source, String path, boolean readonly) {
        return """
                <device name="%s" type="disk"><config><entry key="pool" value="tend-ci-pool"/><entry key="source" value="%s"/><entry key="path" value="%s"/><entry key="readonly" value="%s"/></config></device>
                """.formatted(name, source, path, readonly);
    }
    private static String mount(String name, String source, String path, int uid, int gid) {
        return """
                <mount name="%s" configuration="%s" pool="tend-ci-pool" path="%s" uid="%s" gid="%s" mode="0644"/>
                """.formatted(name, source, path, uid, gid);
    }
    JsonObject query(String query) throws IOException, InterruptedException {
        return get("http://10.79.0.24:9090/api/v1/query?query=" + URLEncoder.encode(query, StandardCharsets.UTF_8));
    }
    JsonObject targets() throws IOException, InterruptedException { return get("http://10.79.0.24:9090/api/v1/targets?state=active"); }
    private JsonObject get(String url) throws IOException, InterruptedException {
        var result = incus.run(Duration.ofSeconds(12), "exec", NativeIngressFixture.CLIENT, "--", "curl", "--fail", "--silent", "--show-error", "--noproxy", "*", "--max-time", "8", url);
        if (result.status() != 0) throw new IOException("Monitoring HTTP observation failed");
        return JsonParser.parseString(result.output()).getAsJsonObject();
    }
    boolean target(String job, String health) throws IOException, InterruptedException {
        long deadline = System.nanoTime() + Duration.ofSeconds(90).toNanos();
        do {
            try {
                for (var entry : targets().getAsJsonObject("data").getAsJsonArray("activeTargets")) {
                    var value = entry.getAsJsonObject();
                    if (value.getAsJsonObject("labels").get("job").getAsString().equals(job) && value.get("health").getAsString().equals(health)) return true;
                }
            } catch (IOException | JsonParseException | IllegalStateException unavailable) { /* Bounded startup polling; no response body in failure. */ }
            Thread.sleep(300);
        } while (System.nanoTime() < deadline);
        return false;
    }
    void admin() throws IOException, InterruptedException {
        adminPassword = privateCommands.incus("exec", GRAFANA, "--", "cat", "/etc/tend-monitor-admin/value").strip();
        Path config = garden.directory.resolve("grafana-api.args"); privateCommands.file(config, "user = \"admin:" + adminPassword + "\"\n");
        incus.require("file", "push", config.toString(), NativeIngressFixture.CLIENT + "/root/grafana-api.args", "--mode=0600");
    }
    JsonObject grafana(String path) throws IOException, InterruptedException {
        var result = incus.run(Duration.ofSeconds(12), "exec", NativeIngressFixture.CLIENT, "--", "curl", "--fail", "--silent", "--show-error", "--noproxy", "*", "--max-time", "8", "--config", "/root/grafana-api.args", "http://10.79.0.25:3000" + path);
        if (result.status() != 0) throw new IOException("Grafana provisioning observation failed");
        return JsonParser.parseString(result.output()).getAsJsonObject();
    }
    void credential(String variant) throws IOException, InterruptedException {
        String fixture = Files.readString(Path.of("out/incus-smoke/metrics-directory.txt")).strip();
        incus.require("stop", PROMETHEUS);
        var result = incus.command(Duration.ofSeconds(120), List.of("sudo", "-n", "python3", "scripts/bootstrap/private-volume", "replace", "metrics", "local", "default", "tend-ci-pool", "tend-ci-metrics-tls", "tend-ci-controller", "65534", fixture + "/" + variant, "tend-ci-private-operator"));
        if (result.status() != 0) throw new IOException("Metrics private replacement rejected; consumer remains stopped");
        incus.require("start", PROMETHEUS);
    }
    int metricsAdminStatus() throws IOException, InterruptedException {
        for (String name : List.of("client.crt", "client.key", "server.crt")) {
            Path file = garden.directory.resolve("probe-metrics-" + name);
            privateCommands.file(file, privateCommands.incus("exec", PROMETHEUS, "--", "cat", "/etc/incus-tls/" + name));
            incus.require("file", "push", file.toString(), NativeIngressFixture.CLIENT + "/root/metrics-" + name, "--mode=0600");
        }
        var result = incus.run(Duration.ofSeconds(12), "exec", NativeIngressFixture.CLIENT, "--", "curl", "--silent", "--show-error", "--noproxy", "*", "--max-time", "8",
                "--cert", "/root/metrics-client.crt", "--key", "/root/metrics-client.key", "--cacert", "/root/metrics-server.crt", "--output", "/dev/null", "--write-out", "%{http_code}", "https://10.79.0.1:8443/1.0/instances?project=default");
        if (result.status() != 0) throw new IOException("Metrics privilege TLS observation failed");
        return Integer.parseInt(result.output().strip());
    }
    String adminIdentity() throws IOException, InterruptedException {
        return privateCommands.incus("exec", GRAFANA, "--", "cat", "/etc/tend-monitor-admin/value").strip();
    }
    boolean secretsExcluded() throws IOException, InterruptedException {
        String key = privateCommands.incus("exec", PROMETHEUS, "--", "cat", "/etc/incus-tls/client.key").strip();
        String encryption = privateCommands.incus("exec", GRAFANA, "--", "cat", "/etc/tend-monitor-encryption/value").strip();
        var values = List.of(adminPassword, key, encryption);
        try (var paths = Files.walk(Path.of("out/incus-smoke"))) {
            for (Path file : paths.filter(Files::isRegularFile).toList()) {
                String text = new String(Files.readAllBytes(file), StandardCharsets.UTF_8);
                for (String value : values) if (text.contains(value)) return false;
            }
        }
        return true;
    }
}
