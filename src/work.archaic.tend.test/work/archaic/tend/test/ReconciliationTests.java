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
        cases.add(new Idempotence());
        cases.add(new ConfigUpdate());
        cases.add(new Drift());
        cases.add(new FileDrift());
        cases.add(new FileMetadataRepairRecovery());
        cases.add(new FileActivationFailure());
        cases.add(new SecretRestart());
        cases.add(new EtagConflict());
        cases.add(new Retain());
        cases.add(new RemoveKey());
        cases.add(new Stop());
        cases.add(new ImageChange());
        cases.add(new Create());
        cases.add(new PartialFailure());
        cases.add(new StartFailure());
        cases.add(new UncertainVolume());
        cases.add(new OperationTimeout());
        cases.add(new Unmanaged());
        cases.add(new Scope());
    }
}
record Idempotence() implements TestCase {
    public void run(TestTrail trail) throws Exception {
        try (var f = new DeploymentFixture()) {
            f.deploy();
            int count = f.mock.mutations; f.engine.reconcile(f.desired);
            assert f.mock.mutations == count : "Second pass must make no writes or restarts";

        }
    }
}
record ConfigUpdate() implements TestCase {
    public void run(TestTrail trail) throws Exception {
        try (var f = new DeploymentFixture()) {
            f.deploy();
            int starts = f.mock.starts;
            f.garden.commit(Garden.xml(), "version=two\n");
            f.engine.reconcile(f.reader.read(f.garden.git.fetchMain(), "incus.xml"));
            assert f.mock.starts == starts + 1 : "Changed configuration must activate exactly once";
            int count = f.mock.mutations; f.engine.reconcile(f.reader.read(f.garden.git.fetchMain(), "incus.xml"));
            assert f.mock.mutations == count : "Activated configuration must settle";

        }
    }
}
record Drift() implements TestCase {
    public void run(TestTrail trail) throws Exception {
        try (var f = new DeploymentFixture()) {
            f.deploy();
            f.mock.drift(Garden.INSTANCE, "environment.DEMO", "drifted");
            f.mock.drift(Garden.INSTANCE, "user.operator", "keep");
            f.engine.reconcile(f.desired);
            var config = f.mock.resources.get(Garden.INSTANCE).getAsJsonObject("config");
            assert config.get("environment.DEMO").getAsString().equals("one") : "Owned configuration drift must be corrected";
            assert config.get("user.operator").getAsString().equals("keep") : "Unmanaged fields must survive updates";

        }
    }
}
record FileDrift() implements TestCase {
    public void run(TestTrail trail) throws Exception {
        try (var f = new DeploymentFixture()) {
            f.deploy();
            int starts = f.mock.starts;
            f.mock.files.put(Garden.VOLUME + "/service.conf", new IncusMock.StoredFile("wrong".getBytes(), "0", "0", "0600"));
            f.engine.reconcile(f.desired);
            assert f.mock.starts == starts + 1 : "Repaired mounted files must be activated even when desired content is unchanged";
            var file = f.mock.files.get(Garden.VOLUME + "/service.conf");
            assert new String(file.bytes()).equals("version=one\n") && file.uid().equals("1000") && file.mode().equals("0644") : "File content and metadata drift must be repaired";

        }
    }
}
record FileActivationFailure() implements TestCase {
    public void run(TestTrail trail) throws Exception {
        try (var f = new DeploymentFixture()) {
            f.deploy();
            f.mock.files.put(Garden.VOLUME + "/service.conf", new IncusMock.StoredFile("drift".getBytes(), "1000", "1000", "0644"));
            f.mock.failStart = true;
            boolean rejected = false;
            try { f.engine.reconcile(f.desired); } catch (IOException e) { rejected = true; }
            assert rejected : "Failed activation after file repair must escape";
            assert !f.mock.resources.get(Garden.INSTANCE).getAsJsonObject("config").has("user.tend.activated") : "Repair must durably invalidate the old activation marker";
            var fresh = new Reconciler(f.client, new SecretStore(f.garden.state.resolve("secrets")), "garden", "test-controller");
            fresh.reconcile(f.desired);
            assert f.mock.running.get(Garden.INSTANCE) : "New controller must resume pending file activation";

        }
    }
}
record SecretRestart() implements TestCase {
    public void run(TestTrail trail) throws Exception {
        try (var f = new DeploymentFixture()) {
            f.deploy();
            byte[] before = f.mock.files.get(Garden.VOLUME + "/session").bytes();
            var fresh = new Reconciler(f.client, new SecretStore(f.garden.state.resolve("secrets")), "garden", "test-controller");
            fresh.reconcile(f.desired);
            assert Arrays.equals(before, f.mock.files.get(Garden.VOLUME + "/session").bytes()) : "Controller restart must retain named secrets";
            assert Files.getPosixFilePermissions(f.garden.state.resolve("secrets/session")).equals(java.nio.file.attribute.PosixFilePermissions.fromString("rw-------")) : "Stored secret must remain private";

        }
    }
}
record EtagConflict() implements TestCase {
    public void run(TestTrail trail) throws Exception {
        try (var f = new DeploymentFixture()) {
            f.deploy();
            f.mock.drift(Garden.INSTANCE, "environment.DEMO", "drifted"); f.mock.conflict = true;
            boolean rejected = false;
            try { f.engine.reconcile(f.desired); } catch (IOException e) { rejected = true; }
            assert rejected : "ETag conflict must not be treated as success";
            f.engine.reconcile(f.desired);
            assert f.mock.running.get(Garden.INSTANCE) : "A later pass must recover after conditional update conflict";

        }
    }
}
record Retain() implements TestCase {
    public void run(TestTrail trail) throws Exception {
        try (var f = new DeploymentFixture()) {
            f.deploy();
            int count = f.mock.mutations;
            f.garden.commit("<incus project=\"garden\"/>", "unused");
            f.engine.reconcile(f.reader.read(f.garden.git.fetchMain(), "incus.xml"));
            assert f.mock.mutations == count && f.mock.resources.containsKey(Garden.VOLUME) : "Removed declarations retain resources in the first slice";

        }
    }
}
record RemoveKey() implements TestCase {
    public void run(TestTrail trail) throws Exception {
        try (var f = new DeploymentFixture()) {
            f.deploy();
            f.garden.commit(Garden.xml().replace("<entry key=\"environment.DEMO\" value=\"one\"/>", ""), "version=one\n");
            f.engine.reconcile(f.reader.read(f.garden.git.fetchMain(), "incus.xml"));
            assert !f.mock.resources.get(Garden.INSTANCE).getAsJsonObject("config").has("environment.DEMO") : "Removed managed keys must be removed from Incus";

        }
    }
}
record Stop() implements TestCase {
    public void run(TestTrail trail) throws Exception {
        try (var f = new DeploymentFixture()) {
            f.deploy();
            f.garden.commit(Garden.xml().replace("<instance name=\"demo\"", "<instance state=\"stopped\" name=\"demo\""), "version=one\n");
            f.engine.reconcile(f.reader.read(f.garden.git.fetchMain(), "incus.xml"));
            assert !f.mock.running.get(Garden.INSTANCE) : "Declared stopped state must converge";

        }
    }
}
record ImageChange() implements TestCase {
    public void run(TestTrail trail) throws Exception {
        try (var f = new DeploymentFixture()) {
            f.deploy();
            int count = f.mock.mutations;
            f.garden.commit(Garden.xml().replace(Garden.IMAGE, "b".repeat(64)), "changed");
            boolean rejected = false;
            try { f.engine.reconcile(f.reader.read(f.garden.git.fetchMain(), "incus.xml")); } catch (IOException e) { rejected = true; }
            assert rejected && f.mock.mutations == count : "Unsupported replacement must fail before changing mounted files";

        }
    }
}
record Create() implements TestCase {
    public void run(TestTrail trail) throws Exception {
        try (var f = new DeploymentFixture()) {
            f.deploy();
            assert f.mock.resources.containsKey(Garden.VOLUME) : "Persistent volume must be created";
            assert f.mock.running.get(Garden.INSTANCE) : "Declared running instance must start";
            assert f.mock.resources.get(Garden.INSTANCE).getAsJsonObject("config").get("volatile.test").getAsString().equals("preserve") : "Activation must preserve Incus volatile configuration";
            assert f.mock.files.get(Garden.VOLUME + "/session").mode().equals("0400") : "Secret permissions must follow declaration";
            assert f.mock.operations.values().stream().allMatch(op -> op.action() == null || op.failed()) : "Client must await asynchronous operations";
        }
    }
}
record PartialFailure() implements TestCase {
    public void run(TestTrail trail) throws Exception {
        try (var f = new DeploymentFixture()) {
            f.mock.failurePath = "/1.0/instances";
            boolean failed = false;
            try { f.deploy(); } catch (IOException e) { failed = true; trail.note(e.getMessage()); }
            assert failed : "Injected failure must escape reconciliation";
            f.deploy();
            assert f.mock.running.get(Garden.INSTANCE) : "Next pass must recover the incomplete deployment";
        }
    }
}
record StartFailure() implements TestCase {
    public void run(TestTrail trail) throws Exception {
        try (var f = new DeploymentFixture()) {
            f.mock.failStart = true;
            boolean failed = false;
            try { f.deploy(); } catch (IOException e) { failed = true; trail.note(e.getMessage()); }
            assert failed : "Injected failure must escape reconciliation";
            assert !f.mock.resources.get(Garden.INSTANCE).getAsJsonObject("config").has("user.tend.activated") : "Failed start must leave activation pending";
            f.deploy();
            assert f.mock.running.get(Garden.INSTANCE) : "Next pass must recover the incomplete deployment";
        }
    }
}
record UncertainVolume() implements TestCase {
    public void run(TestTrail trail) throws Exception {
        try (var f = new DeploymentFixture()) {
            f.mock.loseResponse = true;
            boolean failed = false;
            try { f.deploy(); } catch (IOException e) { failed = true; trail.note(e.getMessage()); }
            assert failed : "Injected failure must escape reconciliation";
            f.deploy();
            assert f.mock.running.get(Garden.INSTANCE) : "Next pass must recover the incomplete deployment";
        }
    }
}
record OperationTimeout() implements TestCase {
    public void run(TestTrail trail) throws Exception {
        try (var f = new DeploymentFixture(Duration.ofMillis(200))) {
            f.mock.stall = true;
            boolean failed = false;
            try { f.deploy(); } catch (IOException e) { failed = true; trail.note(e.getMessage()); }
            assert failed : "Injected failure must escape reconciliation";
        }
    }
}
record Unmanaged() implements TestCase {
    public void run(TestTrail trail) throws Exception {
        try (var f = new DeploymentFixture()) {
            JsonObject existing = new JsonObject(); existing.add("config", new JsonObject());
            f.mock.seed(Garden.VOLUME, existing);
            boolean rejected = false;
            try { f.deploy(); } catch (IOException e) { rejected = true; }
            assert rejected : "Unowned resources must not be adopted implicitly";
            assert f.mock.mutations == 0 : "Ownership preflight must precede mutation";
        }
    }
}
record Scope() implements TestCase {
    public void run(TestTrail trail) throws Exception {
        try (var f = new DeploymentFixture()) {
            var other = new Reconciler(f.client, f.store, "other", "test-controller");
            boolean rejected = false;
            try { other.reconcile(f.desired); } catch (IOException e) { rejected = true; }
            assert rejected && f.mock.mutations == 0 : "Desired project cannot escape controller scope";
        }
    }
}

