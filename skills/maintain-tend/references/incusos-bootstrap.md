# Remote bootstrap on a fresh IncusOS host

This is the operator procedure for [issue #12](https://github.com/archaic-java/tend/issues/12),
the first prerequisite of [the rebuild milestone](https://github.com/archaic-java/tend/issues/11).
Its output is a completed [bootstrap manifest](bootstrap-manifest.example.md), reviewed against
the live host. It does not launch Tend or the seven applications. Controller OCI installation
is [#13](https://github.com/archaic-java/tend/issues/13); private user/credential delivery is
[#14](https://github.com/archaic-java/tend/issues/14). The final host commissioning is
[#19](https://github.com/archaic-java/tend/issues/19).

All commands below run on the authorized **operator workstation**. Incus operations target the
remote API; `incus exec` is used only inside disposable probe guests. IncusOS has no host shell.
Do not run the Ubuntu `scripts/incus-smoke/install` or `prepare` scripts on this host. No
Terraform/OpenTofu, adoption, old credentials or restored application data is involved.

## Contents

- [Choose the scope and keep private material separate](#choose-the-scope-and-keep-private-material-separate)
- [Inventory and verify TLS](#inventory-and-verify-tls)
- [Establish explicit prerequisites](#establish-explicit-prerequisites)
- [Cache immutable image inputs](#cache-immutable-image-inputs)
- [Probe the actual storage, network and hardware](#probe-the-actual-storage-network-and-hardware)
- [Validate the manifest and hand off](#validate-the-manifest-and-hand-off)
- [Failure diagnostics and evidence](#failure-diagnostics-and-evidence)

## Choose the scope and keep private material separate

Install a current Incus client, OpenSSL, curl, skopeo, umoci and GNU `timeout` on the workstation.
The client needs `admin os` and `storage volume file` commands. OCI conversion tools belong
on the workstation when required by the client; IncusOS supplies its own application runtime.
The Pi installer currently requires x86_64, so this rebuild selects `linux/amd64` images on
an `x86_64` host. Other architectures need separately reviewed Pi and GPU inputs.

Begin with an already trusted remote. If reset removed that trust, provision the authorized
client certificate in the installation seed; use the official
[IncusOS access procedure](https://linuxcontainers.org/incus-os/docs/main/getting-started/access/).
Verify the new server certificate through the installation console/authorized operator before
accepting it. A reset is not permission to trust an unexpected certificate.

Use a private workstation directory outside any Git checkout (`umask 077`). Copy the manifest
template there and fill it as each gate passes. It contains public configuration and acceptance
results only. Client keys, TLS store passwords, user files, SMTP passwords and metrics keys
remain in a separate private delivery directory. Do not capture `--debug`, shell tracing,
full credential/config dumps or IncusOS security state in evidence.

Set reviewed values in the workstation shell; these are examples, not discovered host facts:

```sh
set -eu
umask 077
TEND_REMOTE=measerve
TEND_PROJECT=garden
TEND_POOL=local
TEND_BRIDGE=tendbr0
TEND_CIDR=10.78.0.1/24
TEND_DNS=garden.internal
```

Record an ownership ledger before mutations:

| Owner | Resources |
|---|---|
| IncusOS/operator | Host configuration, API trust/listener, pool, default-project managed bridge and LAN attachment, project, cached images, DHCP reservation, DNS/router rules |
| Operator bootstrap (#13/#14) | Tend instance, its persistent state/credential volumes and client authorization; unavoidable private inputs |
| Tend | Seven application instances and their declared application/configuration/secret volumes |
| Temporary operator probe | Exactly named probe guests and volumes created below; remove only those resources after testing |

On a repeat pass, **inspect before create**. Reuse only a resource listed in the manifest with
matching identity, owner and configuration. If a name exists but is absent from the ledger,
stop; do not silently adopt, edit, delete, change project features or replace image aliases.
An inspection error other than a confirmed missing resource is a failure, not permission to
create. Repeat probes use new names. This is a deliberate operator procedure, not an automatic
idempotent installer.

## Inventory and verify TLS

Read the actual server and IncusOS state through the selected remote:

```sh
incus version "$TEND_REMOTE:"
incus query "$TEND_REMOTE:/1.0"
incus info "$TEND_REMOTE:" --resources
incus admin os show "$TEND_REMOTE:"
incus admin os system network show "$TEND_REMOTE:"
incus admin os system kernel show "$TEND_REMOTE:"
incus admin os application list "$TEND_REMOTE:"
incus project list "$TEND_REMOTE:"
incus storage list "$TEND_REMOTE:"
incus network list "$TEND_REMOTE:" --project default
```

Record the IncusOS release, Incus client/server versions, kernel, architecture, management
addresses/port, LAN interface or bond MAC and roles, pool driver/capacity, bridge CIDR/DNS and
GPU vendor/product/full PCI address. Inspect `/1.0`'s `api_extensions` and
`environment` (including `server_supported_instance_types`). Require at least `projects`,
`instance_oci`, `instance_oci_entrypoint`, `file_storage_volume`, `storage_shifted` and
`storage_initial_owner`. Both containers and virtual machines must be supported. The probes
below verify the selected combination; an extension list alone is not storage/GPU evidence.

Choose a stable HTTPS origin for Tend, reachable from its future private bridge NIC. It must
use a DNS SAN or IP SAN on the trusted server certificate. Host management IPs are often
reachable through the ordinary NAT bridge, but verify that path from a guest. Do not rely on
LAN macvlan communication with the host or a Caddy public route to reach the Incus API.

From the public server certificate already verified by the operator:

```sh
openssl x509 -in /private/server.crt -noout -fingerprint -sha256 -ext subjectAltName
```

Record the certificate fingerprint and chosen SAN. Set `TEND_API_ORIGIN` to that HTTPS origin.
Use privately provisioned client certificate/key files and that trust anchor for a workstation
request (paths below are placeholders). Keep the response private; `/1.0` must show trusted
authentication. curl validates the origin's hostname and certificate chain:

```sh
curl --fail --silent --show-error --connect-timeout 10 --max-time 30 \
  --cacert /private/server.crt --cert /private/client.crt --key /private/client.key \
  "$TEND_API_ORIGIN/1.0" > /private/verified-api.json
```

A client certificate accepted by the Incus CLI does not prove JSSE hostname validation.
Prepare Tend's PKCS12 identity/trust stores privately in #13; test the same origin using
Tend's actual Java client before starting watch. Never use `-k`, trust-all code, a hostname
verification bypass or credentials embedded in the origin.

## Establish explicit prerequisites

Inspect the selected pool with `incus storage show "$TEND_REMOTE:$TEND_POOL"` and
`incus storage info "$TEND_REMOTE:$TEND_POOL"`. With IncusOS installation defaults, `local`
is a ZFS pool. Deliberately record and use that pool if suitable. If no usable pool exists,
provision storage through the [IncusOS storage API procedure](https://linuxcontainers.org/incus-os/docs/main/tutorials/storage-preparing-volume-incus/)
using reviewed disks and the [storage reference](https://linuxcontainers.org/incus-os/docs/main/reference/system/storage/).
Stop until the resulting Incus pool is visible; do not invent a host filesystem path, create
a loop-backed substitute or reformat a disk to get past this gate.

Create a dedicated project only after confirming its absence:

```sh
incus project create "$TEND_REMOTE:$TEND_PROJECT" \
  -c features.images=true -c features.profiles=true \
  -c features.storage.volumes=true -c features.networks=false
incus project show "$TEND_REMOTE:$TEND_PROJECT"
```

`features.networks=false` uses networks in the default project; ordinary bridges do not
require per-project OVN. Images and custom volumes remain in `garden`. All subsequent
image, volume and guest operations explicitly select `--project "$TEND_PROJECT"`.
Do not mutate the default profile; application instances and probes use no profiles and
explicit root disks/NICs. Record any limits/restrictions and confirm they permit the required
GPU/unix-char devices and volumes. Controller authorization is a separate #13 gate.

Check the private CIDR against host/LAN/VPN/router routes and all existing Incus networks.
Confirm proposed static addresses and MACs are unused, including DHCP leases and router
reservations; a failed ping is not proof of absence. Allocate unique addresses for Authelia,
Prometheus, Grafana, llama, Open WebUI and Pi plus temporary probes. Caddy's private NIC and
Tend may use DHCP. Exclude the gateway, network and broadcast addresses. Create only a
confirmed absent managed bridge, in the default project:

```sh
incus network create "$TEND_REMOTE:$TEND_BRIDGE" --project default --type=bridge \
  ipv4.address="$TEND_CIDR" ipv4.nat=true ipv4.dhcp=true \
  ipv6.address=none dns.domain="$TEND_DNS" dns.mode=managed
incus network show "$TEND_REMOTE:$TEND_BRIDGE" --project default
incus network list-leases "$TEND_REMOTE:$TEND_BRIDGE" --project default
```

Alternatively, record and explicitly approve a matching existing bridge such as `incusbr0`.
Keep its actual CIDR, DHCP and DNS policy; do not blindly overwrite it. Normal internet and
service access is required for Pi, image/model downloads and public ACME. OVN and egress ACLs
are not prerequisites. Check any existing firewall/routing policy that could block that access.

For Caddy's LAN NIC, use the actual IncusOS interface/bond with the `instances` role:

```sh
incus admin os system network edit "$TEND_REMOTE:"
incus network list "$TEND_REMOTE:" --project default
```

Preserve the current management/cluster roles and addresses when adding `instances` to the
chosen LAN attachment; retain console recovery access during a network change. Follow
[direct attachment](https://linuxcontainers.org/incus-os/docs/main/tutorials/network-direct-attach/).
Record the resulting **unmanaged bridge name**, verified on this host. The later Caddy XML
uses `type=nic`, `nictype=bridged`, `parent=<that bridge>` and the reserved `hwaddr` for its LAN
device, plus `network=<private bridge>` for its second NIC. Do not copy the old
`macvlan`/`enp129s0` combination or change the default profile. A managed physical-network
wrapper is optional; if chosen, record its network name and use `network=<name>` instead.

Reserve Caddy's stable, unique locally administered unicast MAC in router DHCP and record
its LAN address. Configure public `auth`, `grafana`, `ai` and `pi` hostnames to that router's
public address (the AI hostname may use a different domain). Forward TCP 80/443 to Caddy's
reserved address; UDP 443 is optional HTTP/3. Publish AAAA only if its IPv6 path also works.
Keep 8443 private. Internal clients need working DNS and either router hairpin routing or
split DNS to Caddy. Authelia/backend consumers must resolve/reach their HTTPS issuers.
Record the bridge resolver IP/domain and test service-name resolution from a probe below.
Public ACME/router reachability is an actual-site acceptance in #19, after Caddy starts.

## Cache immutable image inputs

The audited source is [homelab at 8fc5449](https://github.com/mitschwimmer/homelab/tree/8fc54492c6d75d9713061703c5a6667347e1481b).
Resolve these exact tags to immutable digests on the workstation. Tags may move; keep the
reviewed digest in the manifest and use it for all copies. The initial list is:

| Workload | Registry/repository and audited selection |
|---|---|
| Caddy | docker.io/library/caddy:2 |
| Authelia | docker.io/authelia/authelia:4.39.28 |
| Grafana | docker.io/grafana/grafana:13.1.0 |
| Prometheus | docker.io/prom/prometheus:v3.12.0 |
| llama.cpp | ghcr.io/ggml-org/llama.cpp:server-rocm-b11277 |
| Open WebUI | ghcr.io/open-webui/open-webui:v0.11.4 |
| Pi VM | images:debian/13/cloud, virtual-machine, x86_64 |

Preserve `llama/models.ini.tftpl` from that revision, including `mimo` and `qwen36`, their exact
HF repositories/files and all tuning. Do not derive presets from this image table. The final
state belongs to digital-garden issue #2. The Tend OCI build/digest is supplied by #13; no
published Tend image is assumed here. For storage probes, additionally cache the `x86_64`
Debian 13 **system container** (`images:debian/13`); it is a probe input, not an eighth workload.

Inspect OCI inputs without private registry credentials on the command line:

```sh
skopeo inspect --override-os linux --override-arch amd64 \
  docker://docker.io/library/caddy:2
```

Record `Digest`, `Os` and `Architecture`, then inspect the digest-qualified reference again.
For a multi-platform index, also record the selected linux/amd64 child manifest digest from
`skopeo inspect --raw` and use that child digest for copying. Reject a missing linux/amd64
image or a different version; do not silently fall back to `latest`/another llama build.
If a tag moved since review, require a new reviewed selection instead of changing the manifest.

Add workstation image remotes only if absent, or inspect their URL/protocol before reuse:

```sh
incus remote list
incus remote add tend-docker https://docker.io --protocol=oci --public
incus remote add tend-ghcr https://ghcr.io --protocol=oci --public
```

Copy by reviewed digest to the target project, with a bounded workstation timeout. Example:

```sh
TEND_CADDY_DIGEST=sha256:REPLACE_WITH_REVIEWED_AMD64_DIGEST
timeout 600 incus image copy "tend-docker:library/caddy@$TEND_CADDY_DIGEST" \
  "$TEND_REMOTE:" --target-project "$TEND_PROJECT"
incus image list "$TEND_REMOTE:" --project "$TEND_PROJECT" --format json
```

Repeat for all six OCI inputs. Record each **full 64-hex Incus fingerprint**, architecture,
type and source digest from the completed copy. Incus OCI fingerprints are derived from
layers; they are not the OCI manifest digest. Match each cached image against
`incus image info <source-remote>:<repository>@<digest>` and its target metadata, including
the fingerprint, architecture and OCI properties. Use a compatible workstation architecture;
Incus's OCI client selection must agree with the reviewed linux/amd64 platform. Keep the
mapping together; never infer a source digest from a local fingerprint alone.

Resolve Debian with `incus image info images:debian/13/cloud --vm` and
`incus image info images:debian/13`. Select/record the full x86_64 fingerprints, then copy
those fingerprints (not the floating aliases):

```sh
timeout 600 incus image copy "images:$TEND_PI_FINGERPRINT" "$TEND_REMOTE:" \
  --vm --target-project "$TEND_PROJECT"
timeout 600 incus image copy "images:$TEND_PROBE_FINGERPRINT" "$TEND_REMOTE:" \
  --target-project "$TEND_PROJECT"
```

Do not enable auto-update or `--reuse`. A failed/timed-out copy must be reobserved before
retry, since the server may still be working. Check each target fingerprint with `incus image
info "$TEND_REMOTE:<full fingerprint>" --project "$TEND_PROJECT"` (`--vm` for Pi).
Reject absent images, wrong architecture/type or provenance mismatch before application deployment.
Use only these full target fingerprints in the future XML. Revalidate the cache before handing
off; image-cache retention is operator policy until native registry reconciliation exists.

## Probe the actual storage, network and hardware

Run these **only after operator authorization on the reset host**. No production credentials
are used. Reserve fresh probe names/addresses in the manifest, confirm they do not exist, and
keep the controller stopped. Probe failures block the manifest; do not weaken mount security.

Use the selected pool/bridge/project and cached Debian container. Create a small custom volume
and two files on the workstation with synthetic content. Use unique names instead of reusing
the examples on a repeat run:

```sh
TEND_PROBE=tend-bootstrap-probe-001
TEND_PROBE_VOLUME=tend-bootstrap-files-001
incus storage volume create "$TEND_REMOTE:$TEND_POOL" "$TEND_PROBE_VOLUME" \
  --project "$TEND_PROJECT" size=64MiB security.shifted=true \
  initial.uid=1000 initial.gid=1000 initial.mode=0755
printf 'public probe\n' > /private/probe-public
printf 'synthetic private probe\n' > /private/probe-private
incus storage volume file push /private/probe-public "$TEND_REMOTE:$TEND_POOL" \
  "$TEND_PROBE_VOLUME/public" --project "$TEND_PROJECT" --uid 1000 --gid 1000 --mode 0444
incus storage volume file push /private/probe-private "$TEND_REMOTE:$TEND_POOL" \
  "$TEND_PROBE_VOLUME/private" --project "$TEND_PROJECT" --uid 1000 --gid 1000 --mode 0400
incus init "$TEND_REMOTE:$TEND_PROBE_FINGERPRINT" "$TEND_REMOTE:$TEND_PROBE" \
  --project "$TEND_PROJECT" --no-profiles --storage "$TEND_POOL" --network "$TEND_BRIDGE" \
  -c security.privileged=false -c limits.cpu=1 -c limits.memory=256MiB -d root,size=2GiB
incus config device add "$TEND_REMOTE:$TEND_PROBE" files disk --project "$TEND_PROJECT" \
  pool="$TEND_POOL" source="$TEND_PROBE_VOLUME" path=/mnt/probe readonly=true
timeout 120 incus start "$TEND_REMOTE:$TEND_PROBE" --project "$TEND_PROJECT"
timeout 30 incus exec "$TEND_REMOTE:$TEND_PROBE" --project "$TEND_PROJECT" \
  --user 1000 --group 1000 -- sh -eu -c '
    test "$(stat -c "%u:%g:%a" /mnt/probe/private)" = "1000:1000:400"
    test "$(stat -c "%u:%g:%a" /mnt/probe/public)" = "1000:1000:444"
    test "$(stat -c "%u:%g:%a" /mnt/probe)" = "1000:1000:755"
    test -r /mnt/probe/private
    if touch /mnt/probe/new 2>/dev/null; then exit 1; fi
  '
timeout 30 incus exec "$TEND_REMOTE:$TEND_PROBE" --project "$TEND_PROJECT" \
  --user 65534 --group 65534 -- sh -eu -c '
    test -r /mnt/probe/public
    test ! -r /mnt/probe/private
  '
```

Repeat file push/metadata/access checks for UID:GID `472:0` (Grafana), `65534:65534`
(Prometheus) and `0:0` (Open WebUI), using a denied user that is not the owner. Also check
the volume directory's intended traversal permissions. Use a distinct new filename for each
owner (for example `private-472`); existing-file overwrites can preserve metadata despite the
push flags. Do not put real secrets in this probe.
Stop the guest, replace the test file through the volume file API (the path Tend uses), restart
and repeat the assertions, including comparison with the new synthetic contents. For this
same-owner replacement, the original UID/GID/mode must remain correct. Record the pool driver,
kernel, idmap and successful read-only
mount behavior. This tests ZFS on the actual host, rather than assuming CI's `dir` result applies.
Use a separate read/write probe volume with `initial.uid=1000`, `initial.gid=1000`,
`initial.mode=0750`, `security.shifted=true`; verify UID 1000 can write and retain a synthetic
file across stop/start. For example, after confirming the new name is free:

```sh
TEND_PROBE_DATA=tend-bootstrap-data-001
incus storage volume create "$TEND_REMOTE:$TEND_POOL" "$TEND_PROBE_DATA" \
  --project "$TEND_PROJECT" size=64MiB security.shifted=true \
  initial.uid=1000 initial.gid=1000 initial.mode=0750
incus config device add "$TEND_REMOTE:$TEND_PROBE" data disk --project "$TEND_PROJECT" \
  pool="$TEND_POOL" source="$TEND_PROBE_DATA" path=/mnt/data
timeout 30 incus exec "$TEND_REMOTE:$TEND_PROBE" --project "$TEND_PROJECT" \
  --user 1000 --group 1000 -- sh -eu -c '
    test "$(stat -c "%u:%g:%a" /mnt/data)" = "1000:1000:750"
    printf "retained probe\n" > /mnt/data/retained
  '
timeout 120 incus stop "$TEND_REMOTE:$TEND_PROBE" --project "$TEND_PROJECT"
timeout 120 incus start "$TEND_REMOTE:$TEND_PROBE" --project "$TEND_PROJECT"
timeout 30 incus exec "$TEND_REMOTE:$TEND_PROBE" --project "$TEND_PROJECT" \
  --user 1000 --group 1000 -- sh -eu -c \
  'test "$(cat /mnt/data/retained)" = "retained probe"'
```

Repeat for application data owners where required. Never test against an application or
controller volume. The read-only assertion uses a directory writable by UID 1000 on a
read/write mount, so failure cannot be explained by ordinary directory permissions alone.

Use this guest to check DNS resolution for the bridge's own registered name and the selected
API hostname (`getent hosts`), outbound HTTPS (`curl` installed inside the probe guest if
needed) to required package/model registries, and the selected API origin with only the
public trust certificate pushed into the guest. An unauthenticated TLS request may return an
untrusted API response; its certificate/hostname validation must still succeed. Confirm route
and resolver state inside the guest. A fresh VM probe must independently verify the same
internet/service paths needed by Pi. No credentials should be pushed to these guests.

Start a bounded Debian cloud VM probe from the cached Pi fingerprint with an explicit root
disk, private NIC, no profiles, 2 CPUs, 2GiB RAM and a 16GiB root:

```sh
TEND_VM_PROBE=tend-bootstrap-vm-001
incus init "$TEND_REMOTE:$TEND_PI_FINGERPRINT" "$TEND_REMOTE:$TEND_VM_PROBE" \
  --project "$TEND_PROJECT" --vm --no-profiles --storage "$TEND_POOL" \
  --network "$TEND_BRIDGE" -c limits.cpu=2 -c limits.memory=2GiB -d root,size=16GiB
timeout 120 incus start "$TEND_REMOTE:$TEND_VM_PROBE" --project "$TEND_PROJECT"
timeout 15 incus exec "$TEND_REMOTE:$TEND_VM_PROBE" --project "$TEND_PROJECT" -- true
```

Require it to start within
120 seconds and its agent to answer `incus exec ... -- true` within a total 180 seconds
(retry short commands until the deadline). Check private DNS/outbound HTTPS and a synthetic
custom-volume mount through the agent. If KVM is unavailable, enable hardware virtualization
in firmware or fix the IncusOS-supported configuration, then repeat. Do not install packages
or load modules on the host. Pi's full installer/service checks remain in #19's existing suite.

For AMD inference, inspect the recorded PCI device and kernel configuration. Enable firmware
through `incus admin os application add "$TEND_REMOTE:gpu-support"` if it is absent; inspect
its application state and any required reboot. The firmware application does not supply
drivers. Keep `amdgpu` available for the container; do not bind this GPU to VFIO for VM
passthrough. Review the [kernel API](https://linuxcontainers.org/incus-os/docs/main/reference/system/kernel/)
and [GPU firmware reference](https://linuxcontainers.org/incus-os/docs/main/reference/applications/gpu-support/).
Add only the recorded GPU to the disposable container (`type=gpu`, `gputype=physical`,
`pci=<full address>`, `uid=1000`, `gid=1000`, `mode=0660`) and a unix-char device for
`/dev/kfd` with the same ownership/mode. Require startup and readable/writable render/KFD
devices as UID 1000. Missing firmware/driver/KFD is a first-install blocker with the PCI
identity in the diagnostic. Real ROCm model loading/inference is #18/#19 acceptance; device
visibility alone is not proof of inference.

Example device setup after recording the selected full PCI address in `TEND_GPU_PCI`:

```sh
timeout 120 incus stop "$TEND_REMOTE:$TEND_PROBE" --project "$TEND_PROJECT"
incus config device add "$TEND_REMOTE:$TEND_PROBE" gpu gpu --project "$TEND_PROJECT" \
  gputype=physical pci="$TEND_GPU_PCI" uid=1000 gid=1000 mode=0660
incus config device add "$TEND_REMOTE:$TEND_PROBE" kfd unix-char --project "$TEND_PROJECT" \
  source=/dev/kfd path=/dev/kfd uid=1000 gid=1000 mode=0660
timeout 120 incus start "$TEND_REMOTE:$TEND_PROBE" --project "$TEND_PROJECT"
timeout 30 incus exec "$TEND_REMOTE:$TEND_PROBE" --project "$TEND_PROJECT" \
  --user 1000 --group 1000 -- sh -eu -c '
    test -c /dev/kfd && test -r /dev/kfd && test -w /dev/kfd
    found=false
    for device in /dev/dri/renderD*; do
      test -c "$device" || continue
      test -r "$device" && test -w "$device"
      found=true
    done
    test "$found" = true
  '
```

After recording the outcomes, stop/delete only the exact probe instances created in this pass,
then delete only their volumes after verifying no consumers remain. Keep cached inputs and
operator prerequisites. A timeout may leave a server operation running; reobserve it before
cleanup/retry. Never use wildcard deletion or cleanup from the CI scripts. Retain failed probes
for operator diagnosis if safe, explicitly recording them in the ledger.

## Validate the manifest and hand off

This is a manual acceptance gate, not Tend configuration or a new secret-input schema. Fill
every required manifest field from observation. Leave a gate pending/failed until its check
passes; placeholders or an unavailable image mean **not ready**. Record the operator, date,
reset installation identity and both source revisions. Immediately before handoff:

1. Reobserve project/features, pool/driver/capacity, bridge, LAN bridge/role/MAC reservation
   and all chosen addresses/names. Confirm no unrelated resource occupies the application or
   controller names; on repeat bootstrap, existing Tend resources require their recorded owner.
2. Recheck every image's full fingerprint, type, architecture and recorded source mapping in
   the correct project. Review the selected model preset file without changing its IDs/settings.
3. Require trusted TLS from the workstation and verified hostname/chain plus network access
   from the bridge guest; require target-driver file and data probes, KVM/agent and GPU devices.
4. Confirm private material has stayed outside Git/evidence, and the ledger lists every created
   prerequisite and any residual probe. Mark the prerequisite gate passed and sign the record.

Supply the reviewed public settings/fingerprints to digital-garden's XML author. Keep ownership
identity stable and preserve Tend's state volume once #13 creates it. The manifest is invalidated
by another reset, changed trust/endpoint, pool/driver, network/subnet, GPU binding, image selection
or host update that affects these checks; rerun affected gates before watch. An already running
controller is not authorized to adopt other resources on a repeat pass.

The next increment must verify the actual OCI controller, PKCS12 TLS and one writer before
watch. Fresh users enroll new passkeys after bootstrap; `one_factor` permits password OR
passkey. Grafana requires `admins`; Open WebUI and Pi require `ai-users`; an Open WebUI admin
needs both. Application secrets are generated by Tend. Private user/SMTP/metrics inputs follow
#14, not this public manifest. Public DNS/ACME, real GPU inference and host reboot remain
explicit final operator acceptances in #19; do not mark them passed from CPU CI.

## Failure diagnostics and evidence

| Failure before watch | Actionable response |
|---|---|
| Untrusted remote or wrong certificate/SAN | Verify reset identity through the authorized installation path; choose/fix a covered reachable origin and reissue trust privately. Never bypass TLS. |
| Missing extension, wrong architecture or no VM support | Record versions/missing capability; select a supported IncusOS release or fix hardware before deploying. |
| Existing unrecorded name, duplicate address/MAC or overlapping subnet | Stop and identify the owner/reservation; choose an unused scope. Do not overwrite or infer availability from ping. |
| Wrong project features or pool | Stop and review the scope while empty; do not mutate features on a populated project or substitute storage. |
| No image, wrong platform/type or mismatched digest/fingerprint | Reobserve the cache/source; retry the immutable copy or review a new input. Do not change the application version silently. |
| Shifted mount/start/metadata/access failure | Record driver/kernel and the failing synthetic assertion; fix compatible host storage/mapping. Do not use privileged guests or world-readable secrets. |
| No VM agent/KVM or AMD render/KFD devices | Check firmware virtualization, IncusOS GPU firmware/kernel and PCI binding; repeat the bounded probe after the required host change. |
| Guest DNS/egress/API failure | Check bridge DHCP/DNS/NAT, routing, LAN roles and router policy; keep normal Pi connectivity. |

Keep only version identifiers, resource names, public fingerprints, assertion names and pass/fail
outcomes in shareable evidence. Raw Incus/IncusOS output may contain private config or keys:
review/redact it locally and never upload the working directory wholesale. This procedure is
source-reviewed, not evidence that the user's reset host has passed it. Existing disposable CI
proves the [separate integration scope](incus-smoke.md); actual IncusOS results are recorded by
the operator in the manifest and final commissioning issue.

When maintaining this procedure, check the command syntax against the installed Incus/IncusOS
versions, the [Incus API extension list](https://linuxcontainers.org/incus/docs/main/api-extensions/)
and [ZFS reference](https://linuxcontainers.org/incus/docs/main/reference/storage_zfs/).
Validate links and shell examples locally. Run affected live checks only on an explicitly
authorized disposable/reset target; documentation edits alone do not require the full CI suite.
