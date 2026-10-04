# Disposable Incus/OVN smoke test

The `incus-smoke` job in Verify tests whether an ordinary GitHub-hosted Ubuntu VM can provide the
real environment needed for Tend integration. Incus is installed directly on the runner. Its
network/authentication workloads are unprivileged system containers. The Pi installation case uses
a real cloud VM and requires nested KVM; missing support fails rather than skipping the case.
The VM contains both OVN's central database/control plane and its local controller/Open vSwitch.

## Contents

- [What it proves](#what-it-proves)
- [Inputs and evidence](#inputs-and-evidence)
- [Reproduce on a disposable VM](#reproduce-on-a-disposable-vm)
- [Adapter references](#adapter-references)

## What it proves

The OVN Minau case starts two containers on `tend-ci-ovn`, with static IPv4 addresses. The server
exposes the same HTTP response on 8080 and 8081. A statically linked BusyBox binary from the runner
provides both servers and the client probe; guest package installation is unnecessary.

The case verifies, in order:

1. Both ports return the expected body before filtering.
2. A client NIC egress ACL allows TCP to the server's IPv4 address on 8080; default egress rejects
   unmatched traffic and default ingress allows traffic.
3. 8080 still returns the expected body, while 8081 fails with a connection refusal or timeout.
   An Incus exec error, missing executable or dead server does not qualify as successful rejection.
4. 8080 remains reachable while the negative test is performed.
5. Detaching the ACL restores 8081, establishing that the ACL caused its failure.

The NIC default actions are configured before instance start. In Incus, changing these defaults
can recreate the NIC; assigning or removing `security.acls` supports an in-place update. This case
isolates ACL attachment and filtering rather than guest recovery after NIC replacement.

Probes use new TCP connections, bounded command timeouts and readiness polling. No ICMP/ping
assumptions, public ingress, certificates or guest internet access are involved. Assertions are
inline Java assertions run with `-ea`. This case proves environment provisioning and actual packet
filtering; the separate cases below cover controller bootstrap and application authentication.

The controller case builds the reviewed Tend Docker revision, exports its OCI runtime bundle,
and imports the split image through the operator procedure in
[OCI controller bootstrap](oci-controller.md). It starts the actual UID 1000 Java `watch`
process inside native Incus OCI execution, using a read-only private TLS/arguments volume,
a retained state volume, and a separate read-only bare Git volume. A project-restricted
client authenticates over verified HTTPS through the private bridge at `10.79.0.1`.
The runner publishes commits; it never launches Tend to reconcile this case.

The case checks application creation and changed-main activation, stable unchanged passes,
stop/start and replacement with retained random/RSA identity and last-success. Missing Git,
an unrelated trust anchor and revoked API authorization must fail within a bounded interval,
preserve the previous success, and recover after repair. Missing Git must also leave deliberate
application drift untouched, proving a failed fetch does not deploy cached main. Private state,
console output and TLS store passwords are inspected privately and excluded from artifacts.
Only image provenance, public configuration, mount/UID checks and redacted test notes are retained.
These are disposable synthetic credentials; no production host is contacted.

A separate reconciliation case runs the actual `once` CLI over HTTPS with an operator-authorized
client certificate and a pinned server trust store. It authors XML and a configuration file in a
disposable Git repository and pushes to a local bare `main`. The CLI creates a real custom volume
with `security.shifted=true` and a running unprivileged container with empty profiles. The case
checks mounted bytes, UID/GID and mode, ownership markers, no restart on an unchanged pass, repair of independently introduced config
and file/permission drift, preservation of unrelated operator configuration, and activation of a
new `main` commit. It checks `last-success` against each published commit. This covers the core
Git-to-Incus path, not generated ingress or secret delivery.

The egress case starts an independent server with two HTTP ports and uses Tend to create its
client from XML. Both ports must work before filtering. It then commits an `<egress>` allowing
8080, runs the CLI and checks 8080 works while 8081 is rejected. A new `main` commit permits 8081
instead, and the probes must reverse. An operator CLI call adds an undeclared 8080 allow rule;
the case first proves the forbidden port has reopened, then reruns Tend against the same commit.
8080 must be rejected again while 8081 remains available, with no client restart. This proves
Git-driven lowering, real ACL API updates and packet-level drift repair. It does not certify
arbitrary protocols, external networks, ingress authorization or IPv6.

The public ingress case creates its gateway and backend through XML on Git `main`. A cached
Alpine image runs checksum-pinned Caddy 2.11.7 via OpenRC against the generated read-only
`/etc/caddy/Caddyfile`. An independent curl client reaches `app.tend.localhost` using `--resolve`.
Caddy automatically issues a local certificate for `.localhost`; there is no public DNS or ACME.
The client first rejects the untrusted certificate, then explicitly trusts the public root CA,
keeping certificate and hostname verification enabled. Two CGI endpoints report distinct bodies
and received identity headers. The case verifies that forged identity headers are stripped and
an unknown HTTP Host returns Caddy's empty default response without adding a backend request. A new commit changes the backend port. It then
alters the generated backing volume through the real HTTPS file API, reloads Caddy to prove the
wrong route is live, and verifies that the same Git commit restores both file bytes and live
routing. Unchanged passes must not restart the gateway, and its CA survives stop/start activation.
The authorization binding uses a stopped Alpine placeholder: this case has only public routes
and does not test Authelia startup, login, group authorization, or public certificate issuance.

The authorization case uses real Authelia 4.39.28, three synthetic users in distinct groups,
and one-factor password login through the actual first-factor endpoint. Its OpenRC entrypoint
restores Incus's file-path bindings from the init environment because OpenRC clears the service
environment, then translates `X_AUTHELIA_CONFIG` to the binary's `--config` file list. Curl stores
session cookies
privately and uses verified HTTPS through a separate Caddy gateway. Anonymous requests redirect
to login; authenticated outside-group users receive 403. Backend request logs prove denials never
reach the application, and CGI responses verify Authelia identity replaces all forged headers.
A new Git commit changes the allowed group; an operator alters the generated policy and restarts
Authelia to prove undeclared access is live. The same desired revision must repair it. No-op passes
must preserve both service start times and the active session. This case uses in-memory sessions,
so it logs in again after policy activation restarts.

The same case then enables password-or-passkey login through a Git configuration commit.
A headless Chromium browser uses CDP's virtual CTAP2 authenticator with resident keys and user
verification. The test performs password login only for enrollment, verifies the real private
filesystem-notifier code to elevate the session, obtains registration options from Authelia,
and calls the browser's native `navigator.credentials.create`. After clearing cookies it calls
`navigator.credentials.get` and the first-factor passkey endpoint without sending a password.
It checks outside-group denial, permitted identity forwarding, server rejection of unknown
credential IDs and signed assertions lacking required user verification, session stability on
a no-op pass, and credential persistence after both an explicit restart and Git-driven
configuration activation. Password login remains valid under `one_factor`; this does not
enforce passkey-only access and does not use Authelia's experimental two-factor option.

The gateway receives a test-only Incus proxy device listening on host loopback port 443, so
Chromium can reach the OVN guest without a public listener. Host resolution maps the two
fixture domains to loopback. Its public CA is imported into the disposable runner's NSS trust
database; browser certificate and hostname checks remain enabled. CDP listens only on loopback,
and its messages, browser profile, logs, enrollment notifications and cookie transfers stay
in the private garden directory, which is removed even on test failure. The probe drives
Authelia's HTTP contracts and native WebAuthn APIs, not UI selectors. It does not test physical
authenticators, biometrics, passkey synchronization or two-factor flows.
Tend generates and mounts Authelia's session, storage and reset-password secrets under `/etc`;
Alpine's boot-time `/run` tmpfs would hide disks mounted below that directory. Passwords, hashes,
cookies, user databases and secret bytes are never uploaded; private fixture accounts are cached
in an operator-owned read-only custom volume delivered by `scripts/bootstrap/private-volume`; login bodies reside in a private temporary directory. The application image contains no user records. The case verifies private metadata/read-only access, preserved bytes across reconciliation/restart, and bounded operator group updates with outsider denial. See [private input ownership](private-inputs.md).

The authorization case then commits an OIDC configuration and generated RSA signing key,
HMAC value and client-secret/hash declarations. Authelia loads the PEM and hash with its native
`template` filter; the confidential test client reads the raw value from its actual Incus mount.
A JDK HTTP client trusts only the disposable public CA, retains hostname checks and follows no
automatic redirects. The runner maps `auth.garden.internal` to loopback in `/etc/hosts` for this
client; Chromium continues to use its explicit resolver mapping.

The test checks discovery, anonymous login requirements, an authenticated observer denied by
the OIDC client policy, passkey login for the permitted admin, explicit consent refusal/acceptance,
and PKCE-S256 authorization-code exchange with `client_secret_basic`. It validates the actual
ID-token signature against the issuer JWKS, issuer, audience, nonce, expiry and identity/group
claims, then checks bearer userinfo against that identity. Negative checks cover an unregistered
callback, mismatched state, wrong client secret, wrong PKCE verifier, replayed code, and rejected
ID-token signature/issuer/audience/nonce/expiry. Restart must preserve signing keys and successful
exchange. Tokens, codes, cookies and client credential stay in process memory or private temporary
files outside the artifact directory; failure messages omit their bodies. This is a narrow protocol
test client, not a JOSE library.

The same case next deploys Grafana 13.1.0 from a digest-pinned cached OCI image, using UID 472,
a persistent custom data volume and read-only configuration/secret mounts. Tend generates the
admin password, encryption key, confidential client secret and matching Authelia hash. A public
Caddy route forwards to Grafana, which performs its own OIDC authorization. Grafana trusts the
fixture CA explicitly, uses PKCE-S256 and client-secret Basic authentication, and validates the
ID token against the provider JWKS. The configuration supplies both `tls_client_ca` and
`SSL_CERT_FILE`: this Grafana version fetches JWKS through Go's default HTTP client.
Operator bootstrap starts a fixture-only dnsmasq on the
gateway for the private provider domain. The OCI guest selects it through Incus’s existing
`oci.dns.nameservers` configuration; Tend gains no exec or DNS feature. Incus manages the
OCI guest’s read-only `/etc/hosts` and `/etc/resolv.conf` files.

The real browser follows Grafana’s login redirect, authenticates with Carol’s enrolled passkey,
accepts explicit consent and returns through Grafana’s callback. Grafana itself exchanges the
code. Its user API must report Carol’s actual name/email/login, global admin status and Admin
organization role. The provider permits Bob, then Grafana independently denies him through
`allowed_groups` while Bob has a valid Viewer role. A Git change to only the group list then
admits the same observer as Viewer, without global admin privileges. A second Git change to
only the role mapping replaces that fallback with an empty role, independently denying Bob.
Grafana accepts `None` as a valid role; the homelab's admin-group allowlist is its admission rule.
An explicitly configured private Grafana log file is inspected for credential leakage.
No-op reconciliation preserves the process, restart preserves
the session, and a fresh login after Git activation preserves the same user ID and admin role.
Browser cookies/codes and mounted secret values are checked against command evidence and
privately read application logs. Grafana’s raw console is never uploaded, including on failure.
Open WebUI, physical authenticators and two-factor remain outside this fixture.

The Pi case creates a Debian 13 cloud VM and two retained custom filesystem volumes through
Tend's XML on a disposable Git `main`. `cloud-init.user-data` installs the same checksum-pinned
Node 24.19.0 and pi-web-sandbox release as homelab, including `npm ci --omit=dev --ignore-scripts`,
and enables the same hardened systemd unit. The copied installer/unit originate from
homelab commit `8fc54492c6d75d9713061703c5a6667347e1481b`; update these fixtures deliberately
when the homelab provisioning changes. JSON-form cloud-config avoids a new YAML library.

Bounded test-fixture checks wait for the real Incus agent and successful cloud-init, then require
the unprivileged systemd service, exact Node version, actual health JSON and built frontend assets.
Startup reports must exclude Bash. Both data paths must be actual virtiofs/9p mounts with UID/GID
1000 and mode 0700. The pi user writes a marker into each volume; an unchanged Tend pass must
not reboot the VM, and an explicit restart must restore the service and preserve both markers.
The declared synthetic model is unreachable and receives no requests. Conversations and tool
behavior are already tested in pi-web-sandbox; this case tests deployment. It does not repeat
Caddy/Authelia authentication, exercise a GPU, or prove Pi update/replacement behavior. The latter
is deferred in [issue #9](https://github.com/archaic-java/tend/issues/9); no controller lifecycle
feature is added here.

Pi uses a separate disposable NAT bridge (`10.78.0.0/24`) for the guest installer to reach Debian,
Node, GitHub releases and npm. Docker's forwarding chain, when present, explicitly permits
outbound/established-return traffic for that bridge. The test does not claim network isolation.
Pi VM/cloud-init/service diagnostics contain only synthetic configuration, no real credentials;
workspace and session contents are excluded from artifacts.

The cases use different resources and evidence directories so concurrent Minau execution is safe.
TLS keys, private Java arguments and controller state are outside the uploaded artifact. The test
uses the normal JSSE client/trust stores and hostname verification; there is no TLS bypass.

## Inputs and evidence

- GitHub runner OS label: `ubuntu-24…16112 tokens truncated…ng();
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
                assert !grafana.leakedInEvidence(browser.privateValues()) : "Group denial must not leak credentials into application logs or command evidence";
                grafanaStarted = garden.started(GrafanaFixture.INSTANCE);
                garden.source("grafana.ini", grafana.configuration("admins observers", "Viewer"));
                String admittedRevision = garden.commitXml(grafana.xml(), "Admit the observer as Viewer through a group-list-only change");
                garden.reconcile(); grafana.ready();
                assert garden.lastSuccess().equals(admittedRevision) && !garden.started(GrafanaFixture.INSTANCE).equals(grafanaStarted) : "Git group-list change must activate Grafana";
                browser.navigate(GrafanaFixture.ORIGIN + "/login/generic_oauth", OidcFixture.ISSUER);
                browser.acceptConsent(GrafanaFixture.CLIENT, GrafanaFixture.ORIGIN);
                user = browser.applicationUser();
                assert user.get("status").getAsInt() == 200 && user.get("login").getAsString().equals("bob") && !user.get("admin").getAsBoolean() : "Changing only the allowed groups must admit the same observer with a valid non-admin role";
                orgs = browser.evaluate("(async () => {const r=await fetch('/api/user/orgs',{signal:AbortSignal.timeout(8000)});return {status:r.status,orgs:await r.json()};})()").getAsJsonObject();
                assert orgs.get("status").getAsInt() == 200 && orgs.getAsJsonArray("orgs").size() == 1
                        && orgs.getAsJsonArray("orgs").get(0).getAsJsonObject().get("role").getAsString().equals("Viewer") : "The admitted observer must receive exactly the mapped Viewer organization role";
                browser.privateValues(); browser.clearSession(); browser.portal();
                assert browser.password("bob") == 200 : "Observer must begin a fresh provider session before the strict-role check";
                grafanaStarted = garden.started(GrafanaFixture.INSTANCE);
                garden.source("grafana.ini", grafana.configuration("admins observers", ""));
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
                assert !grafana.leakedInEvidence(browser.privateValues()) : "Grafana secrets, browser sessions, authorization codes and JWTs must stay out of service logs and uploaded evidence";
                trail.note("Real Grafana OCI login, identity, group/strict-role decisions and persistent data verified");
                System.out.println("Grafana smoke: OCI consumer completes passkey OIDC login, maps identity and admin roles, denies outside-group and unmapped-role users, and preserves identity across restart and Git activation.");
            }

            assert fixture.privateUsers().equals(privateUsers) : "All configuration activations and process restarts must preserve operator users";
            assert fixture.privateEvidenceExcluded() : "Private user hashes and passwords must be absent from Git and uploaded evidence";
            assert !Files.readString(garden.directory.resolve("author/incus.xml")).contains(privateUsers) : "Private user database must never enter public desired XML";
        }
    }
}
