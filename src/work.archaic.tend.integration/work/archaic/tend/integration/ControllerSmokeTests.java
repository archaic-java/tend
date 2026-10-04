package work.archaic.tend.integration;

import java.util.Collection;
import work.archaic.service.test.v02.*;

/** Actual OCI watch process, private remote TLS and retained controller identity. */
public record ControllerSmokeTests() implements TestSuite {
    public void cases(Collection<TestCase> cases) { cases.add(new OciWatchConvergesAndSurvivesReplacement()); }
}
record OciWatchConvergesAndSurvivesReplacement() implements TestCase {
    public void run(TestTrail trail) throws Exception {
        var commands = new IncusCommands("controller");
        try (var garden = new RealGarden(commands)) {
            var controller = new OciControllerFixture(commands, garden);
            String first = controller.publish("one");
            controller.bootstrap("create");
            controller.prepareGit();
            controller.start();
            assert controller.converged(first) : "The Incus OCI watch process must fetch Git main and create the application";
            assert controller.version().equals("one") : "Initial application configuration must come from the watched commit";
            assert controller.ociMarker().equals("true") : "The controller must use Incus native OCI execution";
            assert controller.runtimeIdentity().strip().equals("1000\n1000\n1000:1000:700\n1000:1000:400") : "The real Java process and mounted private files must have controller ownership";
            var privateState = controller.privateState();
            assert !privateState.get(0).isBlank() && privateState.get(3).startsWith("-----BEGIN PRIVATE KEY-----") : "The OCI process must generate both random and RSA signing identity";
            String started = controller.applicationStarted();
            Thread.sleep(3000);
            assert controller.applicationStarted().equals(started) : "Unchanged watch passes must not restart the application";
            assert controller.lastSuccess().equals(first) : "Unchanged watch must retain the observed revision";
            trail.note("Native OCI watch: UID 1000, private mounts, project-restricted HTTPS and bare Git main converged");

            String second = controller.publish("two");
            controller.copyGit();
            assert controller.converged(second) && controller.version().equals("two") : "Publishing main must change the application without a runner-launched Tend process";
            controller.stop(); controller.start();
            assert controller.converged(second) : "Controller must resume watch after stop/start";
            assert controller.privateState().equals(privateState) : "Stop/start must preserve secret bytes, signing identity and mounted values";
            controller.stop(); controller.deleteController();
            controller.bootstrap("reuse"); controller.attachGit(); controller.start();
            assert controller.converged(second) : "Replacement must retain last-success and resume convergence";
            assert controller.privateState().equals(privateState) : "Replacement must reuse retained controller/application credentials";
            trail.note("Controller restart and replacement retained private secret bytes, RSA identity and last-success");

            controller.stop(); controller.detachGit();
            String third = controller.publish("three"); controller.copyGit(); controller.drift();
            long failures = controller.failures(); controller.start();
            assert controller.failedAfter(failures) : "Unavailable Git must produce a bounded reported failure";
            assert controller.lastSuccess().equals(second) && controller.version().equals("drift") : "Failed fetch must not reconcile cached stale main or record a success";
            controller.stop(); controller.attachGit(); controller.start();
            assert controller.converged(third) && controller.version().equals("three") : "Git recovery must converge the newly published revision";

            controller.stop(); controller.trustStore(false);
            String fourth = controller.publish("four"); controller.copyGit();
            failures = controller.failures(); controller.start();
            assert controller.failedAfter(failures) : "Unrelated trust anchor must fail without TLS bypass";
            assert controller.lastSuccess().equals(third) && controller.version().equals("three") : "Bad trust must not deploy or advance last-success";
            controller.stop(); controller.trustStore(true); controller.start();
            assert controller.converged(fourth) && controller.version().equals("four") : "Restored trust must recover watch";

            controller.trust(false);
            String fifth = controller.publish("five"); controller.copyGit();
            failures = controller.failures();
            assert controller.failedAfter(failures) : "Revoked project authorization must produce a bounded API failure";
            assert controller.lastSuccess().equals(fourth) && controller.version().equals("four") : "Unauthorized API access must not deploy or advance success";
            controller.trust(true);
            assert controller.converged(fifth) && controller.version().equals("five") : "Reauthorization must recover the same running watch process";
            assert controller.privateState().equals(privateState) : "Recovery must preserve all generated identities";
            assert controller.secretsExcluded(privateState) : "Controller logs and uploaded command evidence must exclude secrets and TLS store passwords";
            trail.note("Git, trust and API authorization failures were bounded, withheld private material and recovered");
            controller.stop();
        }
    }
}
