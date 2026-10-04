package work.archaic.tend.test;

import com.google.gson.*;
import java.util.*;
import work.archaic.service.test.v02.*;
import work.archaic.tend.ReconciliationException;
import work.archaic.tend.incus.IncusException;
import work.archaic.tend.state.StateException;

public record RequirementsTests() implements TestSuite {
    public void cases(Collection<TestCase> cases) {
        cases.add(new NamedMounts());
        cases.add(new UnshiftedFileVolume());
        cases.add(new MalformedIncusConfiguration());
        cases.add(new UnownedNetworkAcl());
        cases.add(new InvalidNumericAttribute());
        cases.add(new PublicOnlyIngress());
        cases.add(new FirstSliceExample());
        cases.add(new RequirementsExample());
        cases.add(new NamedConfigurationChanges());
        cases.add(new RemovedConfigurationFile());
        cases.add(new IngressProjection());
        cases.add(new PrivateGatewayMetrics());
        cases.add(new FlatMonitoringProvisioning());
        cases.add(new IngressIdempotence());
        cases.add(new AuthorizationActivationFails());
        cases.add(new EgressProjection());
        cases.add(new EgressIdempotence());
        cases.add(new EgressDrift());
        cases.add(new UnsupportedNetwork());
        cases.add(new SharedNetworkAcl());
        cases.add(new MissingConfiguration());
        cases.add(new MountOverlap());
        cases.add(new MissingAuthorizationLoad());
        cases.add(new DuplicateIngressHost());
        cases.add(new UnboundIngress());
        cases.add(new HostnameEgress());
        cases.add(new ConflictingAclSettings());
    }
}
record NamedMounts() implements TestCase {
    public void run(TestTrail trail) throws Exception {
        try (var f = new DeploymentFixture()) {
            var state = RequirementsFixture.apply(f, RequirementsFixture.mounts());
            var devices = f.mock.resources.get(Garden.INSTANCE).getAsJsonObject("devices");
            assert devices.getAsJsonObject("settings").get("readonly").getAsString().equals("true") : "Configuration must be read-only inside the instance";
            String source = RequirementsFixture.source(f, "session");
            var file = f.mock.files.get("/1.0/storage-pools/pool/volumes/custom/" + source + "/value");
            assert file.uid().equals("1000") && file.mode().equals("0400") : "Secret delivery must use declared ownership and private mode";
            var volume = f.mock.resources.get("/1.0/storage-pools/pool/volumes/custom/" + source).getAsJsonObject("config");
            assert volume.get("initial.uid").getAsString().equals("1000") : "Private volume directory must be accessible to the consuming UID";
            assert volume.get("security.shifted").getAsString().equals("true") : "Generated volumes must keep on-disk IDs stable through idmapped mounts";
            byte[] original = file.bytes(); f.engine.reconcile(state);
            assert Arrays.equals(original, f.mock.files.get("/1.0/storage-pools/pool/volumes/custom/" + source + "/value").bytes()) : "Named secret references must retain generated values";
        }
    }
}
record NamedConfigurationChanges() implements TestCase {
    public void run(TestTrail trail) throws Exception {
        try (var f = new DeploymentFixture()) {
            RequirementsFixture.apply(f, RequirementsFixture.mounts());
            int starts = f.mock.starts;
            var changed = f.revision(RequirementsFixture.mounts(), "version=two\n"); f.engine.reconcile(changed);
            assert f.mock.starts == starts + 1 : "Changed named configuration must activate its consumer once";
            int mutations = f.mock.mutations; f.engine.reconcile(changed);
            assert f.mock.mutations == mutations : "Named configuration must converge without further writes";
        }
    }
}
record RemovedConfigurationFile() implements TestCase {
    public void run(TestTrail trail) throws Exception {
        try (var f = new DeploymentFixture()) {
            String expanded = RequirementsFixture.mounts().replace("</configuration>", "<file path=\"/extra.conf\" source=\"service.conf\"/></configuration>");
            RequirementsFixture.apply(f, expanded);
            String old = RequirementsFixture.source(f, "settings");
            RequirementsFixture.apply(f, RequirementsFixture.mounts());
            String current = RequirementsFixture.source(f, "settings");
            assert !old.equals(current) : "Removing a file must change the mounted file set";
            assert !f.mock.files.containsKey("/1.0/storage-pools/pool/volumes/custom/" + current + "/extra.conf") : "Removed configuration files must not remain visible";
        }
    }
}
record IngressProjection() implements TestCase {
    public void run(TestTrail trail) throws Exception {
        try (var f = new DeploymentFixture()) {
            RequirementsFixture.apply(f, RequirementsFixture.ingress());
            String caddy = RequirementsFixture.text(f, "Caddyfile");
            assert caddy.contains("forward_auth 10.20.0.11:9091") && caddy.contains("uri /api/authz/forward-auth") : "Protected routes must call Authelia before the backend";
            assert caddy.contains("reverse_proxy 10.20.0.10:8080") : "Backend reference must use the declared instance NIC address";
            String portal = caddy.substring(caddy.indexOf("auth.example.org"), caddy.indexOf("demo.example.org"));
            assert !portal.contains("forward_auth") : "Explicit public route must reach the authorization portal without a loop";
            assert caddy.contains("request_header -Remote-User") : "Client identity headers must be stripped before authorization";
            var access = JsonParser.parseString(RequirementsFixture.text(f, "access-control.json")).getAsJsonObject().getAsJsonObject("access_control");
            assert access.get("default_policy").getAsString().equals("deny") : "Authelia policy must deny undeclared hosts";
            assert access.getAsJsonArray("rules").get(0).getAsJsonObject().get("policy").getAsString().equals("two_factor") : "Protected ingress must default to two-factor authorization";
            var subjects = access.getAsJsonArray("rules").get(0).getAsJsonObject().getAsJsonArray("subject");
            assert subjects.size() == 2 && subjects.get(0).getAsJsonArray().get(0).getAsString().equals("group:gardeners") : "Groups must be alternative allowed subjects";
        }
    }
}
record IngressIdempotence() implements TestCase {
    public void run(TestTrail trail) throws Exception {
        try (var f = new DeploymentFixture()) {
            var state = RequirementsFixture.apply(f, RequirementsFixture.ingress());
            int mutations = f.mock.mutations; f.engine.reconcile(state);
            assert f.mock.mutations == mutations : "Rendered ingress and authorization policy must settle";
        }
    }
}
record AuthorizationActivationFails() implements TestCase {
    public void run(TestTrail trail) throws Exception {
        try (var f = new DeploymentFixture()) {
            f.mock.failStart = true;
            boolean rejected = false;
            try { RequirementsFixture.apply(f, RequirementsFixture.ingress()); } catch (IncusException e) { rejected = true; }
            assert rejected : "Failed authorization activation must escape reconciliation";
            assert !f.mock.running.getOrDefault("/1.0/instances/caddy", false) : "Gateway must not start ahead of authorization activation";
        }
    }
}
record EgressProjection() implements TestCase {
    public void run(TestTrail trail) throws Exception {
        try (var f = new DeploymentFixture()) {
            RequirementsFixture.network(f, "ovn");
            RequirementsFixture.apply(f, RequirementsFixture.egress());
            var acl = f.mock.resources.get(RequirementsFixture.acl(f));
            assert acl.getAsJsonArray("egress").size() == 2 : "Declared destinations must become Incus ACL rules";
            var first = acl.getAsJsonArray("egress").get(0).getAsJsonObject();
            assert first.get("destination").getAsString().equals("10.20.0.11/32") && first.get("destination_port").getAsString().equals("9091") : "ACL must preserve destination and port";
            var nic = f.mock.resources.get(Garden.INSTANCE).getAsJsonObject("devices").getAsJsonObject("eth0");
            assert nic.get("security.acls.default.egress.action").getAsString().equals("reject") : "Unmatched outbound traffic must be rejected";
            assert nic.get("security.acls.default.ingress.action").getAsString().equals("allow") : "Egress declaration must not invent an ingress firewall policy";
        }
    }
}
record EgressIdempotence() implements TestCase {
    public void run(TestTrail trail) throws Exception {
        try (var f = new DeploymentFixture()) {
            RequirementsFixture.network(f, "ovn");
            var state = RequirementsFixture.apply(f, RequirementsFixture.egress());
            int mutations = f.mock.mutations; f.engine.reconcile(state);
            assert f.mock.mutations == mutations : "Unchanged network policies must cause no writes";
        }
    }
}
record EgressDrift() implements TestCase {
    public void run(TestTrail trail) throws Exception {
        try (var f = new DeploymentFixture()) {
            RequirementsFixture.network(f, "ovn");
            var state = RequirementsFixture.apply(f, RequirementsFixture.egress());
            f.mock.resources.get(RequirementsFixture.acl(f)).add("egress", new JsonArray());
            f.engine.reconcile(state);
            assert f.mock.resources.get(RequirementsFixture.acl(f)).getAsJsonArray("egress").size() == 2 : "Owned ACL drift must be repaired";
        }
    }
}
record UnsupportedNetwork() implements TestCase {
    public void run(TestTrail trail) throws Exception {
        try (var f = new DeploymentFixture()) {
            RequirementsFixture.network(f, "bridge");
            boolean rejected = false;
            try { RequirementsFixture.apply(f, RequirementsFixture.egress()); } catch (ReconciliationException e) { rejected = true; }
            assert rejected && f.mock.mutations == 0 : "Unsupported network must fail preflight before any deployment mutation";
        }
    }
}
record SharedNetworkAcl() implements TestCase {
    public void run(TestTrail trail) throws Exception {
        try (var f = new DeploymentFixture()) {
            RequirementsFixture.network(f, "ovn");
            f.mock.resources.get("/1.0/networks/garden-net").getAsJsonObject("config").addProperty("security.acls", "operator-policy");
            boolean rejected = false;
            try { RequirementsFixture.apply(f, RequirementsFixture.egress()); } catch (ReconciliationException e) { rejected = true; }
            assert rejected && f.mock.mutations == 0 : "Shared ACLs must not widen the declared outbound policy";
        }
    }
}
record MissingConfiguration() implements TestCase {
    public void run(TestTrail trail) throws Exception {
        try (var f = new DeploymentFixture()) {
            boolean rejected = false;
            try { f.revision(RequirementsFixture.mounts().replace("configuration=\"settings\"", "configuration=\"missing\""), "first"); } catch (StateException e) { rejected = true; }
            assert rejected && f.mock.mutations == 0 : "Invalid resource reference or policy must fail before mutation";
        }
    }
}
record MountOverlap() implements TestCase {
    public void run(TestTrail trail) throws Exception {
        try (var f = new DeploymentFixture()) {
            boolean rejected = false;
            try { f.revision(RequirementsFixture.mounts().replace("path=\"/etc/demo\"", "path=\"/data/nested\""), "first"); } catch (StateException e) { rejected = true; }
            assert rejected && f.mock.mutations == 0 : "Invalid resource reference or policy must fail before mutation";
        }
    }
}
record MissingAuthorizationLoad() implements TestCase {
    public void run(TestTrail trail) throws Exception {
        try (var f = new DeploymentFixture()) {
            boolean rejected = false;
            try { f.revision(RequirementsFixture.ingress().replace("/config/configuration.yml,/etc/tend-authorization/access-control.json", "/config/configuration.yml"), "first"); } catch (StateException e) { rejected = true; }
            assert rejected && f.mock.mutations == 0 : "Invalid resource reference or policy must fail before mutation";
        }
    }
}
record DuplicateIngressHost() implements TestCase {
    public void run(TestTrail trail) throws Exception {
        try (var f = new DeploymentFixture()) {
            boolean rejected = false;
            try { f.revision(RequirementsFixture.ingress().replace("auth.example.org", "demo.example.org"), "first"); } catch (StateException e) { rejected = true; }
            assert rejected && f.mock.mutations == 0 : "Invalid resource reference or policy must fail before mutation";
        }
    }
}
record UnboundIngress() implements TestCase {
    public void run(TestTrail trail) throws Exception {
        try (var f = new DeploymentFixture()) {
            boolean rejected = false;
            try { f.revision(RequirementsFixture.ingress().replaceAll("<ingress-gateway[^>]*/>", ""), "first"); } catch (StateException e) { rejected = true; }
            assert rejected && f.mock.mutations == 0 : "Invalid resource reference or policy must fail before mutation";
        }
    }
}
record HostnameEgress() implements TestCase {
    public void run(TestTrail trail) throws Exception {
        try (var f = new DeploymentFixture()) {
            boolean rejected = false;
            try { f.revision(RequirementsFixture.egress().replace("10.20.0.11/32", "auth.example.org"), "first"); } catch (StateException e) { rejected = true; }
            assert rejected && f.mock.mutations == 0 : "Invalid resource reference or policy must fail before mutation";
        }
    }
}
record ConflictingAclSettings() implements TestCase {
    public void run(TestTrail trail) throws Exception {
        try (var f = new DeploymentFixture()) {
            boolean rejected = false;
            try { f.revision(RequirementsFixture.egress().replace("<entry key=\"network\"", "<entry key=\"security.acls\" value=\"external\"/><entry key=\"network\""), "first"); } catch (StateException e) { rejected = true; }
            assert rejected && f.mock.mutations == 0 : "Invalid resource reference or policy must fail before mutation";
        }
    }
}

