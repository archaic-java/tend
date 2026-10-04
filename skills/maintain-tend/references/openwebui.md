# Open WebUI OCI login

The minimal consumer block in `AuthorizationSmokeTests` shares its existing private users,
HTTPS issuer, browser and enrolled credentials. It adds no enrollment or OIDC protocol matrix.
`prepare-openwebui` caches upstream v0.11.4 by OCI index digest
`sha256:9591b13f13843c7721c2b8eaf7382846c81b3ffe126526d1888d1fed50c6a33f`.
Source reviewed at Open WebUI commit `8bd8b4fac5e059578ac0c74b3c18d11139f88b7d`.

The image runs as UID/GID 0 inside an unprivileged Incus container. Its shifted persistent
volume mounts at `/app/backend/data` with explicit `0:0:0700` metadata. The public startup
wrapper in [examples/openwebui/start.sh](../../../examples/openwebui/start.sh) reads two
generated read-only `0400` files, exports the raw client secret and stable session key into
the child process, clears all four trusted-header authentication variables, and executes the
upstream startup script with access logging disabled so callback query codes are not logged. Incus configuration contains only file paths and public settings.
Use an explicit issuer CA file; never disable TLS verification.

The confidential Authelia client uses an exact `/oauth/oidc/callback` redirect, S256 PKCE,
`client_secret_basic`, and signed `openid profile email groups` claims. **Its issuer policy
must require `ai-users`**, including for accounts with `admins`. In v0.11.4, Open WebUI's
`get_user_role` bypasses role checks for the first user and accepts admin claims independently
of its allowed-role list. Setting `OAUTH_ALLOWED_ROLES=ai-users` alone does not implement
the intended admission policy. The dedicated issuer gate closes both bypasses. Bootstrap
the application with an approved account belonging to both `admins` and `ai-users` before
ordinary users sign in; upstream automatically promotes its first account to administrator.

Keep `ENABLE_LOGIN_FORM`, `ENABLE_PASSWORD_AUTH`, `ENABLE_SIGNUP`,
`ENABLE_PERSISTENT_CONFIG` and `ENABLE_OAUTH_PERSISTENT_CONFIG` false. OAuth signup stays
enabled for admitted identities. Role management maps `admins` to admin and `ai-users` to
ordinary user; merging by email stays disabled. The test seeds stale database rows, restarts
the application and checks its API settings and a fresh ordinary-user callback. This verifies
the existing database cannot replace Git's settings while identity persists.

The login test points the OpenAI API at an unavailable loopback endpoint. It verifies actual
callback redemption and `/api/v1/auths/` identity, ordinary/admin roles, observer/admins-only
denial, private read-only delivery, and absence of tracked secrets/codes/cookies/tokens from
console and uploaded evidence. Inference and ROCm commissioning belong to the llama work.
OAuth logs and consoles are excluded from diagnostics; observations remain private.

Compile and run offline checks through `cmd/compile` and `cmd/test`. Run the full disposable
Incus stack only after all related application changes are ready, using the opt-in workflow
described in [incus-smoke.md](incus-smoke.md). A compiled fixture is not live OCI evidence.