record FileMetadataRepairRecovery() implements TestCase {
    public void run(TestTrail trail) throws Exception {
        try (var f = new DeploymentFixture()) {
            f.deploy();
            String path = Garden.VOLUME + "/service.conf";
            f.mock.files.put(path, new IncusMock.StoredFile("version=one\n".getBytes(), "0", "0", "0600"));
            f.mock.failFileWrite = true;
            boolean failed = false;
            try { f.engine.reconcile(f.desired); } catch (IOException expected) { failed = true; }
            assert failed : "Failed recreation must remain a failed reconciliation";
            assert !f.mock.files.containsKey(path) : "Fixture must exercise interruption after deletion";
            assert !f.mock.resources.get(Garden.INSTANCE).getAsJsonObject("config").has("user.tend.activated") : "Missing managed file must leave its consumer activation pending";
            var fresh = new Reconciler(f.client, new SecretStore(f.garden.state.resolve("secrets")), "garden", "test-controller");
            fresh.reconcile(f.desired);
            var file = f.mock.files.get(path);
            assert new String(file.bytes()).equals("version=one\n") && file.uid().equals("1000") && file.gid().equals("1000") && file.mode().equals("0644") : "Next controller pass must recreate the missing file with desired bytes and metadata";
            assert f.mock.running.get(Garden.INSTANCE) : "Recovered consumer must return to its running desired state";
            int mutations = f.mock.mutations; fresh.reconcile(f.desired);
            assert mutations == f.mock.mutations : "Recovery must finish in an idempotent state";
        }
    }
}
