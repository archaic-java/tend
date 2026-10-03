package work.archaic.tend.integration;

import java.nio.file.*;
import java.util.Collection;
import work.archaic.service.test.v02.*;

/** Black-box CLI test: real Git, authenticated HTTPS and real Incus resources. */
public record ReconciliationSmokeTests() implements TestSuite {
    public void cases(Collection<TestCase> cases) { cases.add(new MainConvergesRepairsDriftAndDeploysNewRevision()); }
}
record MainConvergesRepairsDriftAndDeploysNewRevision() implements TestCase {
    public void run(TestTrail trail) throws Exception {
        var incus = new IncusCommands("reconciliation");
        try (var garden = new RealGarden(incus)) {
            String first = garden.commit("one");
            garden.reconcile();
            assert garden.lastSuccess().equals(first) : "Successful deployment must record the fetched main commit";
            assert garden.config("user.tend.owner").equals("tend-ci-controller") : "Created instance must carry controller ownership";
            assert garden.volumeOwner().equals("tend-ci-controller") : "Created volume must carry controller ownership";
            assert garden.config("environment.DEMO").equals("one") : "Instance must receive Git configuration";
            assert garden.file().equals("version=one\n") : "Mounted file must contain Git bytes";
            assert garden.permissions().equals("1000:1000:640") : "Mounted file must have declared UID, GID and mode";
            garden.recordObservedState();
            String started = garden.started();
            garden.reconcile();
            garden.recordObservedState();
            assert garden.started().equals(started) : "An unchanged second pass must not restart the instance";
            trail.note("Initial main converged; unchanged pass did not restart the container");

            incus.require("config", "set", "tend-ci-managed", "environment.DEMO=drift", "user.operator.note=preserve");
            Path wrong = garden.directory.resolve("wrong.conf"); Files.writeString(wrong, "drift\n");
            incus.require("file", "push", wrong.toString(), "tend-ci-managed/data/service.conf", "--uid=0", "--gid=0", "--mode=0600");
            incus.require("exec", "tend-ci-managed", "--", "chown", "0:0", "/data/service.conf");
            incus.require("exec", "tend-ci-managed", "--", "chmod", "0600", "/data/service.conf");
            assert garden.file().equals("drift\n") : "Fixture must establish actual file drift before testing repair";
            assert garden.permissions().equals("0:0:600") : "Fixture must establish ownership and mode drift";
            garden.reconcile();
            assert garden.config("environment.DEMO").equals("one") : "Same Git commit must repair instance drift";
            assert garden.config("user.operator.note").equals("preserve") : "Repair must preserve unrelated operator configuration";
            assert garden.file().equals("version=one\n") : "Same Git commit must repair mounted file bytes";
            assert garden.permissions().equals("1000:1000:640") : "Repair must restore file ownership and permissions";
            assert garden.lastSuccess().equals(first) : "Drift repair must not invent a new Git revision";
            trail.note("Same main commit repaired configuration, file bytes and permissions");

            String second = garden.commit("two");
            assert !second.equals(first) : "Fixture must publish a distinct main revision";
            garden.reconcile();
            assert garden.lastSuccess().equals(second) : "Next pass must fetch and record the new main commit";
            assert garden.config("environment.DEMO").equals("two") : "New main must update instance configuration";
            assert garden.file().equals("version=two\n") : "New main must update the mounted file";
            assert garden.permissions().equals("1000:1000:640") : "New revision must retain declared file permissions";
            started = garden.started();
            garden.reconcile();
            assert garden.started().equals(started) : "Converged new revision must also be idempotent";
            System.out.println("Reconciliation smoke: authenticated CLI converges main, avoids no-op restart, repairs drift and deploys next main.");
        }
    }
}