record FirstSliceExample() implements TestCase {
    public void run(TestTrail trail) throws Exception {
        try (var f = new DeploymentFixture()) {
            var directory = f.garden.author.resolve("examples"); java.nio.file.Files.createDirectories(directory);
            java.nio.file.Files.copy(java.nio.file.Path.of("examples/service.conf"), directory.resolve("service.conf"));
            var state = f.revision(java.nio.file.Files.readString(java.nio.file.Path.of("examples/first-slice.xml")), "unused");
            f.engine.reconcile(state);
            assert f.mock.running.get(Garden.INSTANCE) : "Published first-slice example must reconcile through real adapters";
        }
    }
}
record RequirementsExample() implements TestCase {
    public void run(TestTrail trail) throws Exception {
        try (var f = new DeploymentFixture()) {
            var directory = f.garden.author.resolve("examples"); java.nio.file.Files.createDirectories(directory);
            java.nio.file.Files.copy(java.nio.file.Path.of("examples/service.conf"), directory.resolve("service.conf"));
            RequirementsFixture.network(f, "ovn");
            var state = f.revision(java.nio.file.Files.readString(java.nio.file.Path.of("examples/requirements.xml")), "unused");
            f.engine.reconcile(state);
            assert f.mock.running.get("/1.0/instances/caddy") : "Published requirements example must activate its gateway";
            assert f.mock.resources.containsKey(RequirementsFixture.acl(f)) : "Published requirements example must reconcile its egress ACL";
        }
    }
}

