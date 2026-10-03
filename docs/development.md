# Offline development contract

## Architecture

One production module, `work.archaic.tend`, contains small packages for Git, XML state, Incus HTTP
and private secret persistence. `ResourceCompiler` lowers named application requirements into an Incus deployment plan.
`Reconciler` preflights that plan; `Volumes`, `Instances` and `NetworkPolicies` converge their
respective resources in one serialized pass. `Main`
composes adapters, selects logging v02 providers through ServiceLoader and wraps complete attempts
in the `tend.reconcile` Goal. Polling is a thin loop around that pass. There is no public provider
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
| Random secrets | SecureRandom and Base64 | Private files with atomic replacement; one writer |
| Goals/logging | Catalog logging v02 with Peep | Explicit ServiceLoader selection in CLI only |
| Tests | Catalog test v02 with Minau | HTTP and Git integration on loopback/local disk |
| Mock server | JDK jdk.httpserver | Test module only |

Pinned source revisions:

- service-catalog: `8fcaa67657b27ebc71cb8165acf70659764821b2`
- minau: `93bb952390b412fbaa38b2805bcf954fb6b5bd82`
- peep: `c196921d0a26937c4880ea716e9dd009ca0c5452`

Gson JAR SHA-256: `2cbd119bf1961c28788310963dc80ba65f58cdeec1dd139c8bdb1240faa2c36f`.
`jar --describe-module --file lib/bin/gson-2.14.0.jar --release 9` confirms a named descriptor.
Gson has Apache-2.0 licensing. Distribution licensing and notices for Tend and its source-linked
Archaic dependencies must be settled before publishing releases. This workflow builds an image
for testing and does not distribute it.

Gson was selected for its small runtime footprint, explicit JPMS descriptor and straightforward
tree API. Git CLI was selected so production and tests share Git behavior, including rewritten
main and immutable object reads. No Git protocol mock or JGit dependency is necessary.

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
is deliberately a later stage. Before claiming compatibility, test these adapters against a pinned
Incus version and resolve any discrepancy against the real API rather than relaxing the mock blindly.

## Follow-up sequence

1. Review the named resource contract and its offline projection tests.
2. Validate generated Caddy/Authelia configurations against pinned application versions, then add readiness, nested files and reload.
3. Add explicit registry sources and safe image replacement with retained data.
4. Add application-specific secret generation/derivation only when a consumer requires it.
5. Build and smoke-test the OCI image, then integrate with a disposable real Incus project when authorized.

Backup/export and any changes to the existing homelab remain outside this proof of concept.

## Review conventions

CLI mode selection uses a switch. Reconciliation and mock HTTP dispatch use small named methods
rather than deeply nested branches. Expected XML, Git, secret, Incus and convergence failures have
checked exception types; malformed library inputs are wrapped at their boundary. Watch retries
only expected checked failures. Interruption escapes and programming defects are not swallowed.
Each Minau scenario has its own named case record and run method; shared fixtures prepare adapters
and resources without hiding assertions.
