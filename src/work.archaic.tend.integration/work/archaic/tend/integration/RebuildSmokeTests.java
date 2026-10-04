package work.archaic.tend.integration;

import java.nio.file.*;
import java.time.Duration;
import java.util.*;
import work.archaic.service.test.v02.*;

/** Actual OCI watch is the only application writer for the exact seven-workload garden. */
public record RebuildSmokeTests() implements TestSuite {
    public void cases(Collection<TestCase> cases) { cases.add(new ExactGardenRebuild()); }
}
record ExactGardenRebuild() implements TestCase {
    public void run(TestTrail trail) throws Exception {
        var commands = new IncusCommands("rebuild");
        try (var garden = new RealGarden(commands)) {
            var fixture = new RebuildFixture(commands, garden);
            var controller = fixture.controller();
            var empty = commands.run(Duration.ofSeconds(10), "list", "--project", RebuildFixture.PROJECT, "--format=json");
            assert empty.status() == 0 && com.google.gson.JsonParser.parseString(empty.output()).getAsJsonArray().isEmpty() : "Composition must begin with an empty application project";
            String revision = fixture.prepare();
            controller.bootstrap("create"); controller.prepareGit(); controller.start();
            assert controller.converged(revision) : "Actual OCI watch must create all seven applications from the exact prepared main commit";
            fixture.ready();
            var instances = commands.run(Duration.ofSeconds(10), "list", "--project", RebuildFixture.PROJECT, "--format=json");
            var names = new HashSet<String>();
            for (var value : com.google.gson.JsonParser.parseString(instances.output()).getAsJsonArray()) names.add(value.getAsJsonObject().get("name").getAsString());
            assert names.equals(Set.of("caddy", "authelia", "grafana", "prometheus", "llama", "openwebui", "pi", OciControllerFixture.CONTROLLER)) : "Only Tend and the seven committed workloads may exist";
            revision = fixture.trustActualGateway(); controller.copyGit();
            assert controller.converged(revision) : "The actual public local CA must activate consumers through Git";
            fixture.ready(); String secret = fixture.secret();
            String authStarted = fixture.started("authelia"), gatewayStarted = fixture.started("caddy");
            Thread.sleep(3000);
            assert fixture.started("authelia").equals(authStarted) && fixture.started("caddy").equals(gatewayStarted) : "Repeated OCI convergence must preserve process lifetimes";

            try (var browser = new PasskeyBrowser(commands, garden.directory, "caddy", RebuildFixture.ISSUER, RebuildFixture.PROJECT)) {
                assert browser.password("carol") == 200 : "New synthetic administrator must authenticate to the composed issuer";
                browser.navigate("https://grafana.compose.localhost/login/generic_oauth", RebuildFixture.ISSUER);
                browser.acceptConsent("grafana-garden", "https://grafana.compose.localhost");
                var user = browser.applicationUser();
                assert user.get("status").getAsInt() == 200 && user.get("email").getAsString().equals("carol@example.invalid") && user.get("admin").getAsBoolean() : "Composed Grafana must complete actual OIDC and map the admin identity";
                var query = browser.evaluate("(async()=>{const r=await fetch('/api/datasources/proxy/uid/prometheus/api/v1/query?query=up%7Bjob%3D%22incus%22%7D');const b=await r.json();return {status:r.status,up:b.data?.result?.[0]?.value?.[1]};})()").getAsJsonObject();
                assert query.get("status").getAsInt() == 200 && query.get("up").getAsString().equals("1") : "Actual composed Grafana datasource must return authenticated Incus data";
                var dashboard = browser.evaluate("(async()=>{const r=await fetch('/api/dashboards/uid/homelab-incus');const b=await r.json();return {status:r.status,provisioned:b.meta?.provisioned};})()").getAsJsonObject();
                assert dashboard.get("status").getAsInt() == 200 && dashboard.get("provisioned").getAsBoolean() : "Composed dashboard must load from its flat Git mount";
                browser.portal();
                browser.navigate("https://ai.compose.localhost/oauth/oidc/login", RebuildFixture.ISSUER);
                browser.acceptConsent("webui-garden", "https://ai.compose.localhost");
                assert browser.webuiUser().get("role").getAsString().equals("admin") : "Approved first Open WebUI account must complete the composed callback";
                browser.privateValues(); browser.clearSession(); browser.portal();
                assert browser.password("alice") == 200 : "Ordinary ai-users account must authenticate in the composed deployment";
                browser.navigate("https://ai.compose.localhost/oauth/oidc/login", RebuildFixture.ISSUER);
                browser.acceptConsent("webui-garden", "https://ai.compose.localhost");
                assert browser.webuiUser().get("email").getAsString().equals("alice@example.invalid") && browser.webuiUser().get("role").getAsString().equals("user") : "Composed application must map the ordinary admitted identity";
                var models = browser.evaluate("(async()=>{const r=await fetch('/api/models');const b=await r.json();return {status:r.status,ids:b.data?.map(m=>m.id)};})()").getAsJsonObject();
                assert models.get("status").getAsInt() == 200 && models.getAsJsonArray("ids").toString().contains("mimo") : "Actual WebUI must discover the controlled private model";
                var chat = browser.evaluate("""
                        (async()=>{const token=document.cookie.split('; ').find(v=>v.startsWith('token='))?.slice(6);
                        const r=await fetch('/api/chat/completions',{method:'POST',signal:AbortSignal.timeout(30000),headers:{'Content-Type':'application/json','Authorization':'Bearer '+decodeURIComponent(token)},
                        body:JSON.stringify({model:'mimo',messages:[{role:'user',content:'synthetic composition probe'}],stream:true})});
                        const text=await r.text();return {status:r.status,synthetic:text.includes('synthetic CPU protocol response'),done:text.includes('[DONE]')};})()
                        """).getAsJsonObject();
                assert chat.get("status").getAsInt() == 200 && chat.get("synthetic").getAsBoolean() && chat.get("done").getAsBoolean() : "Actual WebUI must proxy selected-model chat and streaming through the controlled endpoint";
                assert fixture.modelState().get("loads").getAsInt() == 1 && fixture.modelState().get("loaded").getAsString().equals("mimo") : "Only the requested model may be loaded";
                browser.navigate("https://pi.compose.localhost/", "https://pi.compose.localhost");
                var health = browser.evaluate("(async()=>{const r=await fetch('/api/health');return {status:r.status};})()").getAsJsonObject();
                assert health.get("status").getAsInt() == 200 : "Composed protected Pi must serve the admitted user through ForwardAuth";
                var websocket = browser.evaluate("""
                        new Promise(resolve=>{const socket=new WebSocket('wss://pi.compose.localhost/ws');
                        const timeout=setTimeout(()=>{socket.close();resolve({sync:false});},10000);
                        socket.onmessage=e=>{const data=JSON.parse(e.data);if(data.type!=='mirror_sync')return;clearTimeout(timeout);socket.close();resolve({sync:true,session:!!data.sessionId});};
                        socket.onerror=()=>{clearTimeout(timeout);resolve({sync:false});};})
                        """).getAsJsonObject();
                assert websocket.get("sync").getAsBoolean() : "The actual Pi WebSocket must upgrade and deliver its shared-session synchronization";
                browser.privateValues(); browser.clearSession(); browser.portal();
                assert browser.password("bob") == 200 : "Outsider must authenticate before the composed authorization checks";
                browser.navigate("https://pi.compose.localhost/", "https://pi.compose.localhost");
                assert browser.evaluate("(async()=>{const r=await fetch('/api/health');return r.status;})()").getAsInt() == 403 : "Authenticated outsider must not reach protected Pi";
                browser.navigate("https://ai.compose.localhost/oauth/oidc/login", "https://ai.compose.localhost");
                assert browser.webuiUser().get("status").getAsInt() != 200 : "Outsider must not obtain a composed application identity";
                assert fixture.evidenceExcluded(browser.privateValues(), secret) : "Composed secrets, session cookies, codes and tokens must be absent from uploaded evidence";
            }
            String updated = fixture.update(); controller.copyGit();
            assert controller.converged(updated) && !fixture.started("authelia").equals(authStarted) && fixture.started("caddy").equals(gatewayStarted) : "Git configuration change must activate only the affected application";
            controller.stop(); controller.detachGit(); long failures = controller.failures(); controller.start();
            assert controller.failedAfter(failures) && controller.lastSuccess().equals(updated) : "Unavailable Git must fail safely without advancing success";
            controller.stop(); controller.attachGit(); controller.start();
            assert controller.converged(updated) && fixture.secret().equals(secret) : "Git recovery and controller restart must preserve generated identity and selected main";
            assert fixture.modelState().get("loads").getAsInt() == 1 : "Repeated real idle-model monitoring must never load or switch another model";
            controller.stop();
            trail.note("Exact prepared seven-workload garden created by actual OCI watch; local CA/controlled CPU substitutions recorded. Production ZFS/GPU/ACME/reboot gates remain pending");
            Files.writeString(Path.of("out/incus-smoke/rebuild-revisions.txt"), "selected-main=" + revision + "\nupdated-main=" + updated + "\n");
        }
    }
}
