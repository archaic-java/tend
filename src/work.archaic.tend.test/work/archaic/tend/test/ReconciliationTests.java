package work.archaic.tend.test;

import com.google.gson.*;
import java.io.IOException;
import java.net.http.HttpClient;
import java.nio.file.*;
import java.time.Duration;
import java.util.*;
import work.archaic.service.test.v02.*;
import work.archaic.tend.Reconciler;
import work.archaic.tend.incus.IncusClient;
import work.archaic.tend.secrets.SecretStore;
import work.archaic.tend.state.StateReader;

public record ReconciliationTests() implements TestSuite {
    public void cases(Collection<TestCase> cases) {
        for (String scenario : List.of("create", "idempotence", "config-update", "drift", "file-drift", "secret-restart",
                "partial-failure", "start-failure", "etag-conflict", "uncertain-volume", "unmanaged", "retain",
                "remove-key", "stop", "scope", "image-change", "operation-timeout", "file-activation-failure")) cases.add(new ReconciliationCase(scenario));
    }
}
record ReconciliationCase(String scenario) implements TestCase {
    public void run(TestTrail trail) throws Exception {
        try (Garden garden = new Garden(); IncusMock mock = new IncusMock();
             IncusClient client = new IncusClient(mock.endpoint(), "garden", HttpClient.newHttpClient(),
                     scenario.equals("operation-timeout") ? Duration.ofMillis(200) : Duration.ofSeconds(5))) {
            var reader = new StateReader(Path.of("schema/tend.xsd"));
            var store = new SecretStore(garden.state.resolve("secrets"));
            var engine = new Reconciler(client, store, "garden", "test-controller");
            garden.commit(Garden.xml(), "version=one\n");
            var desired = reader.read(garden.git.fetchMain(), "incus.xml");
            if (scenario.equals("unmanaged")) {
                JsonObject existing = new JsonObject(); existing.add("config", new JsonObject());
                mock.seed(Garden.VOLUME, existing);
                boolean rejected = false;
                try { engine.reconcile(desired); } catch (IOException e) { rejected = true; }
                assert rejected : "Unowned resources must not be adopted implicitly";
                assert mock.mutations == 0 : "Ownership preflight must precede mutation";
                return;
            }
            if (scenario.equals("scope")) {
                var other = new Reconciler(client, store, "other", "test-controller");
                boolean rejected = false;
                try { other.reconcile(desired); } catch (IOException e) { rejected = true; }
                assert rejected && mock.mutations == 0 : "Desired project cannot escape controller scope";
                return;
            }
            if (scenario.equals("partial-failure")) mock.failurePath = "/1.0/instances";
            if (scenario.equals("start-failure")) mock.failStart = true;
            if (scenario.equals("uncertain-volume")) mock.loseResponse = true;
            if (scenario.equals("operation-timeout")) mock.stall = true;
            boolean failed = false;
            try { engine.reconcile(desired); } catch (IOException e) { failed = true; trail.note(e.getMessage()); }
            if (Set.of("partial-failure", "start-failure", "uncertain-volume", "operation-timeout").contains(scenario)) {
                assert failed : "Injected failure must escape reconciliation";
                if (scenario.equals("start-failure")) {
                    assert !mock.resources.get(Garden.INSTANCE).getAsJsonObject("config").has("user.tend.activated") : "Failed start must leave activation pending";
                }
                if (scenario.equals("operation-timeout")) return;
                engine.reconcile(desired);
            } else assert !failed : "Initial deployment should succeed";
            assert mock.resources.containsKey(Garden.VOLUME) : "Persistent volume must be created";
            assert mock.running.get(Garden.INSTANCE) : "Declared running instance must start";
            assert mock.resources.get(Garden.INSTANCE).getAsJsonObject("config").get("volatile.test").getAsString().equals("preserve") : "Activation must preserve Incus volatile configuration";
            if (scenario.equals("create")) {
                assert mock.files.get(Garden.VOLUME + "/session").mode().equals("0400") : "Secret permissions must follow declaration";
                assert mock.operations.values().stream().allMatch(op -> op.action() == null || op.failed()) : "Client must await asynchronous operations";
            }
            if (scenario.equals("idempotence")) {
                int count = mock.mutations; engine.reconcile(desired);
                assert mock.mutations == count : "Second pass must make no writes or restarts";
            }
            if (scenario.equals("config-update")) {
                int starts = mock.starts;
                garden.commit(Garden.xml(), "version=two\n");
                engine.reconcile(reader.read(garden.git.fetchMain(), "incus.xml"));
                assert mock.starts == starts + 1 : "Changed configuration must activate exactly once";
                int count = mock.mutations; engine.reconcile(reader.read(garden.git.fetchMain(), "incus.xml"));
                assert mock.mutations == count : "Activated configuration must settle";
            }
            if (scenario.equals("drift")) {
                mock.drift(Garden.INSTANCE, "environment.DEMO", "drifted");
                mock.drift(Garden.INSTANCE, "user.operator", "keep");
                engine.reconcile(desired);
                var config = mock.resources.get(Garden.INSTANCE).getAsJsonObject("config");
                assert config.get("environment.DEMO").getAsString().equals("one") : "Owned configuration drift must be corrected";
                assert config.get("user.operator").getAsString().equals("keep") : "Unmanaged fields must survive updates";
            }
            if (scenario.equals("file-drift")) {
                int starts = mock.starts;
                mock.files.put(Garden.VOLUME + "/service.conf", new IncusMock.StoredFile("wrong".getBytes(), "0", "0", "0600"));
                engine.reconcile(desired);
                assert mock.starts == starts + 1 : "Repaired mounted files must be activated even when desired content is unchanged";
                var file = mock.files.get(Garden.VOLUME + "/service.conf");
                assert new String(file.bytes()).equals("version=one\n") && file.uid().equals("1000") && file.mode().equals("0644") : "File content and metadata drift must be repaired";
            }
            if (scenario.equals("file-activation-failure")) {
                mock.files.put(Garden.VOLUME + "/service.conf", new IncusMock.StoredFile("drift".getBytes(), "1000", "1000", "0644"));
                mock.failStart = true;
                boolean rejected = false;
                try { engine.reconcile(desired); } catch (IOException e) { rejected = true; }
                assert rejected : "Failed activation after file repair must escape";
                assert !mock.resources.get(Garden.INSTANCE).getAsJsonObject("config").has("user.tend.activated") : "Repair must durably invalidate the old activation marker";
                var fresh = new Reconciler(client, new SecretStore(garden.state.resolve("secrets")), "garden", "test-controller");
                fresh.reconcile(desired);
                assert mock.running.get(Garden.INSTANCE) : "New controller must resume pending file activation";
            }
            if (scenario.equals("secret-restart")) {
                byte[] before = mock.files.get(Garden.VOLUME + "/session").bytes();
                var fresh = new Reconciler(client, new SecretStore(garden.state.resolve("secrets")), "garden", "test-controller");
                fresh.reconcile(desired);
                assert Arrays.equals(before, mock.files.get(Garden.VOLUME + "/session").bytes()) : "Controller restart must retain named secrets";
                assert Files.getPosixFilePermissions(garden.state.resolve("secrets/session")).equals(java.nio.file.attribute.PosixFilePermissions.fromString("rw-------")) : "Stored secret must remain private";
            }
            if (scenario.equals("etag-conflict")) {
                mock.drift(Garden.INSTANCE, "environment.DEMO", "drifted"); mock.conflict = true;
                boolean rejected = false;
                try { engine.reconcile(desired); } catch (IOException e) { rejected = true; }
                assert rejected : "ETag conflict must not be treated as success";
                engine.reconcile(desired);
                assert mock.running.get(Garden.INSTANCE) : "A later pass must recover after conditional update conflict";
            }
            if (scenario.equals("retain")) {
                int count = mock.mutations;
                garden.commit("<incus project=\"garden\"/>", "unused");
                engine.reconcile(reader.read(garden.git.fetchMain(), "incus.xml"));
                assert mock.mutations == count && mock.resources.containsKey(Garden.VOLUME) : "Removed declarations retain resources in the first slice";
            }
            if (scenario.equals("remove-key")) {
                garden.commit(Garden.xml().replace("<entry key=\"environment.DEMO\" value=\"one\"/>", ""), "version=one\n");
                engine.reconcile(reader.read(garden.git.fetchMain(), "incus.xml"));
                assert !mock.resources.get(Garden.INSTANCE).getAsJsonObject("config").has("environment.DEMO") : "Removed managed keys must be removed from Incus";
            }
            if (scenario.equals("stop")) {
                garden.commit(Garden.xml().replace("<instance name=\"demo\"", "<instance state=\"stopped\" name=\"demo\""), "version=one\n");
                engine.reconcile(reader.read(garden.git.fetchMain(), "incus.xml"));
                assert !mock.running.get(Garden.INSTANCE) : "Declared stopped state must converge";
            }
            if (scenario.equals("image-change")) {
                int count = mock.mutations;
                garden.commit(Garden.xml().replace(Garden.IMAGE, "b".repeat(64)), "changed");
                boolean rejected = false;
                try { engine.reconcile(reader.read(garden.git.fetchMain(), "incus.xml")); } catch (IOException e) { rejected = true; }
                assert rejected && mock.mutations == count : "Unsupported replacement must fail before changing mounted files";
            }
        }
    }
}
