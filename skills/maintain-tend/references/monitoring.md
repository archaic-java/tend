# Rebuilt monitoring

Prometheus `v3.12.0` uses the reviewed upstream OCI index
`prom/prometheus@sha256:69f5241418838263316593f7274a304b095c40bcf22e57272865da91bd60a8ac`.
This digest was resolved directly through Docker Registry v2. Grafana uses the existing pinned
`13.1.0` image in [integration inputs](incus-smoke.md#inputs-and-evidence).
Import/cache both before Tend, record their full Incus fingerprints, and replace the example
fingerprints from the bootstrap ledger. Their defaults load `/etc/prometheus/prometheus.yml`
and Grafana's provisioning directory without an additional controller hook.

## Configuration and ownership

The [monitoring fragment](../../../examples/monitoring/incus.xml) and its sibling JSON-form YAML
files demonstrate the flat file model. Copy all five files into one Git directory when using
its root-level source references. Fill project/pool/network, static addresses, actual server
hostname/SAN and model labels from the ledger. This fragment must be combined with the garden's
Caddy/Authelia and Grafana OIDC registration/settings before public deployment; it adds no public
Grafana route or replacement authentication policy.

Prometheus runs as UID/GID 65534. Its data disk at `/prometheus` has shifted mapping, UID/GID
65534 and directory mode 0700. Its Git configuration is read-only and its private metrics TLS
volume uses that same identity, mode 0400 files and mode 0700 directory. The retained disk
contains the actual TSDB and WAL, not a disposable root-filesystem cache.

Grafana runs as UID 472/GID 0. Its `/var/lib/grafana` data disk has shifted mapping and directory
mode 0700. Separate generated configuration mounts hold datasource definitions at
`/etc/grafana/provisioning/datasources`, dashboard provider definitions at
`/etc/grafana/provisioning/dashboards`, and flat `incus.json` at `/etc/tend-dashboards`.
Every generated directory belongs to UID 472, so Grafana can list it. No nested file tree or
parent/child overlapping disks are required. The provider names that flat dashboard directory.
The existing dashboard is copied from audited homelab revision
`8fc54492c6d75d9713061703c5a6667347e1481b`, retaining UID `homelab-incus` and datasource UID
`prometheus`. Its datasource is provisioned as the noneditable default.

Generated administrator and encryption values remain private Tend secrets. The real consumer's
OIDC settings continue to admit `admins` and map them to GrafanaAdmin; use the established
confidential client raw/hash/signing declarations and [private user memberships](private-inputs.md).
Monitoring provisioning does not grant an OIDC role or bypass admission. The disposable
monitoring probe uses a private administrator curl argument file against the private API solely
to inspect provisioning; the independent existing browser case verifies actual Grafana OIDC.

## Scraping and model semantics

The public Git configuration declares exactly the required jobs:

| Job | Endpoint | Required behavior |
|---|---|---|
| prometheus | own private address, 9090 `/metrics` | actual process metrics |
| caddy | explicit private gateway address, 9180 `/metrics` | fixed private listener, never the LAN/public route |
| authelia | private address, 9959 `/metrics` | enable the base config's metrics telemetry server |
| grafana | private address, 3000 `/metrics` | metrics enabled independently of OIDC admission |
| incus | authorized HTTPS endpoint `/1.0/metrics` | dedicated metrics-only certificate/key plus actual server CA and SAN name |
| llama | private address, 8080 `/metrics` | retain model label, map it to query `model`, always send `autoload=false` |

[Private bootstrap](private-inputs.md#metrics-tls-and-smtp) supplies a new metrics certificate;
authorize it as `--type metrics`, record its fingerprint and confirm it cannot list administrator
instance resources. It must differ from the controller certificate. `tls_config` explicitly names
`ca_file`, `cert_file`, `key_file` and `server_name`; hostname/SAN verification remains enabled.
Never reuse administrator material or set `insecure_skip_verify`. Missing/misowned private volumes
fail preflight before mutations; invalid issued material must leave the consumer stopped until
corrected. Only the operator can replace those bytes through the stopped-writer procedure.

An unloaded/unavailable llama model may legitimately report `up=0`; this is not the same as a
broken Prometheus/Grafana/Incus target. Monitoring must not download, load or switch a model.
A loaded model must be checked with real model metrics during [llama commissioning issue #18](https://github.com/archaic-java/tend/issues/18).
The source model label and relabel-to-parameter rule keep multiple configured models distinct.

## Acceptance evidence

Offline Minau reads the published flat fragment through the real Git/XML/reconciliation adapters,
checks the six-job contract, explicit TLS/model parameters, Grafana/Prometheus configuration UID,
idempotence and preserved operator TLS ownership. The disposable native ingress case then adds
actual Prometheus and Grafana OCI consumers. It requires the five available service jobs up,
checks the idle model's label/autoload query and down state, observes metrics-only API privilege,
and rejects both an unenrolled certificate and unrelated server trust with recovery.
Grafana's authenticated API must report the provisioned datasource/dashboard and return an actual
scraped Incus query through that datasource. No-op passes preserve both processes. Restart must
recover a pre-restart TSDB sample and preserve generated Grafana identity/provisioning.
Private keys, administrator/encryption secrets, curl credential files and raw Grafana logs are
excluded from artifacts; public configs, scrape health and redacted assertions are retained.

On the reset host, bound first target readiness to 90 seconds for each enabled job, verify the
server's real certificate/SAN and metrics privilege, then inspect the same datasource/dashboard
through an admitted Grafana OIDC session. Preserve both data disks and controller secret state
across activation/replacement. Public DNS/ACME, actual AMD model metrics and host reboot remain
separate operator acceptance rather than claims from the disposable runner.
