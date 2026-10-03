package work.archaic.tend.integration;

import java.nio.file.*;
import java.util.Collection;
import work.archaic.service.test.v02.*;

/** Real password login and session authorization through Tend-generated Caddy/Authelia policies. */
public record AuthorizationSmokeTests() implements TestSuite {
    public void cases(Collection<TestCase> cases) { cases.add(new LoginGroupsGitPolicyAndDriftRepair()); }
}
record LoginGroupsGitPolicyAndDriftRepair() implements TestCase {
    public void run(TestTrail trail) throws Exception {
        var incus = new IncusCommands("authorization");
        try (var garden = new RealGarden(incus)) {
            var fixture = new AuthorizationFixture(incus, garden);
            garden.source("authelia.json", fixture.baseConfiguration());
            String first = garden.commitXml(fixture.xml("gardeners"), "Permit gardeners through real Authelia");
            garden.reconcile();
            assert garden.lastSuccess().equals(first) : "Tend must activate the protected ingress main commit";
            fixture.prepare();
            String requests = fixture.requests();
            var anonymous = fixture.request(null);
            assert anonymous.status() == 0 && anonymous.output().contains("status=302") && anonymous.output().contains("auth.garden.internal") : "Anonymous request must redirect to the real login portal: " + anonymous.output();
            assert fixture.requests().equals(requests) : "Anonymous request must never reach the backend";
            var aliceLogin = fixture.login("alice");
            assert aliceLogin.status() == 0 && aliceLogin.output().strip().equals("200") : "Gardener must authenticate through the real first-factor endpoint";
            var bobLogin = fixture.login("bob");
            assert bobLogin.status() == 0 && bobLogin.output().strip().equals("200") : "Outside-group user must authenticate before testing authorization";
            var carolLogin = fixture.login("carol");
            assert carolLogin.status() == 0 && carolLogin.output().strip().equals("200") : "Admin fixture user must authenticate";
            var alice = fixture.request("alice");
            assert alice.status() == 0 && alice.output().contains("status=200") && alice.output().contains("protected-backend") : "Permitted user must reach the backend";
            assert alice.output().contains("user=alice") && alice.output().contains("groups=gardeners") && alice.output().contains("email=alice@example.invalid") && alice.output().contains("name=Alice") && !alice.output().contains("forged") : "Forwarded identity must come from Authelia, replacing forged client headers: " + alice.output();
            requests = fixture.requests();
            var bob = fixture.request("bob");
            assert bob.status() == 0 && bob.output().contains("status=403") : "Authenticated outside-group user must be forbidden: " + bob.output();
            assert fixture.requests().equals(requests) : "Forbidden user must never reach the backend";
            var carol = fixture.request("carol");
            assert carol.status() == 0 && carol.output().contains("status=403") : "Admin must not receive an implicit bypass of the declared gardeners group";
            assert fixture.requests().equals(requests) : "Admin denial must never reach the backend";
            String authStarted = garden.started(AuthorizationFixture.AUTH);
            String gatewayStarted = garden.started(AuthorizationFixture.GATEWAY);
            garden.reconcile();
            assert garden.started(AuthorizationFixture.AUTH).equals(authStarted) && garden.started(AuthorizationFixture.GATEWAY).equals(gatewayStarted) : "Unchanged protected ingress must not restart either service";
            assert fixture.request("alice").output().contains("protected-backend") : "No-op reconciliation must preserve the authenticated session";
            trail.note("Real login succeeds; generated policy admits gardeners, denies other users and strips forged identity");

            String second = garden.commitXml(fixture.xml("admins"), "Change permitted group to admins");
            assert !second.equals(first) : "Group change must be published as a distinct main commit";
            garden.reconcile();
            assert garden.lastSuccess().equals(second) : "Tend must activate the new group policy revision";
            fixture.ready();
            for (String user : new String[]{"alice", "bob", "carol"}) {
                var login = fixture.login(user);
                assert login.status() == 0 && login.output().strip().equals("200") : "User must log in again after authorization service activation";
            }
            requests = fixture.requests();
            alice = fixture.request("alice");
            assert alice.status() == 0 && alice.output().contains("status=403") : "New main must revoke gardeners access";
            bob = fixture.request("bob");
            assert bob.status() == 0 && bob.output().contains("status=403") : "New main must continue denying observers";
            assert fixture.requests().equals(requests) : "New-policy denials must not reach the backend";
            carol = fixture.request("carol");
            assert carol.status() == 0 && carol.output().contains("user=carol") && carol.output().contains("status=200") : "New main must admit admins";

            String generated = fixture.policy();
            assert generated.contains("group:admins") : "Mounted policy must reflect the new Git declaration";
            fixture.drift(generated.replace("group:admins", "group:observers"));
            fixture.ready();
            var driftLogin = fixture.login("bob");
            assert driftLogin.status() == 0 && driftLogin.output().strip().equals("200") : "Observer must authenticate after drift fixture restart";
            bob = fixture.request("bob");
            assert bob.status() == 0 && bob.output().contains("user=bob") && bob.output().contains("status=200") : "Fixture must prove policy drift grants live undeclared access";
            garden.reconcile();
            assert garden.lastSuccess().equals(second) : "Repair must use the same desired Git revision";
            fixture.ready();
            for (String user : new String[]{"bob", "carol"}) {
                var login = fixture.login(user);
                assert login.status() == 0 && login.output().strip().equals("200") : "User must authenticate after drift repair activation";
            }
            requests = fixture.requests();
            bob = fixture.request("bob");
            assert bob.status() == 0 && bob.output().contains("status=403") : "Same main must revoke drift-granted observer access";
            assert fixture.requests().equals(requests) : "Repaired denial must not reach backend";
            carol = fixture.request("carol");
            assert carol.status() == 0 && carol.output().contains("status=200") && carol.output().contains("user=carol") : "Repair must preserve declared admin access";
            assert fixture.policy().equals(generated) : "Repair must restore generated access-control bytes";
            authStarted = garden.started(AuthorizationFixture.AUTH);
            gatewayStarted = garden.started(AuthorizationFixture.GATEWAY);
            garden.reconcile();
            assert garden.started(AuthorizationFixture.AUTH).equals(authStarted) && garden.started(AuthorizationFixture.GATEWAY).equals(gatewayStarted) : "Repaired authorization must settle without further restarts";
            System.out.println("Authorization smoke: real login enforces groups, replaces forged identity, activates Git policy changes and repairs live authorization drift.");
        }
    }
}