record MalformedIncusConfiguration() implements TestCase {
    public void run(TestTrail trail) throws Exception {
        try (var f = new DeploymentFixture()) {
            var volume = new JsonObject(); volume.add("config", new JsonArray()); f.mock.seed(Garden.VOLUME, volume);
            boolean rejected = false;
            try { f.deploy(); } catch (work.archaic.tend.incus.IncusException e) { rejected = true; }
            assert rejected && f.mock.mutations == 0 : "Malformed API metadata must be a checked protocol failure before mutation";
        }
    }
}
record UnownedNetworkAcl() implements TestCase {
    public void run(TestTrail trail) throws Exception {
        try (var f = new DeploymentFixture()) {
            RequirementsFixture.network(f, "ovn");
            var state = f.revision(RequirementsFixture.egress(), "first");
            String name = new work.archaic.tend.state.ResourceCompiler().compile(state).acls().getFirst().name();
            var acl = new JsonObject(); acl.add("config", new JsonObject()); f.mock.seed("/1.0/network-acls/" + name, acl);
            boolean rejected = false;
            try { f.engine.reconcile(state); } catch (ReconciliationException e) { rejected = true; }
            assert rejected && f.mock.mutations == 0 : "Unowned ACL must fail preflight without adopting or widening it";
        }
    }
}
record InvalidNumericAttribute() implements TestCase {
    public void run(TestTrail trail) throws Exception {
        try (var f = new DeploymentFixture()) {
            boolean rejected = false;
            try { f.revision(Garden.xml().replace("uid=\"1000\"", "uid=\"99999999999999999999\""), "first"); }
            catch (work.archaic.tend.state.StateException e) { rejected = true; }
            assert rejected : "Oversized XML integers must become checked validation failures";
        }
    }
}
record PublicOnlyIngress() implements TestCase {
    public void run(TestTrail trail) throws Exception {
        try (var f = new DeploymentFixture()) {
            String xml = RequirementsFixture.ingress().replaceAll("<authorization>.*?</authorization>", "<public/>");
            RequirementsFixture.apply(f, xml);
            String caddy = RequirementsFixture.text(f, "Caddyfile");
            assert !caddy.contains("forward_auth") : "Explicit public routes must not require authentication";
            var policy = JsonParser.parseString(RequirementsFixture.text(f, "access-control.json")).getAsJsonObject().getAsJsonObject("access_control");
            assert policy.getAsJsonArray("rules").get(0).getAsJsonObject().get("policy").getAsString().equals("deny") : "Unused authorization policy must remain valid and deny by default";
        }
    }
}

