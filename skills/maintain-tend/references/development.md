# Offline development contract

## Contents

- [Architecture](#architecture)
- [Dependencies](#dependencies)
- [Logging and failure policy](#logging-and-failure-policy)
- [Incus mock fidelity](#incus-mock-fidelity)
- [Follow-up sequence](#follow-up-sequence)
- [Review conventions](#review-conventions)
- [Real-host integration](#real-host-integration)

## Architecture

One production module, `work.archaic.tend`, contains small packages for Git, XML state, Incus HTTP
and private secret persistence. `ResourceCompiler` lowers named application requirements into an Incus deployment plan.
`Reconciler` preflights that plan; `Volumes`, `Instances` and `NetworkPolicies` converge their
respective resources in one serialized pass. `Main`
composes adapters, selects a logging v03 provider through ServiceLoader and wraps each complete attempt
in a fresh configured caller-thread context. Polling is a thin loop around that pass. There is no public provider
contract for internal implementation boundaries.

The test module uses Minau v02. Each case owns its temporary directories, bare remote, HTTP server
and HTTP client. It creates fixtures inside `run`; no static mutable fixtures or suite lifecycle
hooks are needed. Assertions state observable expectations rather than duplicating the implementation.

## Dependencies

| Concern | Choice | Boundary |
| --- | --- | --- |
| XML/XSD | JDK java.xml | DTDs, external schemas and entities disabled |
| HTTPS/TLS | JDK java.net.http and JSSE | Operator-provisioned client identity and server trust |
| JSON | Gson 2.14.0, com.google.gson | Explicit JsonObject tree access; no reflective domain serialization or opens |
| Git | Installed git executable | Bounded, noninteractive ProcessBuilder commands; no shell interpolation |
| Secrets | JDK SecureRandom, RSA and PBKDF2WithHmacSHA512 | Private files with atomic replacement; stable signing keys and derived client hashes; one writer |
| Logging | Catalog logging v03 with Culpa | Explicit ServiceLoader selection and context configuration in CLI only |
| Tests | Catalog test v02 with Minau | HTTP and Git integration on loopback/local disk |
| Mock server | JDK jdk.httpserver | Test module only |

Pinned source revisions:

- service-catalog: `69f61743dc0b8a67f18b06231e3139ce0054eaf2`
- minau: `93bb952390b412fbaa38b2805bcf954fb6b5bd82`
- culpa: `e3db79e01fb4891d22877102b2824b4943ebec0b`

Gson JAR SHA-256: `2cbd119bf1961c28788310963dc80ba65f58cdeec1dd139c8bdb1240faa2c36f`.
`jar --describe-module --file lib/bin/gson-2.14.0.jar --release 9` confirms a named descriptor.
Gson has Apache-2.0 licensing. Distribution licensing and notices for Tend and its source-linked
Archaic dependencies must be settled before publishing releases. This workflow builds an image
for testing and does not distribute it.

Gson was selected for its small runtime footprint, explicit JPMS descriptor and straightforward
tree API. Git CLI was selected so production and tests share Git behavior, including rewritten
main and immutable object reads. No Git protocol mock or JGit dependency is necessary.

## Logging and failure policy

[Main](../../../src/work.archaic.tend/work/archaic/tend/Main.java) owns provider selection,
configuration and CLI exit policy. Validate arguments and require exactly one logging provider
before creating controller state. Inspect service descriptors before constructing the provider.
Application code depends on catalog types only; `cmd/run` resolves Culpa explicitly.

[Controller](../../../src/work.archaic.tend/work/archaic/tend/Controller.java) implements
`Logging`. Keep fetch, XML resolution, preflight, convergence and last-success publication inside
one context per attempt. Reuse that binding through ordinary helper calls. Create another context
for the next polling attempt because contexts are single-use. The controller's complete-pass
IOException translation is an intentional policy boundary: watch retries expected failures,
while programming defects escape. Interruption restores the thread flag and escapes retries.

Select `Configuration.text(Boolean.getBoolean("tend.debug"), System.err)` at composition.
Immediate completion, optional lazy resource-count debug and failure reports use stderr;
`--help` uses stdout. Enable debug with `java -Dtend.debug=true @cmd/run ...`.
Keep evidence to commit IDs and safe resource counts; never include secrets or HTTP bodies.
The context publishes an escaping failure once; the outer CLI chooses status without repeating
that report. Argument and startup failures render separately because no context exists.
Exit status is 0 on success, 1 for expected argument/composition/reconciliation failure, and 130
for thread interruption. Watch retries retain the process until success or interruption.

Translate decoding/provider errors close to their operation. Secret validation uses checked
`SecretException` failures. Cryptographic causes are deliberately withheld because their
messages can contain private input; this is an explicit exception to cause-preservation guidance.
Retain synchronization, atomic file replacement and cleanup scopes. Constructor cleanup in the
browser fixture deliberately catches and rethrows any Exception after closing acquired resources.

The [logging v03 guide](https://github.com/archaic-java/service-catalog/blob/main/skills/maintain-service-catalog/references/logging-v03.md)
and [Culpa README](https://github.com/archaic-java/culpa/blob/e3db79e01fb4891d22877102b2824b4943ebec0b/README.md)
explain the contract and provider. The guide's moving main is navigation, not the dependency pin;
inspect declarations in the selected sibling checkout for exact API semantics.

Verify application policy with `java @cmd/test --suite work.archaic.tend.test.CliTests` after
compilation. The cases cover real process composition, single failure rendering, stderr/debug,
invalid-input rejection before filesystem changes, watch recovery across fresh contexts,
interruption and secret exclusion. Run the full offline suite for convergence and failure recovery.
Minau trails remain independent of application logging.

## Incus mock fidelity

The mock implements only the endpoints currently used:

- GET/POST/PUT custom filesystem volumes.
- GET/POST custom volume files with raw bytes and X-Incus UID/GID/mode/type/write headers.
- GET/POST/PUT instances with configuration, named devices and explicit empty profiles.
- GET/PUT instance state.
- GET managed network properties; GET/POST/PUT network ACLs.
- GET operation wait, including nonterminal and failed operation status.

Resource GETs include ETags; stale conditional PUTs fail with HTTP 412. Mutations return synchronous
or asynchronous envelopes according to the endpoint. Asynchronous effects are applied at completion,
so the mock catches clients that mistake HTTP acceptance for completed work. It also supports lost
responses, HTTP failures, start failures, drift and ownership collisions.

Authoritative contracts:

- https://linuxcontainers.org/incus/docs/main/rest-api/
- https://linuxcontainers.org/incus/docs/main/rest-api-spec/
- https://github.com/lxc/incus/blob/main/doc/rest-api.yaml
- https://github.com/google/gson/blob/main/gson/src/main/java/module-info.java

The mock is not an Incus emulator. It does not prove storage driver behavior, real image creation,
mount visibility, GPU access, TLS authentication or actual application health. Real Incus integration
is a separate suite, whose current coverage is recorded below. For new compatibility claims, test
against the pinned Incus version and resolve discrepancies against the real API rather than
relaxing the mock blindly.

## Follow-up sequence

1. Review the named resource contract and its offline projection tests.
2. Validate generated Caddy/Authelia configurations against pinned application versions, then add readiness, nested files and reload.
3. Add explicit registry sources and safe image replacement with retained data.
4. RSA-3072 signing keys and PBKDF2-SHA512 client-secret derivation now cover the homelab OIDC credentials. The integration fixture now tests a real authorization-code exchange; the real Grafana OCI consumer is now covered, and Open WebUI deployment follows.
5. Extend the verified real-Incus CLI path to OCI bootstrap and generated application policies.

Backup/export and any changes to the existing homelab remain outside this proof of concept.

## Review conventions

CLI mode selection uses a switch. Reconciliation and mock HTTP dispatch use small named methods
rather than deeply nested branches. Expected XML, Git, secret, Incus and convergence failures have
checked exception types; malformed library inputs are wrapped at their boundary. Watch retries
only expected checked failures. Interruption escapes and programming defects are not swallowed.
Each Minau scenario has its own named case record and run method; shared fixtures prepare adapters
and resources without hiding assertions.

## Real-host integration

The separate `work.archaic.tend.integration` module uses Minau v02 and the real Incus CLI.
`cmd/test` does not resolve or run it. `cmd/incus-smoke` selects it explicitly. Its first case
proves the runner can provision unprivileged containers on OVN and enforce an egress ACL,
including reachability before filtering and recovery after detaching the ACL. Shell scripts
install/bootstrap and retain diagnostics before teardown. They never contact the homelab.
The second case invokes Tend's actual CLI over operator-authorized HTTPS, using disposable Git
`main` revisions and a volume with `security.shifted=true`. It verifies convergence, mounted file
metadata, no-op passes without restarts, drift repair and a new revision. The mock mirrors Incus's
preservation of metadata on file overwrite; offline cases cover rejection of unshifted managed
file volumes and retry after failed file recreation. A third case verifies XML egress port changes
and ACL drift repair through actual packet probes. A fourth case runs Caddy
with generated public ingress, explicit local CA trust, Git backend changes, identity header
stripping and live routing drift repair. A fifth case exercises real Authelia startup,
one-factor login, group authorization, identity forwarding, Git policy changes and live drift repair.
It also verifies that Authelia can consume Tend-generated secrets through private file mounts,
and uses headless Chromium with a virtual authenticator to test passkey enrollment, passwordless
one-factor login, rejected assertions and credential persistence across activation. Browser
automation uses JDK HTTP/WebSocket and the existing Gson module; no new controller dependency.
The same case exercises confidential OIDC over verified HTTPS using a small JDK test client.
It validates RS256 with the published JWKS and issuer/audience/nonce/expiry claims, checks
userinfo and groups, rejects bad client secrets and PKCE verifiers, and verifies single-use codes.
The same browser case deploys the actual Grafana OCI consumer and verifies its passkey OIDC
login, identity/admin roles, independent group and strict-role denial, and persistent user/session
state across restart and Git activation. A sixth case deploys the pinned Pi harness through cloud-init in a real Debian VM, checks
agent/installer success, unprivileged systemd startup, frontend/health, Bash exclusion, actual
custom-volume mounts, no-op stability and persistent data across restart. Its fixture readiness
checks add no controller feature. VM update/replacement is tracked in issue #9. OCI controller
deployment is covered separately by ControllerSmokeTests using the operator bootstrap scripts,
native watch, retained controller identity and failure recovery. Two-factor and Open WebUI
deployment remain unverified.
