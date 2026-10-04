# Disposable Incus/OVN smoke test

The `incus-smoke` job in Verify tests whether an ordinary GitHub-hosted Ubuntu VM can provide the
real environment needed for Tend integration. Incus is installed directly on the runner. Its
workloads are unprivileged system containers, so this test needs no nested hardware virtualization.
The VM contains both OVN's central database/control plane and its local controller/Open vSwitch.

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
filtering; it does not prove OCI bootstrap, secret permissions, Caddy/Authelia startup or user authentication.

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
only in a disposable local image and login bodies reside in a private temporary directory.

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
ID token against the provider JWKS. Operator bootstrap writes only the private domain’s public
address mapping to the disposable guest’s `/etc/hosts`; Tend gains no exec or DNS feature.

The real browser follows Grafana’s login redirect, authenticates with Carol’s enrolled passkey,
accepts explicit consent and returns through Grafana’s callback. Grafana itself exchanges the
code. Its user API must report Carol’s actual name/email/login, global admin status and Admin
organization role. The provider permits Bob, then Grafana independently denies him through
`allowed_groups`; a Git change admits observers to the group list while strict role mapping
still denies Bob’s unmapped role. No-op reconciliation preserves the process, restart preserves
the session, and a fresh login after Git activation preserves the same user ID and admin role.
Browser cookies/codes and mounted secret values are checked against command evidence and
privately read application logs. Grafana’s raw console is never uploaded, including on failure.
Open WebUI, physical authenticators and two-factor remain outside this fixture.

The cases use different resources and evidence directories so concurrent Minau execution is safe.
TLS keys, private Java arguments and controller state are outside the uploaded artifact. The test
uses the normal JSSE client/trust stores and hostname verification; there is no TLS bypass.

## Inputs and evidence

- GitHub runner OS label: `ubuntu-24.04`, AMD64.
- Incus and incus-client: `1:7.5.1-ubuntu24.04-202609271822` from Zabbly's signed stable repository.
  The signing key fingerprint is checked before installation. A missing pinned build fails the
  job; update the pin deliberately rather than silently selecting another release.
- OVN, Open vSwitch and busybox-static: Ubuntu packages; exact installed versions are recorded.
- Container image: `images:alpine/3.22`, resolved once and copied into the local image cache. The basic cases use that copy; ingress fixtures derive cached images from it. This pins the OS release, not the rolling build; the full fingerprint
  and image information are retained in `image.txt` for each run.
- Caddy: official `caddy_2.11.7_linux_amd64.tar.gz`, SHA-256
  `727b91701a392de6ebc5027509f548bf39979e5216340d0faed8fa5e69c84f8b`. Bootstrap verifies the
  archive, installs curl from Alpine 3.22, and records curl's version and derived image fingerprints.
  Bootstrap fetches signed curl packages in `alpine:3.22` through the host Docker network,
  records its image digest and package checksums, and installs them offline in Incus. OVN guests
  require no internet access. Caddy's private CA keys
  remain inside its disposable root disk; only its public root certificate is observed.
- Authelia: official `authelia-v4.39.28-linux-amd64-musl.tar.gz`, SHA-256
  `ce2526b633ce3eec06680fae2f26060dc8cef3a92cdbc376410b28bcca6c97e1`. Its CLI generates a
  random private fixture password and Argon2 digest; login bodies and the user database stay private.
- Grafana: official `grafana/grafana:13.1.0` OCI index digest
  `sha256:121a7a9ece6dc10b969f1f96eed64b4f07dfac0d0b8abc070f7cb83bbde86f63`.
  Operator bootstrap uses Incus’s OCI remote with Ubuntu `skopeo`/`umoci`, records the cache
  fingerprint, and Tend creates the application from that fingerprint. No registry pull feature
  is added to Tend.
- Browser: the GitHub Ubuntu runner's installed `google-chrome`, with its version recorded.
  `libnss3-tools` supplies `certutil`; the JDK WebSocket/HTTP client and already-pinned Gson
  drive CDP without a browser automation library or new controller dependency.
- `dir` storage pool and IPv4-only test subnets: `10.77.0.0/24` for the uplink and `10.77.1.0/24`
  for the OVN network. A disposable VM must have no conflicting routes or existing `tend-ci-*`
  resources. Profile inheritance is disabled for the test instances.

Every run uploads `out/incus-smoke/` as a seven-day GitHub artifact. It contains each CLI command
and its output, Minau results, resolved inputs, network configurations, OVN/OVS topology, container
configurations and service journals. Diagnostics run before cleanup, even on failure. All resources
are temporary; the runner VM is discarded after the job. Setup uses only the local daemon and
needs no repository secrets, external Incus credentials or published test images.

## Reproduce on a disposable VM

These scripts install packages, change host network services and create/delete fixed-name Incus
resources in the default project. Run them only in a fresh Ubuntu 24.04 AMD64 test VM, not on an
existing Incus host. The scripts require `TEND_DISPOSABLE_RUNNER=yes` as an explicit environment
selection. The VM needs passwordless sudo, JDK 25, Git, curl, GPG, OpenSSL, Python 3, Docker, Google Chrome and working host internet access. Host loopback port 443 must be free.

From Tend's repository root:

```sh
export TEND_DISPOSABLE_RUNNER=yes
sh scripts/prepare
javac @cmd/compile
bash scripts/incus-smoke/install
bash scripts/incus-smoke/prepare
bash scripts/incus-smoke/prepare-ingress
bash scripts/incus-smoke/prepare-authelia
bash scripts/incus-smoke/prepare-grafana
bash scripts/incus-smoke/authenticate
sudo apt-get install -y libnss3-tools
google-chrome --version >out/incus-smoke/browser-version.txt
echo '127.0.0.1 auth.garden.internal' | sudo tee -a /etc/hosts >/dev/null
java @cmd/incus-smoke
bash scripts/incus-smoke/diagnostics
bash scripts/incus-smoke/cleanup
```

Capture diagnostics and run cleanup after failures too. There is no skip-on-unsupported-host
behavior: failed installation, missing kernel support, failed provisioning or incorrect filtering
must produce a failed CI job. Offline tests continue to use `java @cmd/test` independently.

## Adapter references

- https://github.com/zabbly/incus
- https://linuxcontainers.org/incus/docs/main/howto/network_ovn_setup/
- https://linuxcontainers.org/incus/docs/main/howto/network_acls/
