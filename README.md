# Tend

Tend keeps Incus resources aligned with XML committed to the `main` branch of a Git repository.
The experimental state repository is [mitschwimmer/digital-garden](https://github.com/mitschwimmer/digital-garden).
Development uses offline fixtures; a separate CI smoke test provisions real Incus and OVN on a disposable GitHub runner.

## Build and verify

Install JDK 25, Git, curl and sha256sum on Linux. Prepare pinned dependencies once:

```sh
sh scripts/prepare
javac @cmd/compile
java @cmd/test
java @cmd/run --help
```

Preparation needs internet access. Compilation and tests then run offline. Minau v02 runs isolated
cases against a stateful HTTP Incus mock and real temporary bare Git repositories. No live Incus
credentials or GitHub credentials are needed. Dependencies are listed in [development guidance](skills/maintain-tend/references/development.md).

## Maintain Tend

Read the [maintenance skill](skills/maintain-tend/SKILL.md) for the project foundation and task map.
It routes XML/resource changes, reconciliation, secrets, CLI/logging, dependency work and testing
to their owning code and verification. Detailed guidance has one home under its references.

## Resource and verification scope

Tend supports declared instances, custom volumes and managed files, named configurations and
stable generated secrets, Caddy/Authelia ingress and NIC-scoped OVN egress. Existing projects,
pools, networks, cached images and operator-provisioned credentials remain prerequisites.
See the [resource model](skills/maintain-tend/references/resource-model.md) for XML, ownership,
retention, activation and unsupported operations. Validate all Git inputs before deployment mutations.

`cmd/test` runs offline fixtures. The separate `cmd/incus-smoke` suite verifies real Incus/OVN,
reconciliation, Caddy routing, Authelia/passkey/OIDC, Grafana and Pi on a fresh disposable test VM.
It requires operator-authorized disposable infrastructure, nested KVM and pinned application inputs;
never run it against the homelab. Read [integration reproduction and evidence](skills/maintain-tend/references/incus-smoke.md)
before running it. Iterate with the offline suite and run integration once after a coherent change
is ready; repeat only to resolve a failure or after a change that invalidates the evidence.

## Run a controller

```sh
java @cmd/run once /path/to/state-repository https://incus.example:8443 garden garden-controller ./var
java @cmd/run watch /path/to/state-repository https://incus.example:8443 garden garden-controller ./var 30
```

Arguments are mode, Git remote, Incus origin, project, stable ownership identity, persistent state
directory and optional polling seconds. The remote can be a local bare repository or a Git URL.
Git is noninteractive. Failed fetches do not deploy a stale revision. HTTPS uses the JDK default
TLS context, including JSSE client key/trust stores provisioned by the operator. Loopback HTTP is
allowed for local testing only; no trust-all TLS or hostname-verification bypass is provided.

For example, the operator can mount private PKCS12 stores and a private Java argument file:

```text
-Djavax.net.ssl.keyStore=/run/tend/client.p12
-Djavax.net.ssl.keyStoreType=PKCS12
-Djavax.net.ssl.keyStorePassword=OPERATOR_PROVISIONED
-Djavax.net.ssl.trustStore=/run/tend/trust.p12
-Djavax.net.ssl.trustStoreType=PKCS12
-Djavax.net.ssl.trustStorePassword=OPERATOR_PROVISIONED
```

Launch with `java @/run/tend/tls.args @cmd/run watch ...`; keep this file outside Git.
Tend logs revision IDs and generic API errors to stderr, never secret values or HTTP bodies.
Enable debug with `java -Dtend.debug=true @cmd/run ...`. Expected CLI failures exit with status 1;
thread interruption exits with status 130. A reconciliation failure is reported once per attempt.

One process locks its state directory. Bootstrap must provide one writer per ownership scope;
separate state directories are not a distributed leader election mechanism. Failed passes leave
successfully completed work in place. Subsequent passes reobserve Incus and retry. Stop/start
activation is marked complete only after the observed state matches. A file repair clears its
consumers' activation markers *before* writing, so recovery after a crash resumes activation.
`last-success` records the latest completely reconciled commit; it is status, not authoritative
resource state. Incus operations use bounded waits and configuration PUTs use ETags.

## OCI bootstrap boundary

Start a fresh IncusOS installation with the [remote bootstrap procedure](skills/maintain-tend/references/incusos-bootstrap.md).
It inventories the actual host, establishes operator-owned prerequisites, caches pinned images and
records a validated manifest before application deployment. Controller installation and private
input delivery follow the [OCI controller procedure](skills/maintain-tend/references/oci-controller.md)
and issue #14, respectively.

```sh
docker build -t tend:local .
docker run --rm tend:local --help
```

The image runs as UID/GID 1000 and contains the JRE, Git, Tend, Culpa, the catalog and Gson.
The operator procedure uses `scripts/bootstrap/export-controller` to build an identified OCI
artifact and `scripts/bootstrap/controller` to create a stopped Incus controller with retained
state and read-only private TLS/argument mounts. Incus's `oci.entrypoint` supplies the complete
Java command, including `watch` arguments from a private file. The OCI `VOLUME` declaration
does not provision Incus storage. Tend neither creates its own credentials nor reconciles itself.

No image is published by this workflow, and the existing homelab is untouched. Secret backup/export
and rotation remain deferred. Deleting the controller secret directory loses generated credentials;
preserve its volume during replacement.

See [development guidance](skills/maintain-tend/references/development.md) for test boundaries and the implementation sequence.

Fresh private users, metrics TLS and optional SMTP credentials use [operator-owned read-only volumes](skills/maintain-tend/references/private-inputs.md); Tend retains ownership of generated application secrets.
