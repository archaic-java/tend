package work.archaic.tend.integration;

import java.nio.file.*;
import java.util.Collection;
import work.archaic.service.test.v02.*;

/** First real-host test: prove that the runner can enforce a NIC-scoped OVN ACL. */
public record OvnSmokeTests() implements TestSuite {
    public void cases(Collection<TestCase> cases) { cases.add(new EgressAllowsOnePortAndRejectsAnother()); }
}
record EgressAllowsOnePortAndRejectsAnother() implements TestCase {
    public void run(TestTrail trail) throws Exception {
        var incus = new IncusCommands();
        prepareInstance(incus, "tend-ci-server", "10.77.1.11");
        prepareInstance(incus, "tend-ci-client", "10.77.1.10");
        Path response = Path.of("out/incus-smoke/index.html"); Files.writeString(response, "tend-ovn-smoke\n");
        incus.require("file", "push", response.toString(), "tend-ci-server/root/index.html");
        incus.require("exec", "tend-ci-server", "--", "/root/busybox", "httpd", "-p", "8080", "-h", "/root");
        incus.require("exec", "tend-ci-server", "--", "/root/busybox", "httpd", "-p", "8081", "-h", "/root");
        var baselineAllowed = incus.reachable(8080);
        assert baselineAllowed.status() == 0 && baselineAllowed.output().contains("tend-ovn-smoke") : "Allowed port must work before filtering";
        var baselineBlocked = incus.reachable(8081);
        assert baselineBlocked.status() == 0 && baselineBlocked.output().contains("tend-ovn-smoke") : "Blocked port must initially be reachable, preventing false positives";
        trail.note("Both server ports reachable before attaching ACL");
        applyPolicy(incus);
        var allowed = incus.reachable(8080);
        assert allowed.status() == 0 && allowed.output().contains("tend-ovn-smoke") : "Declared destination port must remain reachable: " + allowed.output();
        var blocked = incus.rejected(8081);
        assert blocked.status() == 1 && (blocked.output().contains("Connection refused") || blocked.output().contains("timed out")) : "Undeclared port must fail with a network rejection or timeout, not an exec error";
        trail.note("8080 allowed, 8081 rejected with ACL attached");
        var stillAllowed = incus.request(8080);
        assert stillAllowed.status() == 0 && stillAllowed.output().contains("tend-ovn-smoke") : "Server must remain available during the negative test";
        incus.require("config", "device", "unset", "tend-ci-client", "eth0", "security.acls");
        var restored = incus.reachable(8081);
        assert restored.status() == 0 && restored.output().contains("tend-ovn-smoke") : "Removing the ACL must restore access to prove filtering caused the failure";
        System.out.println("OVN smoke: both ports reachable; ACL allows 8080 and rejects 8081; detaching ACL restores 8081.");
    }
    private static void prepareInstance(IncusCommands incus, String name, String address) throws java.io.IOException, InterruptedException {
        incus.require("init", "tend-ci-alpine", name, "--no-profiles", "--storage", "tend-ci-pool");
        incus.require("config", "device", "add", name, "eth0", "nic", "network=tend-ci-ovn", "name=eth0", "ipv4.address=" + address,
                "security.acls.default.egress.action=reject", "security.acls.default.ingress.action=allow");
        incus.require("start", name);
        incus.require("file", "push", "/bin/busybox", name + "/root/busybox", "--mode=0755");
    }
    private static void applyPolicy(IncusCommands incus) throws java.io.IOException, InterruptedException {
        incus.require("network", "acl", "create", "tend-ci-egress");
        incus.require("network", "acl", "rule", "add", "tend-ci-egress", "egress", "action=allow", "state=enabled",
                "destination=10.77.1.11/32", "protocol=tcp", "destination_port=8080");
        incus.require("config", "device", "set", "tend-ci-client", "eth0", "security.acls=tend-ci-egress");
    }
}
