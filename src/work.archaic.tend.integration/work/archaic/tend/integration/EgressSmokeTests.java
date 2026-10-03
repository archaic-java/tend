package work.archaic.tend.integration;

import java.io.IOException;
import java.nio.file.*;
import java.time.Duration;
import java.util.Collection;
import work.archaic.service.test.v02.*;

/** Real XML -> Git main -> Tend HTTPS -> OVN packet enforcement. */
public record EgressSmokeTests() implements TestSuite {
    public void cases(Collection<TestCase> cases) { cases.add(new GitEgressChangesPortsAndRepairsAclDrift()); }
}
record GitEgressChangesPortsAndRepairsAclDrift() implements TestCase {
    private static final String CLIENT = "tend-ci-policy-client";
    private static final String SERVER = "tend-ci-policy-server";
    private static final String ADDRESS = "10.77.1.21";
    public void run(TestTrail trail) throws Exception {
        var incus = new IncusCommands("egress");
        prepareServer(incus);
        try (var garden = new RealGarden(incus)) {
            garden.commitXml(xml(garden.fingerprint, ""), "Unrestricted baseline");
            garden.reconcile();
            incus.require("file", "push", "/bin/busybox", CLIENT + "/root/busybox", "--mode=0755");
            var baseline8080 = incus.reachable(CLIENT, ADDRESS, 8080);
            assert baseline8080.status() == 0 && baseline8080.output().contains("tend-ovn-smoke") : "8080 must work before Tend applies egress";
            var baseline8081 = incus.reachable(CLIENT, ADDRESS, 8081);
            assert baseline8081.status() == 0 && baseline8081.output().contains("tend-ovn-smoke") : "8081 must initially work to exclude unrelated network failures";

            String first = garden.commitXml(xml(garden.fingerprint, policy(8080)), "Allow only 8080");
            garden.reconcile();
            assert garden.lastSuccess().equals(first) : "Tend must activate the main commit declaring egress";
            var allowed8080 = incus.reachable(CLIENT, ADDRESS, 8080);
            assert allowed8080.status() == 0 && allowed8080.output().contains("tend-ovn-smoke") : "XML-declared 8080 must remain reachable: " + allowed8080.output();
            var blocked8081 = incus.rejected(CLIENT, ADDRESS, 8081);
            assert blocked8081.status() == 1 && (blocked8081.output().contains("Connection refused") || blocked8081.output().contains("timed out")) : "Undeclared 8081 must fail with a network refusal or timeout: " + blocked8081.output();
            String acl = output(incus, "config", "device", "get", CLIENT, "eth0", "security.acls").strip();
            assert acl.matches("tend-[a-f0-9]{32}") : "Tend must attach its generated ACL to the client NIC";
            assert output(incus, "network", "acl", "get", acl, "user.tend.owner").strip().equals("tend-ci-controller") : "Generated ACL must retain Tend ownership";
            Files.writeString(Path.of("out/incus-smoke/egress-acl.txt"), acl + "\n");
            trail.note("Git-declared egress allows 8080 and rejects 8081");

            String second = garden.commitXml(xml(garden.fingerprint, policy(8081)), "Allow only 8081");
            assert !second.equals(first) : "Port change must be published as a new main commit";
            garden.reconcile();
            assert garden.lastSuccess().equals(second) : "Tend must fetch and activate the updated main commit";
            var allowed8081 = incus.reachable(CLIENT, ADDRESS, 8081);
            assert allowed8081.status() == 0 && allowed8081.output().contains("tend-ovn-smoke") : "Updated XML must open 8081: " + allowed8081.output();
            var blocked8080 = incus.rejected(CLIENT, ADDRESS, 8080);
            assert blocked8080.status() == 1 && (blocked8080.output().contains("Connection refused") || blocked8080.output().contains("timed out")) : "Updated XML must close 8080: " + blocked8080.output();
            assert output(incus, "config", "device", "get", CLIENT, "eth0", "security.acls").strip().equals(acl) : "Rule revision must retain the named policy's ACL identity";

            incus.require("network", "acl", "rule", "add", acl, "egress", "action=allow", "state=enabled",
                    "destination=" + ADDRESS + "/32", "protocol=tcp", "destination_port=8080");
            var drift = incus.reachable(CLIENT, ADDRESS, 8080);
            assert drift.status() == 0 && drift.output().contains("tend-ovn-smoke") : "Fixture must prove ACL drift reopens the forbidden port";
            String started = garden.started(CLIENT);
            garden.reconcile();
            assert garden.lastSuccess().equals(second) : "Repair must use the same desired main revision";
            var repaired = incus.rejected(CLIENT, ADDRESS, 8080);
            assert repaired.status() == 1 && (repaired.output().contains("Connection refused") || repaired.output().contains("timed out")) : "Same Git revision must repair ACL drift and close 8080: " + repaired.output();
            var stillAllowed = incus.reachable(CLIENT, ADDRESS, 8081);
            assert stillAllowed.status() == 0 && stillAllowed.output().contains("tend-ovn-smoke") : "Repair must preserve the allowed port and a live server";
            assert garden.started(CLIENT).equals(started) : "ACL-only repair must not restart the client";
            trail.note("New main reversed port access; same main repaired independently introduced ACL drift");
            System.out.println("Egress smoke: Tend enforces XML, reverses allowed ports through Git and repairs ACL drift without restarting the client.");
        }
    }
    private static String output(IncusCommands incus, String... args) throws IOException, InterruptedException {
        var result = incus.run(Duration.ofSeconds(90), args);
        if (result.status() != 0) throw new IOException("Incus observation failed: " + result.output());
        return result.output();
    }
    private static void prepareServer(IncusCommands incus) throws IOException, InterruptedException {
        incus.require("init", "tend-ci-alpine", SERVER, "--no-profiles", "--storage", "tend-ci-pool");
        incus.require("config", "device", "add", SERVER, "eth0", "nic", "network=tend-ci-ovn", "name=eth0", "ipv4.address=" + ADDRESS);
        incus.require("start", SERVER);
        incus.require("file", "push", "/bin/busybox", SERVER + "/root/busybox", "--mode=0755");
        Path response = Path.of("out/incus-smoke/egress/index.html"); Files.writeString(response, "tend-ovn-smoke\n");
        incus.require("file", "push", response.toString(), SERVER + "/root/index.html");
        for (int port : new int[]{8080, 8081})
            incus.require("exec", SERVER, "--", "/root/busybox", "httpd", "-p", Integer.toString(port), "-h", "/root");
    }
    private static String policy(int port) {
        return """
                  <egress name="tend-ci-http" instance="tend-ci-policy-client" device="eth0">
                    <allow address="10.77.1.21/32" protocol="tcp" port="%d"/>
                  </egress>
                """.formatted(port);
    }
    private static String xml(String fingerprint, String policy) {
        return """
                <incus project="default">
                  <instance name="tend-ci-policy-client" fingerprint="%s">
                    <device name="root" type="disk"><config>
                      <entry key="path" value="/"/><entry key="pool" value="tend-ci-pool"/>
                    </config></device>
                    <device name="eth0" type="nic"><config>
                      <entry key="network" value="tend-ci-ovn"/><entry key="name" value="eth0"/>
                      <entry key="ipv4.address" value="10.77.1.20"/>
                    </config></device>
                  </instance>
                %s</incus>
                """.formatted(fingerprint, policy);
    }
}