record UnshiftedFileVolume() implements TestCase {
    public void run(TestTrail trail) throws Exception {
        try (var f = new DeploymentFixture()) {
            boolean rejected = false;
            try { f.revision(Garden.xml().replace("<entry key=\"security.shifted\" value=\"true\"/>", ""), "version=one\n"); }
            catch (work.archaic.tend.state.StateException expected) { rejected = true; }
            assert rejected : "File volumes without explicit idmapped mounts must fail before deployment";
            assert f.mock.mutations == 0 : "Unsupported ID mapping must not partially deploy resources";
        }
    }
}

record PrivateGatewayMetrics() implements TestCase {
    public void run(TestTrail trail) throws Exception {
        try (var f = new DeploymentFixture()) {
            String xml = RequirementsFixture.ingress().replace("authorization-path=\"/etc/tend-authorization\"/>", "authorization-path=\"/etc/tend-authorization\"><metrics device=\"eth0\"/></ingress-gateway>");
            xml = xml.replace("<instance name=\"caddy\" fingerprint=\"" + Garden.IMAGE + "\">", "<instance name=\"caddy\" fingerprint=\"" + Garden.IMAGE + "\"><device name=\"eth0\" type=\"nic\"><config><entry key=\"network\" value=\"garden-net\"/><entry key=\"ipv4.address\" value=\"10.20.0.12\"/></config></device>");
            assert !xml.equals(RequirementsFixture.ingress()) : "Fixture must enable the private metrics declaration";
            RequirementsFixture.apply(f, xml);
            String caddy = RequirementsFixture.text(f, "Caddyfile");
            assert caddy.contains("{\n    metrics\n}") && caddy.contains("http://10.20.0.12:9180") && caddy.contains("bind 10.20.0.12") : "Metrics must be explicitly bound to the private gateway NIC";
            for (String header : List.of("Remote-User", "Remote-Email", "Remote-Groups", "Remote-Name", "X-Forwarded-User", "X-Forwarded-Email", "X-Forwarded-Groups"))
                assert caddy.split("request_header -" + header, -1).length == 3 : "Every public and protected route must strip client identity headers";
            int mutations = f.mock.mutations;
            boolean rejected = false;
            try { f.revision(xml.replace("10.20.0.12", "203.0.113.12"), "unchanged"); } catch (StateException e) { rejected = true; }
            assert rejected && f.mock.mutations == mutations : "Public-address metrics must reject before mutation";
        }
    }
}

