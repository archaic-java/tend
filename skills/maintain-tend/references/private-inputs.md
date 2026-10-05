# Fresh private inputs

Operator-owned read-only custom volumes deliver fresh Authelia users, Incus metrics TLS and an
optional external SMTP password. Tend owns generated session/storage/reset/OIDC/application
credentials on its retained controller state. These boundaries never adopt or overwrite each
other's bytes. No migration, general secret manager, rotation or backup/export is introduced.

## Provision and declare

Use an authorized workstation Incus remote from [remote bootstrap](incusos-bootstrap.md).
The operator supplies a directory with mode 0700 and regular files with mode 0600 or 0400;
symlinks, missing files, extra files and shared permissions fail before API access. The command
uses only the remote Incus API, bounded calls and generic diagnostics. Provider output is
withheld because it may contain private inputs.

```sh
python3 scripts/bootstrap/private-volume create users \
  measerve garden pool garden-users garden 1000 /private/garden/users
```

Arguments are mode, kind, remote, project, pool, volume, owner, application UID/GID and private
directory. Choose the actual consumer identity; this command does not invent an image UID.
It creates a shifted filesystem volume with initial UID/GID, directory mode 0700 and
`user.tend.private=OWNER` plus `user.tend.private.kind=KIND`. Files have that UID/GID and mode
0400. Existing names are refused before writes. Partial delivery remains operator-owned;
keep consumers stopped, reobserve the ledger and repair the complete input before starting.

Declare its delivery metadata and an ordinary read-only Incus disk in public XML:

```xml
<volume pool="pool" name="garden-users"
        private-owner="garden" private-kind="users" private-uid="1000"/>
<!-- Inside the Authelia instance, before its generated mounts: -->
<device name="users" type="disk"><config>
  <entry key="pool" value="pool"/><entry key="source" value="garden-users"/>
  <entry key="path" value="/etc/private-users"/><entry key="readonly" value="true"/>
</config></device>
```

All three private attributes are required together. Private declarations accept neither managed
`config` nor `file` children. Tend validates existence, scope owner, kind, directory UID/GID/mode,
shifted mapping, absence of Tend ownership and read-only consumers before any mutation or secret
generation. It does not read private bytes, create this volume, repair its configuration/files or
include it in generated activation digests. A normal Tend-managed volume cannot adopt it.
Ownership names, volume names and paths are public metadata; user records and credential values
never belong in XML, configuration Git files, Incus environment values, image layers or diagnostics.

## Users and first login

The users directory contains exactly `users.yml`. Its content is JSON-form YAML, accepted by
Authelia and deliberately validated without adding a YAML dependency. It has one `users` map;
each username has exactly `displayname`, `password`, `email` and a nonempty unique `groups` array.
Generate each password hash privately using the pinned Authelia CLI's Argon2id generator.
The delivery command rejects plaintext passwords, malformed records and duplicate object keys.
Authelia performs the definitive hash/config validation when it starts; the command's syntax
check does not certify hash strength or successful authentication.

Provision actual users with the intended `admins` and `ai-users` memberships. Both groups must
be present across the fresh input. Give an administrator `ai-users` too when they need Open
WebUI, whose admission is `ai-users`; the administrator role alone does not grant that admission.
Other group names remain explicit; no implicit administrator bypass is introduced.

Configure Authelia's file backend at `/etc/private-users/users.yml`. Keep `watch: false` explicit:
private group activation uses a deliberate stop/start, avoiding changes beneath active sessions.
The data volume retains Authelia's database and enrolled passkeys. Session, storage encryption,
reset-password, OIDC HMAC/signing keys and client raw/hash secrets continue to use Tend-generated
secret mounts; user delivery does not generate, rotate or copy them.

After reset, log in with the newly provisioned password, elevate the session using the configured
identity-verification notifier and enroll a new discoverable passkey. Check a fresh passwordless
login and an outside-group denial. `one_factor` accepts password **or** passkey; it does not enforce
passkey-only access or require the experimental two-factor passkey setting. Old authenticators
and database recovery are outside this fresh-install contract.

## Safe user/group updates

Stop the bootstrap controller first, then every consumer of the users volume. Prepare and
validate the complete replacement privately. The `replace` mode verifies the stopped controller's
`user.tend.bootstrap` owner and requires every recorded volume consumer to be stopped before
any file mutation. It refuses conflicting ownership and recreates files to establish mode/UID
because Incus overwrite preserves old metadata.

```sh
incus stop measerve:tend --project garden
incus stop measerve:authelia --project garden
python3 scripts/bootstrap/private-volume replace users \
  measerve garden pool garden-users garden 1000 /private/garden/users tend
incus start measerve:authelia --project garden
# Observe bounded application health, then fresh login/group admission and outsider denial.
incus start measerve:tend --project garden
```

The command leaves all consumers and the controller stopped. Do not restart after partial failure.
Repair by repeating a complete replacement while the stopped-writer interlock still holds.
After successful delivery, bound health/login checks to 90 seconds; if they fail, stop the consumer
and keep the controller stopped while correcting the private input. Updates invalidate in-memory
sessions by restarting Authelia; revoke access checks must use fresh sessions. Retained OIDC refresh
or application sessions have their own expiry/revocation policy and are not claimed revoked by
this procedure. There is one authorized operator writer; do not race controller start against delivery.

## Metrics TLS and SMTP

The metrics directory contains exactly `client.crt`, `client.key`, `server.crt` and
`controller.crt`. The last file is the public controller certificate for comparison only; it is
never delivered. Provision a new certificate/key, authorize its certificate explicitly as Incus
`--type metrics`, and retain its public fingerprint in the private bootstrap ledger. Use the actual
Incus server trust certificate. Delivery checks key/certificate agreement, expiry and that the
metrics fingerprint differs from the controller's certificate; certificate trust/type and endpoint
hostname verification must also be checked against the live metrics API.

```sh
incus config trust add-certificate measerve: /private/garden/metrics/client.crt \
  --name garden-prometheus --type metrics
python3 scripts/bootstrap/private-volume create metrics \
  measerve garden pool garden-metrics-tls garden 65534 /private/garden/metrics
```

Declare `private-kind="metrics"` and the actual Prometheus UID; mount read-only at
`/etc/incus-tls`. Prometheus `tls_config` names the three certificate/key/trust paths and the
actual server name. Never set `insecure_skip_verify`. A separate metrics credential is mandatory;
this procedure cannot determine server-side privilege from a PEM certificate alone.

Filesystem notification is a valid first-install choice. If SMTP is enabled, its private directory
contains exactly a nonempty `password` file. Deliver with kind `smtp` and the Authelia UID, declare
that kind and mount read-only. Configure the pinned Authelia image's supported password-file
binding (or native configuration template secret function). Only the path enters Git/environment;
never render its value. Host, port, sender and TLS policy are ordinary public configuration.

## Verification and limits

Offline Minau covers preserved operator configuration/files across repeat and controller restart,
and rejection of missing, wrong-owner, writable and ambiguous input volumes before mutations.
`scripts/bootstrap/test-private-volume` exercises private format/permission validation, collisions,
replacement metadata and the stopped-controller/consumer interlock with a protocol fixture.
The disposable authorization case delivers users through this operator procedure, never its image,
checks real read-only metadata and successful password/passkey enrollment/login, changes private
groups with bounded stop/start, retains outsider denial, and preserves private bytes through Tend
configuration activation and restart. Generated secret persistence remains covered separately.
Real metrics scraping and SMTP startup are workload acceptance, not claimed by these offline checks.
