# Tend resource model

Tend uses the Incus project, instance, device and custom-volume model for infrastructure. Small
application requirements sit beside those resources: Configuration, Secret, Ingress and Egress.
They are named declarations in `incus.xml`, not a Kubernetes API server or a second scheduler.
The controller fetches `main`, resolves all inputs at one immutable commit, validates them and
lowers them into Incus resources before mutation. Invalid references fail the whole attempt.

| Kubernetes concept | Tend declaration | Incus/application implementation |
| --- | --- | --- |
| Namespace | `<incus project="garden">` | Existing Incus project; bootstrap owns project creation |
| Pod/workload | `<instance>` with explicit `<device>` children | Incus instance, image fingerprint, config, devices, running/stopped state |
| PersistentVolume | `<volume>` and a disk device | Named custom filesystem volume; retained when removed |
| ConfigMap | `<configuration>` with Git file references; instance `<mount configuration="…">` | Per-consumer private volume and read-only disk; restarts consumer when content changes |
| Secret | `<secret>`; instance `<mount secret="…">` | Controller-generated random value, private controller storage, private file on a read-only disk |
| Ingress plus authorization | `<ingress-gateway>` and `<ingress>` | Caddy host routes, Authelia forward authentication and group access rules |
| Egress NetworkPolicy | `<egress instance="…" device="…">` | Incus network ACL attached to the declared OVN NIC |

## Configuration and secrets

```xml
<secret name="session" bytes="32"/>
<configuration name="demo-settings">
  <file path="/service.conf" source="examples/service.conf"/>
</configuration>
```

After its normal Incus devices, an instance declares consumers:

```xml
<mount name="settings" configuration="demo-settings" pool="pool"
       path="/etc/demo" uid="1000" gid="1000" mode="0644"/>
<mount name="session" secret="session" pool="pool"
       path="/run/secrets/session" uid="1000" gid="1000" mode="0400"/>
```

A secret is delivered as `value` within the mounted directory, here
`/run/secrets/session/value`. Git contains the generator and reference, never the generated value.
`bytes` means random bytes before URL-safe Base64 encoding. No implicit rotation occurs when
parameters change. Imports, RSA keys, derived password hashes and backups/exports are later work.

A configuration contains root-level files sourced from regular Git files. The mount owns UID,
GID and mode for every file. Defaults are UID/GID 0 and mode 0400; explicitly use 0644 for a normal
public configuration. Secret modes exclude group/other access. Generated volume directories are
0700 with the consumer's UID/GID. Consumers cannot write these disks. A mount must reference
exactly one resource; duplicate device names and overlapping mount paths are rejected.

Names for backing volumes are deterministic, bounded `tend-` names derived from instance,
mount, ownership and file set. Changing the file set or owner selects another volume, detaching
the old one so removed files do not remain visible. Content updates repair the same volume and
invalidate activation before writing. Each consumer has its own volume, avoiding shared ID-map
assumptions. Old volumes are retained; garbage collection is outside this proof of concept.
Declared Incus volumes and their root-level files remain available for the existing offline slice.

## Ingress

```xml
<ingress-gateway instance="caddy" pool="pool" path="/etc/caddy"
                 authorization-instance="authelia" authorization-device="eth0"
                 authorization-path="/etc/tend-authorization"
                 authorization-uid="1000" authorization-gid="1000"/>
<ingress name="pi" host="pi.example.org" instance="pi" device="eth0" port="3001">
  <authorization policy="two_factor"><group name="ai-users"/></authorization>
</ingress>
<ingress name="auth" host="auth.example.org" instance="authelia" device="eth0" port="9091">
  <public/>
</ingress>
```

There is one explicitly bound Caddy gateway and one Authelia instance. Backend and authorization
references resolve declared static `ipv4.address` values on named NIC devices. Literal DNS hosts
must be unique. Each route explicitly chooses public access or authorization; protected routes
require at least one group. Groups are alternatives. Policy defaults to `two_factor`; `one_factor`
is an explicit choice. Caddy strips client identity headers before forwarding and uses its native
`forward_auth` directive. DNS and certificate reachability remain operator prerequisites.

Tend mounts `Caddyfile` at the gateway path and `access-control.json` at the authorization path.
Gateway UID/GID and authorization UID/GID are explicit optional attributes, defaulting to 0.
Authelia's declared `environment.X_AUTHELIA_CONFIG` must end with that generated JSON file, e.g.
`/config/configuration.yml,/etc/tend-authorization/access-control.json`. The base configuration
must provide sessions, authentication backend, storage, notifier and any OIDC settings. It must
not duplicate `access_control`. The chosen images/entrypoints must actually load these paths;
command-line `--config` must not override Authelia's environment binding. Tend does not inspect
process arguments inside an image.

