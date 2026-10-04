# Fresh IncusOS rebuild acceptance

The final disposable case prepares the pinned `mitschwimmer/digital-garden` recipe, commits its
actual root `incus.xml` and referenced files to a private bare main, and runs the imported Tend
OCI watch process as the **only application writer**. It starts in a new application project;
operator scripts create only project/network/cache/private inputs and Tend itself. There is no
second hand-maintained seven-workload XML graph and no runner-launched Tend in this case.

The CI input declares linux/amd64 cache fingerprints, private network/IPs, LAN test attachment,
local hosts/CA and a controlled CPU model protocol. That protocol uses the already-pinned Python
OCI runtime, removes GPU/KFD and emits synthetic completions; it does not run llama.cpp or load
real weights. Caddy creates the actual local CA; the case commits that **public** certificate and
activates consumer trust through Git before login. These substitutions and image inputs are
recorded in `rebuild-provenance.json`; selected/updated Git main revisions are recorded separately.
CI storage is a disposable directory pool, not production ZFS. Local certificates are not ACME.

Existing component suites retain their detailed Grafana, Pi, passkey and protocol checks. The
composition adds only the missing cross-application path: all seven from actual watch, bounded
agent/cloud-init/HTTP readiness, composed Grafana OIDC/dashboard/datasource query, actual Open
WebUI OIDC/model discovery/streaming, protected Pi HTTP/WebSocket, outsider denial, stable
convergence, one affected Git activation, Git failure/recovery and retained controller secrets.
Private browser observations and application logs stay outside uploaded evidence. No generic
readiness/dependency scheduler is added to Tend.

`scripts/incus-smoke/test` selects component suites sequentially and stops completed default-project
workloads before composition to bound memory. The opt-in Actions integration job has a finite
40-minute ceiling. Run it once after the reviewed stack is coherent; rerun failures only when a
fix invalidates that evidence. A failure is not acceptance, and a compiled fixture is not a live pass.

## Remote-only operator procedure

1. Record the selected Tend/garden commits and exact OCI/VM cached fingerprints. Complete the
   [IncusOS bootstrap ledger](incusos-bootstrap.md) using the actual ZFS pool, LAN attachment/MAC,
   subnet/static IPs, public DNS/router, Incus TLS hostname and GPU PCI. No production values are
   inferred from CI. Prepare/review the resulting garden state before main activation. The older
   illustrative digital-garden PR #1 is not an installation candidate.
2. Verify new ZFS volumes and shifted UID/GID/mode file delivery through the remote API. Preserve
   operator prerequisites and newly created retained data; do not adopt old application state.
   Check VM agent/KVM and GPU firmware/kernel/device visibility through IncusOS APIs. No host
   shell package installation, Terraform, migration/import or credential restore.
3. Provision **new** private users, a separate metrics-only client certificate and optional SMTP
   through [private inputs](private-inputs.md). Include an approved admins + ai-users account for
   first Open WebUI bootstrap. Store controller TLS/Git settings privately; no secret bytes in Git.
4. Build/import the exact reviewed Tend OCI artifact, bootstrap its retained state/private mounts
   and start watch. Require bounded last-success and all seven instances. Wait separately for Pi
   agent/cloud-init/systemd and every service health endpoint. Do not claim inference from health.
5. From the actual routed public hosts, verify ACME certificate chain/name/expiry and router access
   on 80/443. Test password login, **new passkey enrollment**, fresh passwordless login and group
   denials. Verify actual Grafana role/dashboard query, Open WebUI ai-users role/callback and Pi
   protected HTTP/WebSocket with shared session/workspace semantics and permitted non-Bash tools.
   Confirm Pi still reaches required internet/services normally; do not impose a model-only ACL.
6. Verify all private metrics targets and verified Incus metrics-only TLS. Confirm that its client
   cannot administer instances. Test both unloaded-model scrape requests with `autoload=false`;
   no download/model load/switch may occur. Keep Prometheus/model management endpoints private.
7. Execute every [AMD model commissioning check](llama.md): actual UID-1000 render/KFD access,
   correct ROCm image/driver/firmware, VRAM residency, downloaded content provenance, declared
   context/cache/thinking/MTP, successful completions/streaming and switching for each preset.
   Observe the intended model for real Open WebUI and Pi requests. Record failures as blockers;
   never silently switch to CPU, another model or reduced context.
8. Make one reviewed Git configuration change; require activation only of its consumer. Repeat
   stable convergence, temporarily unavailable Git and recovery. Restart Tend; preserve generated
   client/session/signing identities and last-success. Verify newly created Pi workspace/session,
   Authelia passkey/user identity, Grafana state and Prometheus historical sample persistence.
9. Reboot through the authorized IncusOS management workflow after recording a finite recovery
   bound. Require Tend and all boot-autostart workloads to return, agents/health/group decisions
   to recover, stable secrets/new data to persist and actual GPU load/completion to work again.

Keep raw logs, session content, prompts, cookies, tokens, codes and private keys private. Publish a
sanitized ledger with revisions, image sources/fingerprints/platforms, actual OS/kernel/firmware,
network/storage/GPU identity, test substitutions and each bounded outcome. Generated secret
backup/export, image upgrade/replacement, old-data migration and pruning remain outside this gate.

## Completion ledger

| Gate | Required evidence / current boundary |
|---|---|
| Offline preparation/projection | Exact public recipe, duplicate address/model/context guards and Tend XSD/semantic/mock pass |
| Disposable OCI composition | Successful Actions run and sanitized artifacts at the selected stack revision |
| Reviewed production state | Operator public site values and prepared installation commit; currently not supplied |
| ZFS/LAN/TLS/public ACME | Actual reset-host remote observations; pending operator execution |
| RX 9060 XT/ROCm/presets | Actual hardware results for both models; pending operator execution |
| New passkeys/data and host reboot | Fresh production enrollment, retained data and reboot evidence; pending operator execution |

Issue #19 and tracker #11 stay open until manual gates pass or are explicitly reported as blockers.
A green CPU runner must never be presented as full production/homelab acceptance. The preparation
PR is reviewable without production inputs; it does not authorize incomplete main activation.
