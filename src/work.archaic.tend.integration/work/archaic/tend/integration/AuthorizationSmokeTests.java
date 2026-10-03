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
            try (var browser = new PasskeyBrowser(incus, garden.directory)) {
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

                garden.source("authelia.json", fixture.passkeyConfiguration());
                String passkeyRevision = garden.commitXml(fixture.xml("admins"), "Enable one-factor password or passkey login");
                garden.reconcile(); fixture.ready();
                assert garden.lastSuccess().equals(passkeyRevision) : "Tend must activate passkey settings from Git";
                browser.authenticator();
                assert browser.password("bob") == 200 : "Outside-group user must password-authenticate for enrollment";
                assert browser.startElevation() == 200 : "Enrollment must send a real identity-verification code";
                assert browser.finishElevation() == 200 : "Enrollment must verify the private notifier code";
                var enrolled = browser.register();
                assert enrolled.get("options").getAsInt() == 200 && enrolled.get("stored").getAsInt() == 201 && enrolled.get("resident").getAsBoolean() : "Authelia must store a discoverable passkey through a real browser ceremony";
                browser.clearSession();
                var login = browser.login("valid");
                assert login.get("status").getAsInt() == 200 && login.get("ok").getAsBoolean() : "Outside-group user must authenticate with only a passkey";
                requests = fixture.requests();
                var denied = browser.request(incus, fixture);
                assert denied.status() == 0 && denied.output().contains("status=403") : "Passkey login must not bypass group authorization";
                assert fixture.requests().equals(requests) : "Outside-group passkey session must never reach the backend";

                browser.clearSession(); browser.authenticator();
                assert browser.password("carol") == 200 : "Permitted user must password-authenticate for enrollment";
                assert browser.startElevation() == 200 : "Permitted enrollment must request identity verification";
                assert browser.finishElevation() == 200 : "Permitted enrollment must verify the delivered code";
                enrolled = browser.register();
                assert enrolled.get("stored").getAsInt() == 201 && enrolled.get("resident").getAsBoolean() : "Permitted user must enroll a discoverable passkey";
                browser.clearSession();
                login = browser.login("unknown");
                assert login.get("stage").getAsString().equals("server") && login.get("status").getAsInt() == 403 : "Authelia must reject an assertion naming an unknown credential";
                requests = fixture.requests();
                denied = browser.request(incus, fixture);
                assert denied.output().contains("status=302") && fixture.requests().equals(requests) : "Unknown credential must leave the client anonymous with no backend access";

                browser.clearSession(); browser.verified(false);
                login = browser.login("unverified");
                assert login.get("stage").getAsString().equals("server") && login.get("status").getAsInt() == 403 : "Authelia must reject a signed assertion without required user verification";
                denied = browser.request(incus, fixture);
                assert denied.output().contains("status=302") && fixture.requests().equals(requests) : "Missing user verification must not create an authorized session";
                browser.clearSession(); browser.verified(true);
                login = browser.login("valid");
                assert login.get("status").getAsInt() == 200 && login.get("ok").getAsBoolean() : "Fresh anonymous session must authenticate using only its enrolled passkey";
                var permitted = browser.request(incus, fixture);
                assert permitted.status() == 0 && permitted.output().contains("status=200") && permitted.output().contains("user=carol") && permitted.output().contains("groups=admins") && permitted.output().contains("email=carol@example.invalid") && permitted.output().contains("name=Carol") && !permitted.output().contains("forged") : "Passkey session must forward the real authorized identity";
                authStarted = garden.started(AuthorizationFixture.AUTH);
                gatewayStarted = garden.started(AuthorizationFixture.GATEWAY);
                garden.reconcile();
                assert garden.started(AuthorizationFixture.AUTH).equals(authStarted) && garden.started(AuthorizationFixture.GATEWAY).equals(gatewayStarted) : "No-op reconciliation must not restart passkey services";
                assert browser.request(incus, fixture).output().contains("status=200") : "No-op reconciliation must preserve the passkey session";

                incus.require("restart", AuthorizationFixture.AUTH); fixture.ready(); browser.clearSession();
                login = browser.login("valid");
                assert login.get("status").getAsInt() == 200 && login.get("ok").getAsBoolean() : "Stored passkey must survive an Authelia restart";
                assert browser.request(incus, fixture).output().contains("user=carol") : "Restart must preserve authorized passkey identity";
                authStarted = garden.started(AuthorizationFixture.AUTH);
                garden.source("authelia.json", fixture.passkeyConfiguration().replace("\"info\"", "\"warn\""));
                String updated = garden.commitXml(fixture.xml("admins"), "Change managed Authelia configuration after passkey enrollment");
                garden.reconcile(); fixture.ready(); browser.clearSession();
                assert garden.lastSuccess().equals(updated) : "Tend must activate the next passkey configuration revision";
                assert !garden.started(AuthorizationFixture.AUTH).equals(authStarted) : "Changed Git configuration must actually restart Authelia before checking credential persistence";
                login = browser.login("valid");
                assert login.get("status").getAsInt() == 200 && login.get("ok").getAsBoolean() : "Stored passkey must survive Git-driven configuration activation";
                assert browser.request(incus, fixture).output().contains("status=200") : "Git activation must preserve permitted passkey access";
                var password = fixture.login("carol");
                assert password.status() == 0 && password.output().strip().equals("200") : "Password login must remain supported alongside passkeys";
                assert fixture.request("carol").output().contains("status=200") : "One-factor ingress must accept either supported login method";
                trail.note("Browser passkey enrollment, passwordless login, group decisions, negative assertions and credential persistence verified");
                System.out.println("Passkey smoke: real WebAuthn enrollment and passwordless login enforce groups and user verification; credentials survive restart and Git activation.");
            }

        }
    }
}