record FlatMonitoringProvisioning() implements TestCase {
    public void run(TestTrail trail) throws Exception {
        try (var f = new DeploymentFixture()) {
            for (String file : List.of("prometheus.json", "datasources.json", "dashboards.json", "incus.json"))
                java.nio.file.Files.copy(java.nio.file.Path.of("examples/monitoring", file), f.garden.author.resolve(file));
            String xml = java.nio.file.Files.readString(java.nio.file.Path.of("examples/monitoring/incus.xml")).replace("private-owner=\"garden\"", "private-owner=\"test-controller\"");
            var privateConfig = JsonParser.parseString("""
                    {"config":{"user.tend.private":"test-controller","user.tend.private.kind":"metrics","security.shifted":"true","initial.uid":"65534","initial.gid":"65534","initial.mode":"0700"}}
                    """).getAsJsonObject();
            f.mock.resources.put("/1.0/storage-pools/pool/volumes/custom/incus-metrics", privateConfig.deepCopy());
            var desired = f.revision(xml, "monitoring"); f.engine.reconcile(desired);
            var dashboards = f.mock.files.entrySet().stream().filter(e -> e.getKey().endsWith("/incus.json")).findFirst().orElseThrow().getValue();
            assert dashboards.uid().equals("472") && dashboards.gid().equals("0") && dashboards.mode().equals("0644") : "Flat dashboards must be readable by the Grafana OCI UID";
            var prometheus = f.mock.files.entrySet().stream().filter(e -> e.getKey().endsWith("/prometheus.yml")).findFirst().orElseThrow().getValue();
            assert prometheus.uid().equals("65534") && prometheus.gid().equals("65534") : "Prometheus configuration must use its actual OCI identity";
            var config = JsonParser.parseString(new String(prometheus.bytes(), java.nio.charset.StandardCharsets.UTF_8)).getAsJsonObject();
            var jobs = config.getAsJsonArray("scrape_configs");
            assert jobs.size() == 6 : "The monitoring recipe must declare all six required scrape jobs";
            var llama = jobs.get(5).getAsJsonObject();
            assert llama.getAsJsonObject("params").getAsJsonArray("autoload").get(0).getAsString().equals("false") : "An idle model must never be activated just by a monitoring request";
            var tls = jobs.get(4).getAsJsonObject().getAsJsonObject("tls_config");
            assert tls.has("server_name") && tls.has("ca_file") && tls.has("cert_file") && tls.has("key_file") && !tls.has("insecure_skip_verify") : "Incus scraping requires explicit server identity/trust and separate private client credentials";
            int mutations = f.mock.mutations; f.engine.reconcile(desired);
            assert f.mock.mutations == mutations && f.mock.resources.get("/1.0/storage-pools/pool/volumes/custom/incus-metrics").equals(privateConfig) : "Unchanged monitoring must settle while retaining operator-owned TLS";
        }
    }
}
