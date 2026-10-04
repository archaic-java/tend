package work.archaic.tend.integration;

import java.time.Duration;
import java.util.Collection;
import work.archaic.service.test.v02.*;

/** Native upstream OCI entrypoints, verified TLS, private metrics and HTTP/WebSocket authorization. */
public record NativeIngressSmokeTests() implements TestSuite {
    public void cases(Collection<TestCase> cases) { cases.add(new UpstreamIngressAndPrivateMetrics()); }
}
record UpstreamIngressAndPrivateMetrics() implements TestCase {
    public void run(TestTrail trail) throws Exception {
        var incus = new IncusCommands("native-ingress");
        try (var garden = new RealGarden(incus)) {
            var fixture = new NativeIngressFixture(incus, garden);
            garden.source("native-authelia.json", fixture.configuration());
            String first = garden.commitXml(fixture.xml("ai-users"), "Deploy upstream Caddy and Authelia OCI");
            garden.reconcile(); fixture.prepare();
            assert garden.lastSuccess().equals(first) : "Native OCI services must activate the desired Git revision";
            assert incus.run(Duration.ofSeconds(10), "config", "get", NativeIngressFixture.GATEWAY, "volatile.container.oci").output().strip().equals("true") : "Caddy must execute as native OCI";
            assert incus.run(Duration.ofSeconds(10), "config", "get", NativeIngressFixture.AUTH, "volatile.container.oci").output().strip().equals("true") : "Authelia must execute as native OCI without the OpenRC fixture";
            assert fixture.untrusted().status() == 60 : "Unknown disposable CA must be rejected";
            String before = fixture.requests();
            for (boolean websocket : new boolean[]{false, true}) {
                var anonymous = fixture.request("pi.native.localhost", websocket ? "/ws" : "/", null, websocket);
                assert anonymous.status() == 0 && anonymous.output().contains("status=302") : "Anonymous HTTP and WebSocket upgrades must redirect before the backend";
            }
            assert fixture.requests().equals(before) : "Anonymous denial must not reach the backend";
            for (String user : new String[]{"alice", "bob", "carol"})
                assert fixture.login(user).output().strip().equals("200") : "Actual Authelia OCI must authenticate privately supplied fresh users";
            for (boolean websocket : new boolean[]{false, true}) {
                var denied = fixture.request("pi.native.localhost", websocket ? "/ws" : "/", "bob", websocket);
                assert denied.status() == 0 && denied.output().contains("status=403") : "Outside-group sessions must be denied for HTTP and WebSocket upgrade";
            }
            assert fixture.requests().equals(before) : "Outside-group denials must not reach the backend";
            var permitted = fixture.request("pi.native.localhost", "/", "alice", false);
            assert permitted.status() == 0 && permitted.output().contains("status=200") && permitted.output().contains("user=alice") && permitted.output().contains("forwarded=absent") && !permitted.output().contains("forged") : "AI-user identity must be supplied by Authelia while all forged identity headers are removed";
            var upgraded = fixture.request("pi.native.localhost", "/ws", "alice", true);
            assert upgraded.output().contains("status=101") && upgraded.output().contains("s3pPLMBiTxaQ9kYGzzhZRbK+xOo=") : "Permitted HTTP/1.1 WebSocket handshake must reach the independent backend";
            assert fixture.websocketFrame().equals("810568656c6c6f") : "Caddy must relay the actual hello text frame through the upgraded connection";
            var publicRoute = fixture.request("oidc.native.localhost", "/", null, false);
            assert publicRoute.status() == 0 && publicRoute.output().contains("user=absent") && publicRoute.output().contains("forwarded=absent") && !publicRoute.output().contains("forged") : "Public OIDC application route must strip client identity and leave authentication to the application";
            var metrics = fixture.metrics(true);
            assert metrics.status() == 0 && metrics.output().contains("caddy_http_requests_total") : "Private gateway metrics must expose actual request counters for Prometheus";
            var lanMetrics = fixture.metrics(false);
            assert lanMetrics.status() == 7 && lanMetrics.output().contains("Failed to connect") : "Metrics listener must not bind the second LAN-facing NIC";
            var publicMetrics = fixture.request("oidc.native.localhost", "/metrics", null, false);
            assert publicMetrics.status() == 0 && !publicMetrics.output().contains("caddy_http_requests_total") : "Public routes must not expose the private Caddy metrics handler";
            String ca = fixture.ca(), gatewayStarted = garden.started(NativeIngressFixture.GATEWAY), authStarted = garden.started(NativeIngressFixture.AUTH);
            garden.reconcile();
            assert garden.started(NativeIngressFixture.GATEWAY).equals(gatewayStarted) && garden.started(NativeIngressFixture.AUTH).equals(authStarted) : "Unchanged native OCI passes must preserve both process lifetimes";
            assert fixture.request("pi.native.localhost", "/", "alice", false).output().contains("status=200") : "No-op passes must preserve the actual session";
            trail.note("Upstream OCI, verified disposable HTTPS, independent identity, HTTP/WebSocket decisions and private-only metrics verified");

            String second = garden.commitXml(fixture.xml("admins"), "Activate a native OCI authorization policy change");
            garden.reconcile(); fixture.ready();
            assert garden.lastSuccess().equals(second) : "Native Authelia must load the generated policy after the base configuration";
            assert fixture.login("alice").output().strip().equals("200") && fixture.request("pi.native.localhost", "/", "alice", false).output().contains("status=403") : "Git policy activation must revoke the previously admitted AI user";
            assert fixture.login("carol").output().strip().equals("200") && fixture.request("pi.native.localhost", "/", "carol", false).output().contains("status=200") : "Git policy activation must admit the declared administrator group";
            incus.require("restart", NativeIngressFixture.GATEWAY); fixture.ready();
            assert fixture.ca().equals(ca) : "Caddy data/config volumes must retain its CA across activation/restart";
            assert fixture.request("pi.native.localhost", "/", "carol", false).output().contains("status=200") : "Gateway restart must preserve authorized service access";
            trail.note("Generated policy activation uses upstream OCI execution; unchanged passes and retained Caddy identity survive restart");

            var monitoring = new MonitoringFixture(incus, garden, fixture);
            monitoring.sources();
            String monitorRevision = garden.commitXml(monitoring.xml(), "Provision actual Prometheus and Grafana monitoring");
            garden.reconcile(); fixture.ready();
            assert garden.lastSuccess().equals(monitorRevision) : "Monitoring must activate the complete desired Git revision";
            for (String job : new String[]{"prometheus", "caddy", "authelia", "grafana", "incus"})
                assert monitoring.target(job, "up") : "Every enabled live monitoring target must be healthy within its readiness bound";
            assert monitoring.target("llama", "down") : "An idle/unavailable model must remain down without being loaded by monitoring";
            boolean idleModel = false;
            for (var target : monitoring.targets().getAsJsonObject("data").getAsJsonArray("activeTargets")) {
                var observed = target.getAsJsonObject();
                if (!observed.getAsJsonObject("labels").get("job").getAsString().equals("llama")) continue;
                idleModel = observed.getAsJsonObject("labels").get("model").getAsString().equals("tiny-ci") && observed.get("scrapeUrl").getAsString().contains("autoload=false") && observed.get("scrapeUrl").getAsString().contains("model=tiny-ci");
            }
            assert idleModel : "Llama scraping must preserve model identity and explicitly disable autoload";
            assert monitoring.metricsAdminStatus() == 403 : "The enrolled metrics certificate must not authorize administrator instance access";
            var ownership = incus.run(Duration.ofSeconds(10), "exec", MonitoringFixture.PROMETHEUS, "--", "stat", "-c", "%u:%g:%a", "/prometheus", "/etc/incus-tls/client.key", "/etc/prometheus/prometheus.yml");
            assert ownership.status() == 0 && ownership.output().strip().equals("65534:65534:700\n65534:65534:400\n65534:65534:644") : "Prometheus data, private key and configuration must have explicit consumer ownership";
            monitoring.admin();
            var datasource = monitoring.grafana("/api/datasources/uid/prometheus");
            assert datasource.get("type").getAsString().equals("prometheus") && datasource.get("url").getAsString().equals("http://10.79.0.24:9090") : "Grafana API must report the actual provisioned datasource";
            var dashboard = monitoring.grafana("/api/dashboards/uid/homelab-incus");
            assert dashboard.getAsJsonObject("meta").get("provisioned").getAsBoolean() && dashboard.getAsJsonObject("dashboard").get("title").getAsString().equals("Incus instances") : "Grafana must load the existing flattened Incus dashboard through its file provider";
            var proxied = monitoring.grafana("/api/datasources/proxy/uid/prometheus/api/v1/query?query=up%7Bjob%3D%22incus%22%7D");
            assert proxied.get("status").getAsString().equals("success") && proxied.getAsJsonObject("data").getAsJsonArray("result").get(0).getAsJsonObject().getAsJsonArray("value").get(1).getAsString().equals("1") : "Grafana's actual datasource must return scraped authenticated Incus data";
            String prometheusStarted = garden.started(MonitoringFixture.PROMETHEUS), grafanaStarted = garden.started(MonitoringFixture.GRAFANA);
            String adminIdentity = monitoring.adminIdentity();
            garden.reconcile();
            assert garden.started(MonitoringFixture.PROMETHEUS).equals(prometheusStarted) && garden.started(MonitoringFixture.GRAFANA).equals(grafanaStarted) : "No-op monitoring passes must preserve both OCI processes";
            monitoring.credential("untrusted");
            assert monitoring.target("incus", "down") && monitoring.target("caddy", "up") : "An unenrolled credential must fail Incus scraping while unrelated targets remain healthy";
            monitoring.credential("wrong-server");
            assert monitoring.target("incus", "down") : "Unrelated server trust must fail verified TLS without a bypass";
            monitoring.credential("valid");
            assert monitoring.target("incus", "up") : "Restoring the dedicated certificate and server CA must recover scraping";
            var sample = monitoring.query("up{job=\"incus\"}").getAsJsonObject("data").getAsJsonArray("result").get(0).getAsJsonObject().getAsJsonArray("value");
            String sampleTime = sample.get(0).getAsString();
            incus.require("restart", MonitoringFixture.PROMETHEUS);
            assert monitoring.target("incus", "up") : "Prometheus must recover its actual targets after restart";
            var historical = monitoring.query("up{job=\"incus\"} @ " + sampleTime).getAsJsonObject("data").getAsJsonArray("result");
            assert historical.size() == 1 && historical.get(0).getAsJsonObject().getAsJsonArray("value").get(1).getAsString().equals("1") : "Retained TSDB/WAL must preserve a pre-restart sample";
            incus.require("restart", MonitoringFixture.GRAFANA);
            assert monitoring.target("grafana", "up") : "Grafana must restart with readable provisioning and retained data";
            assert monitoring.adminIdentity().equals(adminIdentity) && monitoring.grafana("/api/dashboards/uid/homelab-incus").has("dashboard") : "Restart must preserve generated administrator identity and actual dashboard provisioning";
            assert monitoring.secretsExcluded() : "Metrics key and Grafana private credentials must stay out of command artifacts";
            trail.note("Actual Prometheus scraping, metrics-only verified TLS, Grafana datasource/dashboard/query and retained TSDB verified");

            var llama = new LlamaFixture(incus, garden);
            garden.source("prometheus.json", monitoring.configuration().replace("tiny-ci", "mimo"));
            String llamaRevision = garden.commitXml(llama.xml(monitoring.xml()), "Add explicitly controlled CPU model protocol coverage");
            garden.reconcile(); llama.ready();
            assert garden.lastSuccess().equals(llamaRevision) : "Tend must activate controlled model fixture and real monitoring configuration";
            var cacheIdentity = incus.run(Duration.ofSeconds(10), "exec", LlamaFixture.INSTANCE, "--", "stat", "-c", "%u:%g:%a", "/var/cache/llama", "/etc/llama/models.ini");
            assert cacheIdentity.status() == 0 && cacheIdentity.output().strip().equals("1000:1000:750\n1000:1000:644") : "CPU fixture must exercise real writable-cache and read-only-config permissions";
            assert llama.get("/v1/models").getAsJsonArray("data").size() == 2 : "Model discovery must preserve both public model IDs";
            assert monitoring.target("llama", "down") && llama.get("/fixture").get("loads").getAsInt() == 0 : "Real Prometheus autoload=false scraping must not load an idle model";
            var chat = llama.request("POST", "/v1/chat/completions", "{\"model\":\"mimo\",\"messages\":[{\"role\":\"user\",\"content\":\"synthetic OpenAI consumer probe\"}]}");
            assert chat.status() == 0 && com.google.gson.JsonParser.parseString(chat.output()).getAsJsonObject().get("model").getAsString().equals("mimo") : "Representative OpenAI consumer request must select the intended model";
            assert monitoring.target("llama", "up") && llama.get("/fixture").get("loads").getAsInt() == 1 : "Scraping the selected model must observe it without switching or loading another";
            var stream = llama.request("POST", "/v1/chat/completions", "{\"model\":\"qwen36\",\"stream\":true,\"messages\":[]}");
            assert stream.status() == 0 && stream.output().contains("qwen36") && stream.output().contains("data: [DONE]") : "Controlled streaming response must carry the selected model and terminate";
            assert llama.get("/fixture").get("loads").getAsInt() == 2 && llama.get("/fixture").get("loaded").getAsString().equals("qwen36") : "Explicit model switching must be independent of idle scrape requests";
            assert monitoring.target("llama", "down") && llama.get("/fixture").get("loads").getAsInt() == 2 : "Monitoring the now-idle previous model must never switch it back";
            incus.require("restart", LlamaFixture.INSTANCE); llama.ready();
            var retained = incus.run(Duration.ofSeconds(10), "exec", LlamaFixture.INSTANCE, "--", "test", "-f", "/var/cache/llama/mimo.synthetic-cache");
            assert retained.status() == 0 && llama.get("/fixture").get("uid").getAsInt() == 1000 : "Cache files must persist across restart while the model process retains UID 1000";
            trail.note("Controlled CPU protocol ONLY: model discovery/selection/streaming, persistent cache and real idle monitoring; no llama binary, downloaded weights or GPU inference");
        }
    }
}
