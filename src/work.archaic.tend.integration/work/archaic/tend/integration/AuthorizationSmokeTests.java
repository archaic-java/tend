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

                garden.source("oidc.yml", fixture.oidcConfiguration());
                String oidcRevision = garden.commitXml(fixture.oidcXml(), "Enable confidential OIDC client with generated credentials");
                garden.reconcile(); fixture.ready(); browser.clearSession();
                assert garden.lastSuccess().equals(oidcRevision) : "Tend must activate OIDC configuration and mounted generated credentials";
                try (var oidc = new OidcFixture(garden.directory)) {
                    var discovery = oidc.discovery();
                    assert discovery.status == 200 && discovery.body.get("issuer").getAsString().equals(OidcFixture.ISSUER) : "Real HTTPS discovery must advertise the expected issuer";
                    assert discovery.body.get("authorization_endpoint").getAsString().equals(OidcFixture.ISSUER + "/api/oidc/authorization")
                            && discovery.body.get("token_endpoint").getAsString().equals(OidcFixture.ISSUER + "/api/oidc/token")
                            && discovery.body.get("jwks_uri").getAsString().equals(OidcFixture.ISSUER + "/jwks.json") : "Discovery must bind endpoints to the trusted issuer";
                    var keys = oidc.keys();
                    assert keys.status == 200 && keys.body.getAsJsonArray("keys").size() == 1 : "Provider must publish Tend's generated signing key";
                    var flow = OidcFixture.flow();
                    var authorization = oidc.authorize(flow, OidcFixture.CALLBACK);
                    assert authorization.status == 303 && authorization.location.getAuthority().equals("auth.garden.internal")
                            && !OidcFixture.query(authorization.location).containsKey("code") : "Anonymous OIDC request must require authentication without issuing a code";
                    assert browser.password("bob") == 200 : "Outside-group user must authenticate before testing OIDC policy";
                    oidc.session(browser.sessionCookies());
                    flow = OidcFixture.flow(); authorization = oidc.authorize(flow, OidcFixture.CALLBACK);
                    var parameters = OidcFixture.query(authorization.location);
                    assert authorization.status == 303 && parameters.getOrDefault("error", "").equals("access_denied")
                            && flow.state().equals(parameters.get("state")) && !parameters.containsKey("code") : "OIDC client policy must deny authenticated observers without issuing a code";
                    browser.clearSession(); login = browser.login("valid");
                    assert login.get("status").getAsInt() == 200 && login.get("ok").getAsBoolean() : "Permitted user must authenticate OIDC session with only the enrolled passkey";
                    oidc.session(browser.sessionCookies());
                    var unregistered = oidc.authorize(OidcFixture.flow(), "https://unregistered.example.invalid/callback");
                    assert unregistered.status == 303 && unregistered.location.getAuthority().equals("auth.garden.internal")
                            && OidcFixture.query(unregistered.location).getOrDefault("error", "").equals("invalid_request")
                            && !OidcFixture.query(unregistered.location).containsKey("code") : "Provider must reject an unregistered callback on its own error page without issuing a code";
                    flow = OidcFixture.flow(); authorization = oidc.authorize(flow, OidcFixture.CALLBACK);
                    var refused = oidc.consent(authorization.location, false);
                    parameters = OidcFixture.query(refused.location);
                    assert refused.status == 303 && parameters.getOrDefault("error", "").equals("access_denied")
                            && !parameters.containsKey("code") : "Explicit refusal of consent must not issue a code";
                    flow = OidcFixture.flow(); authorization = oidc.authorize(flow, OidcFixture.CALLBACK);
                    var callback = oidc.consent(authorization.location, true);
                    String code = OidcFixture.code(callback, flow);
                    boolean rejected = false;
                    try { OidcFixture.code(callback, OidcFixture.flow()); } catch (java.io.IOException error) { rejected = true; }
                    assert rejected : "Client must reject a callback with a mismatched state";
                    var wrongSecret = oidc.exchange(code, flow.verifier(), false);
                    assert wrongSecret.status == 401 && wrongSecret.error().equals("invalid_client") && !wrongSecret.body.has("access_token") : "Incorrect client secret must not redeem the authorization code";
                    var tokens = oidc.exchange(code, flow.verifier(), true);
                    assert tokens.status == 200 && tokens.body.has("id_token") && tokens.body.has("access_token")
                            && tokens.body.get("token_type").getAsString().equalsIgnoreCase("Bearer") : "Mounted raw client secret must match the Tend-derived Authelia hash";
                    String token = tokens.body.get("id_token").getAsString(), access = tokens.body.get("access_token").getAsString();
                    var claims = OidcToken.verify(token, keys.body, OidcFixture.ISSUER, OidcFixture.CLIENT, flow.nonce(), access, java.time.Instant.now());
                    assert claims.get("preferred_username").getAsString().equals("carol") && claims.get("name").getAsString().equals("Carol")
                            && claims.get("email").getAsString().equals("carol@example.invalid") && claims.getAsJsonArray("groups").size() == 1
                            && claims.getAsJsonArray("groups").get(0).getAsString().equals("admins") : "Signed ID token must carry the authenticated user's actual identity and groups";
                    var userinfo = oidc.userinfo(access);
                    assert userinfo.status == 200 && userinfo.body.get("sub").getAsString().equals(claims.get("sub").getAsString())
                            && userinfo.body.get("preferred_username").getAsString().equals("carol") && userinfo.body.getAsJsonArray("groups").get(0).getAsString().equals("admins") : "Bearer userinfo must agree with the validated ID token";
                    String[] parts = token.split("\\.");
                    byte[] signature = java.util.Base64.getUrlDecoder().decode(parts[2]); signature[0] ^= 1;
                    String tampered = parts[0] + "." + parts[1] + "." + java.util.Base64.getUrlEncoder().withoutPadding().encodeToString(signature);
                    for (String invalid : new String[]{"signature", "issuer", "audience", "nonce", "expiry"}) {
                        rejected = false;
                        try {
                            OidcToken.verify(invalid.equals("signature") ? tampered : token, keys.body,
                                    invalid.equals("issuer") ? "https://wrong.example.invalid" : OidcFixture.ISSUER,
                                    invalid.equals("audience") ? "other-client" : OidcFixture.CLIENT,
                                    invalid.equals("nonce") ? "wrong-nonce" : flow.nonce(), access,
                                    invalid.equals("expiry") ? java.time.Instant.ofEpochSecond(claims.get("exp").getAsLong()) : java.time.Instant.now());
                        } catch (java.io.IOException error) { rejected = true; }
                        assert rejected : "Client must reject an invalid ID token " + invalid;
                    }
                    var replay = oidc.exchange(code, flow.verifier(), true);
                    assert replay.status == 400 && replay.error().equals("invalid_grant") && !replay.body.has("access_token") : "An authorization code must be single use";
                    flow = OidcFixture.flow(); authorization = oidc.authorize(flow, OidcFixture.CALLBACK);
                    code = OidcFixture.code(oidc.consent(authorization.location, true), flow);
                    var wrongPkce = oidc.exchange(code, OidcFixture.flow().verifier(), true);
                    assert wrongPkce.status == 400 && wrongPkce.error().equals("invalid_grant") && !wrongPkce.body.has("access_token") : "Incorrect PKCE verifier must not redeem a code";
                    authStarted = garden.started(AuthorizationFixture.AUTH);
                    garden.reconcile();
                    assert garden.started(AuthorizationFixture.AUTH).equals(authStarted) : "No-op OIDC reconciliation must preserve the authorization instance";
                    incus.require("restart", AuthorizationFixture.AUTH); fixture.ready(); browser.clearSession();
                    login = browser.login("valid");
                    assert login.get("status").getAsInt() == 200 : "Passkey login must survive OIDC service restart";
                    oidc.session(browser.sessionCookies());
                    var restartedKeys = oidc.keys();
                    assert restartedKeys.body.equals(keys.body) : "Restart must preserve the generated public signing key";
                    flow = OidcFixture.flow(); authorization = oidc.authorize(flow, OidcFixture.CALLBACK);
                    code = OidcFixture.code(oidc.consent(authorization.location, true), flow);
                    tokens = oidc.exchange(code, flow.verifier(), true);
                    assert tokens.status == 200 : "Shared client secret and hash must still match after restart";
                    claims = OidcToken.verify(tokens.body.get("id_token").getAsString(), restartedKeys.body, OidcFixture.ISSUER,
                            OidcFixture.CLIENT, flow.nonce(), tokens.body.get("access_token").getAsString(), java.time.Instant.now());
                    assert claims.get("preferred_username").getAsString().equals("carol") : "Restarted provider must issue a valid token for the same passkey user";
                    assert !oidc.leakedInEvidence() : "Generated client secret, sessions, codes and tokens must stay out of service logs and command evidence";
                    trail.note("OIDC code exchange, signed identity/group claims, consent, negative requests and persistent credentials verified");
                    System.out.println("OIDC smoke: passkey session and explicit consent issue a verifiable ID token; groups, client authentication, PKCE, single-use codes and restart persistence verified.");
                }
                var grafana = new GrafanaFixture(incus, garden, fixture);
                garden.source("oidc.yml", grafana.provider());
                garden.source("grafana.ini", grafana.configuration("admins", "Viewer"));
                garden.source("root.crt", Files.readString(garden.directory.resolve("root.crt")));
                String grafanaRevision = garden.commitXml(grafana.xml(), "Deploy real Grafana OCI consumer with confidential OIDC");
                garden.reconcile(); fixture.ready(); grafana.dns(); grafana.ready();
                assert garden.lastSuccess().equals(grafanaRevision) : "Tend must activate the OCI consumer and its shared generated credentials";
                var identity = incus.run(java.time.Duration.ofSeconds(10), "exec", GrafanaFixture.INSTANCE, "--", "stat", "-c", "%u:%g:%a", "/etc/tend-grafana-client/value");
                assert identity.status() == 0 && identity.output().strip().equals("472:0:400") : "OCI consumer must receive a private client secret readable by its declared UID";
                browser.privateValues(); browser.clearSession();
                browser.navigate(GrafanaFixture.ORIGIN + "/api/health", GrafanaFixture.ORIGIN);
                assert browser.applicationUser().get("status").getAsInt() == 401 : "Anonymous browser must not have a Grafana identity";
                browser.navigate(GrafanaFixture.ORIGIN + "/login/generic_oauth", OidcFixture.ISSUER); browser.probe();
                assert browser.evaluate("new URL(location.href).searchParams.has('flow_id')").getAsBoolean() : "Anonymous Grafana login must redirect to a real provider authentication flow";
                login = browser.login("valid");
                assert login.get("status").getAsInt() == 200 && login.get("ok").getAsBoolean() : "Grafana flow must accept Carol's enrolled passkey without a password";
                browser.navigate(GrafanaFixture.ORIGIN + "/login/generic_oauth", OidcFixture.ISSUER);
                var pending = browser.consent();
                assert pending.get("client").getAsString().equals(GrafanaFixture.CLIENT) && !pending.get("login").getAsBoolean() : "Passkey session must satisfy Grafana's explicit one-factor OIDC policy";
                browser.acceptConsent(GrafanaFixture.CLIENT, GrafanaFixture.ORIGIN);
                var user = browser.applicationUser();
                assert user.get("status").getAsInt() == 200 && user.get("login").getAsString().equals("carol")
                        && user.get("name").getAsString().equals("Carol") && user.get("email").getAsString().equals("carol@example.invalid")
                        && user.get("admin").getAsBoolean() : "Grafana itself must redeem the PKCE code, validate the ID token and map admins to GrafanaAdmin";
                int userId = user.get("id").getAsInt();
                var orgs = browser.evaluate("(async () => {const r=await fetch('/api/user/orgs',{signal:AbortSignal.timeout(8000)});return {status:r.status,orgs:await r.json()};})()").getAsJsonObject();
                assert orgs.get("status").getAsInt() == 200 && orgs.getAsJsonArray("orgs").size() == 1
                        && orgs.getAsJsonArray("orgs").get(0).getAsJsonObject().get("role").getAsString().equals("Admin") : "Grafana must assign the mapped organization admin role";
                String grafanaStarted = garden.started(GrafanaFixture.INSTANCE);
                garden.reconcile();
                assert garden.started(GrafanaFixture.INSTANCE).equals(grafanaStarted) : "Unchanged OCI desired state must not restart Grafana";
                incus.require("restart", GrafanaFixture.INSTANCE); grafana.ready();
                user = browser.applicationUser();
                assert user.get("status").getAsInt() == 200 && user.get("id").getAsInt() == userId && user.get("admin").getAsBoolean() : "Grafana session and user identity must survive restart with the persistent data volume and secret key";
                browser.privateValues(); browser.clearSession(); browser.portal();
                assert browser.password("bob") == 200 : "Observer must authenticate before testing Grafana's own admission rules";
                browser.navigate(GrafanaFixture.ORIGIN + "/login/generic_oauth", OidcFixture.ISSUER);
                pending = browser.consent();
                assert pending.get("client").getAsString().equals(GrafanaFixture.CLIENT) && !pending.get("login").getAsBoolean() : "Provider must permit the observer so the consumer's group check is exercised";
                browser.acceptConsent(GrafanaFixture.CLIENT, GrafanaFixture.ORIGIN);
                user = browser.applicationUser();
                assert user.get("status").getAsInt() == 401 : "Grafana must reject a provider-authenticated user outside its allowed groups";
                grafanaStarted = garden.started(GrafanaFixture.INSTANCE);
                garden.source("grafana.ini", grafana.configuration("admins observers", "None"));
                String strictRevision = garden.commitXml(grafana.xml(), "Exercise strict role rejection independently of group admission");
                garden.reconcile(); grafana.ready();
                assert garden.lastSuccess().equals(strictRevision) && !garden.started(GrafanaFixture.INSTANCE).equals(grafanaStarted) : "Git configuration changes must activate the real OCI consumer";
                browser.navigate(GrafanaFixture.ORIGIN + "/login/generic_oauth", OidcFixture.ISSUER);
                browser.acceptConsent(GrafanaFixture.CLIENT, GrafanaFixture.ORIGIN);
                user = browser.applicationUser();
                assert user.get("status").getAsInt() == 401 : "An allowed group with no mapped role must still be denied under role_attribute_strict";
                browser.privateValues(); browser.clearSession(); browser.portal();
                login = browser.login("valid");
                assert login.get("status").getAsInt() == 200 : "Git activation must preserve the registered passkey";
                browser.navigate(GrafanaFixture.ORIGIN + "/login/generic_oauth", OidcFixture.ISSUER);
                browser.acceptConsent(GrafanaFixture.CLIENT, GrafanaFixture.ORIGIN);
                user = browser.applicationUser();
                assert user.get("status").getAsInt() == 200 && user.get("id").getAsInt() == userId && user.get("admin").getAsBoolean() : "Fresh OIDC login after Git activation must retain the same Grafana user and admin mapping";
                var evidence = grafana.evidence(browser.privateValues());
                assert evidence.groupDenied() && evidence.roleDenied() : "Grafana must report both the independent group denial and strict unmapped-role denial";
                assert !evidence.leaked() : "Grafana secrets, browser sessions, authorization codes and JWTs must stay out of service logs and uploaded evidence";
                trail.note("Real Grafana OCI login, identity, group/strict-role decisions and persistent data verified");
                System.out.println("Grafana smoke: OCI consumer completes passkey OIDC login, maps identity and admin roles, denies outside-group and unmapped-role users, and preserves identity across restart and Git activation.");
            }

        }
    }
}
