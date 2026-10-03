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
selection. The VM needs passwordless sudo, JDK 25, Git, curl, GPG, OpenSSL, Python 3, Docker and working host internet access.

From Tend's repository root:

```sh
export TEND_DISPOSABLE_RUNNER=yes
sh scripts/prepare
javac @cmd/compile
bash scripts/incus-smoke/install
bash scripts/incus-smoke/prepare
bash scripts/incus-smoke/prepare-ingress
bash scripts/incus-smoke/authenticate
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
