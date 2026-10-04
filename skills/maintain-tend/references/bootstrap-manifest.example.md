# Fresh IncusOS bootstrap manifest

Copy outside Git, then complete using [the remote procedure](incusos-bootstrap.md).
This is an operator acceptance record, not a Tend input file. Empty values and `pending`
gates deliberately prevent declaring the bootstrap ready. Store no credentials here.

## Installation and ownership

| Field | Observed/reviewed value |
|---|---|
| Operator and UTC date | |
| Reset installation identity/date | |
| Tend procedure commit | |
| Audited homelab commit | 8fc54492c6d75d9713061703c5a6667347e1481b |
| Reviewed digital-garden commit (when authored) | |
| Incus client / server / IncusOS release / kernel | |
| Architecture / OCI platform | x86_64 / linux/amd64 (verify) |
| API extensions and container/VM support checked | |
| Authorized workstation remote | |
| Controller HTTPS origin / certificate SAN / public SHA-256 fingerprint | |
| Project / explicit features / limits and restrictions | |
| Stable Tend ownership identity (for #13) | |
| Operator-owned existing prerequisites approved for reuse | |
| Operator-owned resources created this pass | |
| Exact temporary guest/volume names; cleanup outcome | |
| Name/address collisions checked; observation time | |

## Storage, network and hardware

| Field | Observed/reviewed value |
|---|---|
| Pool / driver / usable capacity / capacity budget | |
| Private managed bridge (default project) | |
| Gateway CIDR / NAT / DHCP / reserved range | |
| DNS domain / resolver IP / guest resolution result | |
| Management endpoint / LAN addresses and routes | |
| IncusOS LAN interface or bond / MAC / roles | |
| Resulting LAN bridge name / chosen Caddy NIC shape | |
| Caddy stable MAC / DHCP reservation / LAN IP | |
| Controller private NIC / API reachability result | |
| AMD vendor/product / full PCI address / kernel driver | |
| gpu-support state / render and KFD UID 1000 access | |
| Hardware virtualization / bounded VM boot and agent result | |
| Target-driver shifted read-only file checks (all owners) | |
| Read/write data ownership and persistence checks | |

| Private allocation | Address / MAC or DHCP policy |
|---|---|
| Authelia | |
| Prometheus | |
| Grafana | |
| llama.cpp | |
| Open WebUI | |
| Pi | |
| Caddy private NIC / Tend / temporary probes | |

| Public route | Hostname / DNS A/AAAA / router destination |
|---|---|
| auth | |
| grafana | |
| ai | |
| pi | |
| TCP 80/443, optional UDP 443; internal hairpin or split DNS | |

## Cached image provenance

OCI rows require both the reviewed source digest (and platform child digest if an index)
and the full local Incus fingerprint; they are different identities. VM/system-container
rows require the immutable source fingerprint, image-server URL and local fingerprint.
For every row, verify target project, architecture, image type and current availability.

| Input | Reviewed immutable source / platform | Full cached fingerprint / project / type |
|---|---|---|
| Caddy (library/caddy:2) | | |
| Authelia (authelia/authelia:4.39.28) | | |
| Grafana (grafana/grafana:13.1.0) | | |
| Prometheus (prom/prometheus:v3.12.0) | | |
| llama.cpp (ggml-org/llama.cpp:server-rocm-b11277) | | |
| Open WebUI (open-webui/open-webui:v0.11.4) | | |
| Pi (debian/13/cloud, VM) | | |
| Temporary storage/network probe (debian/13, container) | | |
| Tend controller (supplied and verified by #13) | pending #13 | pending #13 |

Model presets reviewed from `llama/models.ini.tftpl` at the audited homelab commit:
`mimo` and `qwen36`, with exact HF repositories/files and all settings retained. Record
the reviewed file hash and final digital-garden path here:

## Gates and handoff

| Gate | pending / failed / passed; date and concise evidence |
|---|---|
| Host inventory, scope and collision checks | pending |
| Trusted TLS and covered origin from workstation | pending |
| Bridge guest TLS/route/DNS and normal outbound access | pending |
| All application and probe inputs cached by immutable identity | pending |
| Actual pool shifted file UID/GID/modes, denial and read-only checks | pending |
| File replacement/restart and read/write data retention | pending |
| KVM / Debian cloud VM agent / VM network and mount checks | pending |
| AMD firmware / selected GPU and KFD device access | pending |
| Probe cleanup and complete operator ownership ledger | pending |
| Prerequisites reobserved immediately before handoff | pending |
| #12 prerequisite acceptance: operator signature/date | pending |

The above gates authorize only the prerequisite handoff. Keep the following later gates
separate, and do not start watch until #13/#14 and the reviewed state are ready:

| Later gate | Status / owning issue |
|---|---|
| Tend OCI, PKCS12 TLS, state volume, project authorization, one writer | pending #13 |
| Private fresh users and unavoidable operator credentials | pending #14 |
| Seven-workload XML and model configuration | pending digital-garden #2 |
| Public DNS/router/ACME TLS, real inference, services and host reboot | pending #18/#19 |