Generated Authelia access control defaults to deny. With no protected routes, a wildcard deny
rule keeps its configuration valid. Authorization is reconciled and activated before the gateway.
Activation currently means completed Incus start/stop operations, not application readiness.
Every change uses restart activation; reload hooks are deferred.

This authorization protects requests through Caddy. It does not prevent clients reaching a backend
IP directly. Network topology/firewall enforcement of that boundary needs its own validation.
OIDC login in Grafana/Open WebUI is a distinct requirement: proxy authorization does not register
OIDC clients, enforce their policies or generate their matching plaintext/hash credentials.

## Egress

```xml
<egress name="grafana-outbound" instance="grafana" device="eth0">
  <allow address="10.20.0.11/32" protocol="tcp" port="9091"/>
  <allow address="10.20.0.12/32" protocol="tcp" port="9090"/>
  <allow address="10.20.0.1" protocol="udp" port="53"/>
</egress>
```

An egress declaration is an allowlist with default rejection on one NIC. An empty allowlist is
valid. Destinations are literal IPv4 addresses/CIDRs and TCP/UDP destination ports; hostnames,
selectors and dynamic DNS expansion are not supported. IPv6 has no explicit allow rules in this
slice. Incus's stateful ACL behavior and baseline network services still apply.

The NIC must use a declared `network` referencing an existing managed OVN network. There is one
policy per NIC. Tend owns that NIC's `security.acls` and both default-action settings: egress
reject, ingress allow. A shared network ACL or manually supplied `security.acls*` settings is
rejected before mutation to avoid ambiguous combined rules. Tend does not create networks.
ACL ownership uses `user.tend.owner`, and updates use ETags. Unrelated ACL configuration survives.

This scopes enforcement to the named NIC, not to every interface or process in an instance.
Bridge, macvlan, host networking and arbitrary extra NICs require separate consideration. The
current homelab uses a bridge; this experimental adapter therefore needs a separately bootstrapped
OVN network. Supporting bridge NIC ACLs is possible later, but requires tests for their documented
baseline-service and firewall differences. Restrictive egress rules must explicitly account for
service dependencies, DNS and outbound services such as certificate issuance.

## Scope and recovery

The active digital-garden state remains empty. Examples are review fixtures with placeholder
fingerprints, not a migration or a deployable reproduction of homelab. Current homelab requirements
include Caddy, Authelia, Grafana, Prometheus, llama, Open WebUI and pi. This model gives those
applications configuration, credential references, HTTP entry points and network dependencies;
it does not yet implement registry pulls, readiness, nested configuration trees, GPU validation,
RSA generation, hash derivation, OIDC client generation or metrics-specific Caddy configuration.

Removal retains whole instances, volumes and ACLs. Removing mount/device entries from a retained
instance detaches previously managed devices. Removing ingress entries while keeping the gateway
binding regenerates both policies. Removing the gateway binding or its instances retains the old
resources and must not be used as an authorization revocation procedure. Deletion/pruning and
secret rotation need an explicit later contract.

Offline tests verify XML validation, resource projection, API requests, ownership, conditional
updates, drift and failed-operation recovery. The separate real integration suite verifies Incus
storage semantics, OVN packet filtering, Caddy startup and public-route TLS. Authelia startup and
one-factor proxy group authorization through password and passkey login now have real coverage.
The passkey fixture requires discoverability and user verification, but `one_factor` permits
password login too; Tend does not enforce exclusive use of passkeys. Two-factor and OIDC application
integration remain unverified.

## Authoritative adapter contracts

- [Incus network ACLs](https://linuxcontainers.org/incus/docs/main/howto/network_acls/)
- [Incus REST specification](https://github.com/lxc/incus/blob/main/doc/rest-api.yaml)
- [Incus volume ownership options](https://linuxcontainers.org/incus/docs/main/reference/storage_dir/)
- [Caddy forward authentication](https://caddyserver.com/docs/caddyfile/directives/forward_auth)
- [Authelia Caddy integration](https://www.authelia.com/integration/proxies/caddy/)
- [Authelia configuration files and merging](https://www.authelia.com/configuration/methods/files/)

## Managed file volumes and ID mapping

A low-level volume containing managed files must declare `security.shifted=true`. Generated
configuration, secret and gateway volumes use it automatically. Incus applies an idmapped mount
instead of rewriting on-disk UIDs/GIDs at attachment, so the file API and container see consistent
ownership. The host kernel and storage filesystem must support this Incus setting. Ordinary data
volumes without managed files retain their declared mapping configuration.

Incus overwrites existing file content while preserving ownership and mode. When either metadata
field drifts, Tend first invalidates consumer activation, deletes the managed file, and recreates
it with the declared metadata. A failure between deletion and recreation leaves activation pending;
the next pass restores the missing file before activating its consumer. This repair is not an
atomic file replacement.
