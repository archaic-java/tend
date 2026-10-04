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
        }
    }
}
