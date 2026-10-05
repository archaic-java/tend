# Native Caddy and Authelia OCI ingress

The homelab selects upstream `library/caddy:2` and `authelia/authelia:4.39.28`. The reviewed
OCI index pins used by the disposable native case are:

| Image | Index digest | Observed AMD64 runtime |
|---|---|---|
| library/caddy:2 | `sha256:13b7fbadd017b042956fddbceedeeea12bb1e560534f9b3df281269dbcc61813` | Caddy v2.11.6; command `caddy run --config /etc/caddy/Caddyfile --adapter caddyfile` |
| authelia/authelia:4.39.28 | `sha256:bd97cff4fcbf715b5ff1f9ae286afbe6033afce385302520b0368122d43a6f54` | `/app/entrypoint.sh`; `/app/authelia`, working directory `/app` |

Pins and runtime configuration were read directly from Docker Registry v2's OCI index/manifest
and config blobs. Resolve/import these on the operator workstation using the Incus OCI remote,
record the full resulting Incus fingerprints, and use those fingerprints in XML. Tend does not
pull registry images. Updating a pin requires renewed startup and activation evidence.

## Entrypoints and retained data

Caddy's upstream command reads Tend's generated `/etc/caddy/Caddyfile` directly. Mount it
read-only; retain explicit shifted custom volumes at `/data` and `/config`. The upstream image
uses `XDG_DATA_HOME=/data` and `XDG_CONFIG_HOME=/config`; its local CA is below
`/data/caddy/pki/authorities/local`, and public ACME state also belongs on retained data.
No exec/reload feature is needed: generated-file activation stops/starts the service.

Authelia's base Git configuration must precede generated policy. Explicitly select the supported
binary and complete config argument list when the image's entrypoint/default config would
otherwise override the environment binding:

```xml
<entry key="oci.uid" value="1000"/><entry key="oci.gid" value="1000"/>
<entry key="oci.entrypoint" value="/app/authelia --config /etc/tend-base/configuration.json --config /etc/tend-authorization/access-control.json"/>
<entry key="environment.X_AUTHELIA_CONFIG" value="/etc/tend-base/configuration.json,/etc/tend-authorization/access-control.json"/>
```

This is the actual upstream binary inside the pinned image, without the synthetic OpenRC wrapper.
Set generated authorization/config/secret mounts to UID/GID 1000. Keep the private users volume
read-only with the same identity, and a retained UID/GID 1000 directory mode 0700 at the base
configuration's SQLite/notifier data path. Follow [private user delivery](private-inputs.md).
The base configuration supplies sessions, file backend, notifier, storage and OIDC settings;
it must not contain `access_control`. Tend supplies that final policy file and activates
Authelia before gateway route changes. `one_factor` means password or passkey.

## Private metrics and identity

The optional gateway child enables the global Caddy metric counters and a single HTTP metrics
listener on port 9180:

```xml
<ingress-gateway instance="caddy" pool="pool" path="/etc/caddy"
                 authorization-instance="authelia" authorization-device="eth0"
                 authorization-path="/etc/tend-authorization"
                 authorization-uid="1000" authorization-gid="1000">
  <metrics device="eth1"/>
</ingress-gateway>
```

The selected gateway NIC must reference a managed network and have an explicit RFC1918 IPv4
address. The generated listener has an explicit `bind` to that address; it does not bind a second
LAN NIC or wildcard address. The path is fixed at `/metrics`; port 9180 is fixed. Missing/invalid
NICs and public addresses are rejected before mutation. No arbitrary Caddy snippets or templating
are exposed. Do not forward that port from the router; private network routing/firewall policy
must limit access to the monitoring side. Caddy's local admin listener remains local.

Every public and protected route strips `Remote-User`, `Remote-Groups`, `Remote-Email`,
`Remote-Name`, `X-Forwarded-User`, `X-Forwarded-Email` and `X-Forwarded-Groups` before any
ForwardAuth or backend request. Authelia's response supplies trusted `Remote-*` identity only
on protected routes. The auth portal and Grafana/Open WebUI routes are public proxy routes;
the latter two register OIDC clients and enforce their own admission/roles. Pi explicitly uses
`one_factor` with `ai-users`; HTTP and WebSocket handshakes follow the same gateway policy.
This gateway does not authorize clients that bypass it and reach a backend directly.

## Evidence and operator acceptance

The native disposable case uses independent resources on ordinary bridges, upstream OCI images,
private user delivery and generated secret mounts. `.localhost` hosts select an explicit disposable
local CA; the probe rejects it first, then uses its public certificate with hostname checks intact.
A standalone fixture backend verifies HTTP identity and a real RFC6455 upgrade/hello frame.
Anonymous and outside-group HTTP/upgrades must never reach it. Public routes strip forged
identity; private metrics have actual request counters, while the second NIC and public HTTPS
routes cannot expose that handler. Changed Git policy activates the actual Authelia binary;
no-op passes retain process lifetimes/session, and Caddy retains its CA through restart.
The existing browser/OIDC/Grafana cases separately retain their independent admission evidence.

Public installation requires the operator to verify DNS, host/LAN addressing, inbound 80/443 and
ACME reachability, then observe real issuance and verified external HTTPS/WebSocket access.
CI does not certify public issuance, physical passkeys or host firewall topology. After Git
activation, bound service health and fresh-session policy checks to 90 seconds; leave consumers
stopped after failed startup until private/config prerequisites are corrected.
