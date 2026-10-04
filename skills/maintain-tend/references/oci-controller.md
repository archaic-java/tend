# Operator-owned OCI watch controller

Complete the [IncusOS prerequisite manifest](incusos-bootstrap.md) first. This procedure implements
[issue #13](https://github.com/archaic-java/tend/issues/13). All build/bootstrap commands run on
the authorized workstation through the remote Incus API. No IncusOS host shell is required.
Only start watch after private inputs (#14) and the reviewed digital-garden state are ready.

## Build an identifiable artifact

On a Linux AMD64 workstation, install Docker, skopeo, umoci, Python 3, Git and GNU tar/timeout.
Check out the reviewed Tend commit in a clean worktree (including no untracked inputs).
Build/export requires workstation root to preserve filesystem ownership when unpacking OCI;
this is not permission to execute anything on the IncusOS host.

```sh
sudo bash scripts/bootstrap/export-controller /private/new-tend-artifact
```

The script builds the checked-out revision, runs the Dockerfile's compile/offline tests and
labels the runtime image with `org.opencontainers.image.revision`. It converts that Docker image
to an OCI layout with skopeo, unpacks its native process configuration with umoci and produces
Incus split metadata/rootfs archives. `config.json` remains in metadata so Incus executes an
actual OCI container, not a system-container wrapper. No registry publishing is needed.

`provenance.json` records the Git revision, Docker image ID, OCI manifest digest and platform;
`SHA256SUMS` covers the exported files. These identify the artifact actually built; moving
base-image tags do not make builds byte reproducible. Keep the archives/provenance for review.
Private inputs must remain outside the build context. Import through the authorized remote:

```sh
incus image import /private/new-tend-artifact/metadata.tar.gz \
  /private/new-tend-artifact/rootfs.tar.gz measerve: --project garden
incus image list measerve: --project garden --format=json
```

Record the full 64-hex Incus fingerprint in the manifest, together with the artifact identities.
Confirm architecture `x86_64`, type `container`, `properties.type=oci`, `tend.revision` and
`tend.oci.digest`. The imported fingerprint differs from the source OCI digest. Do not choose
an ambiguous prefix or mutable alias for bootstrap/replacement.

## Prepare private identity, trust and arguments

Create a mode-0700 workstation directory outside Git. Its required regular files are
`client.p12`, `trust.p12`, `java.args`, `controller.args`, all mode 0600 or stricter.
Issue a separate client identity for Tend. Grant only the intended project with an authorized
operator client, keeping the private key off the command line:

```sh
incus config trust add-certificate measerve: /private/tend/client.crt \
  --name garden-tend --restricted --projects garden
```

The server must permit this identity's operations in the intended project, including shared
pool/network observation, instances, custom volumes and file operations. Restrict the project
and verify the actual path; do not grant IncusOS host administration or reuse operator keys.
Tend uses standard client-certificate authentication; additional authorization backends need
their own explicit grant. Keep the operator identity available for recovery/revocation.

Obtain and verify the server trust certificate and its SAN through the prerequisite procedure.
Choose an API origin reachable from the private bridge and covered by that SAN; an Incus CLI
connection alone is not proof of Java hostname validation. Convert the private client key/cert
and public server trust to PKCS12 on the workstation. The example uses a locally generated
store password in a private file, so it is not present in process arguments:

```sh
umask 077
openssl rand -hex 24 > /private/tend/store-password
openssl pkcs12 -export -name tend -inkey /private/tend/client.key \
  -in /private/tend/client.crt -out /private/tend/client.p12 \
  -passout file:/private/tend/store-password
keytool -importcert -noprompt -alias incus -file /private/tend/server.crt \
  -keystore /private/tend/trust.p12 -storetype PKCS12 \
  -storepass:file /private/tend/store-password
```

Write `java.args` privately with the fixed guest paths below. Insert the actual store password
using a private editor/file-writing tool; do not echo it into logs or expand it into a command.

```text
-Djavax.net.ssl.keyStore=/etc/tend-bootstrap/client.p12
-Djavax.net.ssl.keyStoreType=PKCS12
-Djavax.net.ssl.keyStorePassword=PRIVATE_STORE_PASSWORD
-Djavax.net.ssl.trustStore=/etc/tend-bootstrap/trust.p12
-Djavax.net.ssl.trustStoreType=PKCS12
-Djavax.net.ssl.trustStorePassword=PRIVATE_STORE_PASSWORD
```

Write `controller.args` in the same private directory, replacing the public origin and scope
with reviewed manifest values (quote Java argfile tokens containing whitespace):

```text
watch
https://github.com/mitschwimmer/digital-garden.git
https://SAN-COVERED-PRIVATE-API-ORIGIN:8443
garden
garden-controller
/var/lib/tend
30
```

No token/password may appear in a Git URL. Public HTTPS Git uses the image's Git and CA bundle.
For private HTTPS Git, supply a mode-0600 `git-credentials` file and `.gitconfig` in this
directory, with `credential.helper = store --file=/etc/tend-bootstrap/git-credentials`.
The mount is read-only, so credential refresh must be performed by the operator; do not enable
interactive prompts. Additional CA trust may be supplied as a private mounted file and selected
by `.gitconfig` without disabling HTTPS validation. SSH transport requires a separately reviewed
key/known-hosts delivery arrangement; it is not part of this first procedure. The CI bare Git
volume is a synthetic fixture, not an IncusOS host-path requirement.

## Create the stopped controller and start deliberately

Replace `FULL_FINGERPRINT` and select unused names. `create` rejects collisions before mutations:

```sh
bash scripts/bootstrap/controller create measerve garden local tendbr0 FULL_FINGERPRINT \
  tend garden-tend-state garden-tend-bootstrap garden-controller /private/tend
```

The script preflights private-file permissions, project/pool/network, cached OCI provenance,
instance and volume collisions. It creates two operator-marked custom volumes with
`security.shifted=true`, UID/GID 1000 and mode 0700. Private stores/argument files are delivered
through the volume file API as UID/GID 1000, mode 0400. State mounts read/write at `/var/lib/tend`;
the bootstrap directory mounts read-only at `/etc/tend-bootstrap`. These are operator resources,
marked `user.tend.bootstrap`, and must not appear among Tend's managed application declarations.

The explicit empty-profile, unprivileged OCI instance uses the selected root pool and ordinary
private bridge, autostart/autorestart, UID/GID 1000, cwd `/app` and this complete native command:

```text
java @/etc/tend-bootstrap/launch.args
```

Incus `oci.entrypoint` replaces the full executable/argument vector; there is no separate Incus
Docker `CMD` property to set. Only file paths appear in Incus config. Passwords, private keys and
Git credentials remain file content, never config values or environment variables.

The bootstrap script leaves the instance stopped. Inspect its explicit devices, image revision
and ownership ledger, verify the argument files' project/owner/state directory privately, then:

```sh
incus start measerve:tend --project garden
incus exec measerve:tend --project garden -- stat -c '%u:%g:%a' /var/lib/tend
incus exec measerve:tend --project garden -- cat /var/lib/tend/last-success
```

Require the exact desired main SHA in `last-success` and observe Tend-owned applications in the
intended project. Inspect the actual Java process UID/GID (Incus supplies a small OCI PID 1);
require 1000/1000, a writable private state mount and readable/read-only bootstrap files. Never
dump Java arguments, `/proc/*/environ`, the secret directory or bootstrap volume into diagnostics.
Watch reports generic expected failures and retries; Git operations are bounded at 30 seconds
and Incus waits at 45 seconds. A failed fetch does not reconcile cached state. Incorrect trust,
authorization and private-file access need operator correction, not a TLS bypass.

## Stop/start, replacement and partial-bootstrap recovery

Stop/start retains both volumes. `last-success`, Git mirror and generated secrets/signing keys
belong to the state volume. `last-success` is observed status, not authoritative desired state.
One process locks its state directory; bootstrap additionally requires one writer for the scope.

For replacement, record the current image, instance config and **both** volume names. Stop the
old controller and verify it is stopped before removing only that instance/root disk:

```sh
incus stop measerve:tend --project garden --timeout=60
incus delete measerve:tend --project garden
bash scripts/bootstrap/controller reuse measerve garden local tendbr0 NEW_FULL_FINGERPRINT \
  tend garden-tend-state garden-tend-bootstrap garden-controller /private/tend
incus start measerve:tend --project garden
```

`reuse` requires matching recorded operator markers, shifted/private volume configuration and
no consumers on either volume. It creates only the new stopped instance/devices; it never
rewrites private inputs or deletes a volume. A new owner marker, missing volume or another
consumer is a failure. Retain the same ownership identity, project, secret declarations and
state directory. Changing them is not an image replacement. Reobserve exact main and generated
identity after replacement. Stop the new controller before rollback to the recorded old image.

If initial bootstrap fails partway, completed operator resources remain. Reobserve operations
after a timeout; do not assume a timed-out request did nothing. Verify marker/configuration and
each private file's metadata **without printing bytes**. Complete any missing files using the
private volume file API and remove only a confirmed stopped partial instance, then use `reuse`.
Do not delete/recreate the state volume as recovery. Resource collisions with unrelated owners
must be resolved explicitly. Host reboot and ZFS commissioning remain actual-IncusOS #19 gates.

## Verification and evidence

`python3 scripts/bootstrap/test-controller` runs seven offline operator safety checks with a
fake Incus executable: private-input rejection before API access, collisions before writes,
create versus retained-volume reuse, ownership and one-writer rejection, and a stopped result.
It has no live credentials. Compile/offline checks remain the [README commands](../../../README.md).

The separate `ControllerSmokeTests` reuses the disposable Incus/Minau infrastructure. It runs
the actual operator bootstrap script and native OCI watch process over project-restricted HTTPS
from an ordinary private bridge. A read-only custom volume carries bare Git; the fixture copies
objects before refs when publishing another main commit. No runner-launched Tend process is
used. The case checks no-op stability, new main, retained last-success/random/RSA and application
mount bytes after restart/replacement, Git failure without stale reconciliation, wrong trust and
revoked API authorization followed by recovery. UID/mount/provenance evidence is public; secret
bytes, console and TLS store passwords are inspected privately and excluded from uploads.

Run the full integration job once for a coherent final change; rerun only failures or changed
evidence. See [integration reproduction](incus-smoke.md) for exact steps. CI's disposable dir
pool and synthetic trust/Git do not replace IncusOS/ZFS, public Git/DNS or host-reboot acceptance.

The bootstrap command combines `java.args`, the reviewed `cmd/run` and `controller.args` into
one private `launch.args`, delivered with UID/GID 1000 and mode 0400. This ordering is required:
the JDK stops expanding argument-file references after the module main target, so appending
`@controller.args` after `@cmd/run` passes that filename literally to Tend. Reuse preserves the
complete launch file along with the original private inputs; changing launcher configuration
requires a stopped operator bootstrap update, not a controller-generated secret change.
