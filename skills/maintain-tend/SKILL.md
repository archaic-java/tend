---
name: maintain-tend
description: "Maintain, extend, diagnose or review the archaic-java/tend Incus GitOps controller. Use for Tend XML/resource lowering, ownership and reconciliation, Git snapshots, secret persistence, CLI and logging composition, dependencies, offline tests or disposable Incus integration."
---

# Maintain Tend

Read the foundation, select the task, and load only the references relevant to that work.
Use this entry point for both human contributors and coding agents.

## Establish the foundation

- Use JDK 25, named JPMS modules and the [README's commands](../../README.md#build-and-verify).
  Follow Archaic Java's shared engineering conventions; keep project mechanics here.
- Keep one production module with explicit Git, XML/state, Incus and secret adapters.
  Treat these as implementation boundaries; introduce a service contract only for a plausible
  replaceable provider. Logging and testing use the service catalog.
- Read one immutable Git revision per attempt. Resolve and validate every referenced input,
  deployment policy and ownership preflight before Incus mutations.
- Keep one writer per ownership scope. Preserve operator fields, retained resources, ETags,
  bounded asynchronous waits and recovery after partial completion.
- Invalidate activation before changing mounted files. Activate authorization before gateway routes.
  Require `security.shifted=true` for managed files; generated file volumes enable it.
- Preserve stable private secrets across replacement. Never put values, HTTP bodies, browser tokens
  or private provider causes in logs, exceptions or uploaded evidence.
- Keep offline fixtures local and isolated. Run real integration only on a fresh disposable CI/test
  VM; never contact the existing homelab. Keep production scope to required digital-garden capabilities.
- Read declarations at the pinned dependency revision. Treat links to dependency main branches as
  navigation, not pins or permission to change an immutable versioned contract.

## Choose the task

| Task | Read next | Owning code and verification |
|---|---|---|
| Change XML, mounts, ingress or egress | [Resource model](references/resource-model.md) | Schema, StateReader and ResourceCompiler; RequirementsTests and published examples. |
| Change convergence, ownership or recovery | [Resource model](references/resource-model.md#change-and-verify-resources), [development](references/development.md#architecture) | Reconciler, Instances, Volumes and NetworkPolicies; ReconciliationTests. |
| Change Git or secret persistence | [Resource model](references/resource-model.md#configuration-and-secrets), [development](references/development.md) | GitRepository and SecretStore; StateTests and SecretTests. |
| Change CLI, polling, logging or failure policy | [Logging and failure policy](references/development.md#logging-and-failure-policy) | Main and Controller; CliTests, then the full offline suite. |
| Prepare dependencies, compile or package | [Development dependencies](references/development.md#dependencies), [README](../../README.md) | scripts/prepare, lib/src, cmd and Dockerfile; compile, offline tests and CLI help. |
| Change Incus protocol handling or mock behavior | [Mock fidelity](references/development.md#incus-mock-fidelity), [integration](references/incus-smoke.md) | IncusClient and IncusMock; offline failures/recovery, then affected real-host cases. |
| Run or extend real-host verification | [Integration reproduction and evidence](references/incus-smoke.md), [development coverage](references/development.md#real-host-integration) | Separate integration module and scripts/incus-smoke; preserve diagnostics and cleanup. |
| Bootstrap a reset IncusOS host | [Remote bootstrap](references/incusos-bootstrap.md), [manifest template](references/bootstrap-manifest.example.md) | Operator procedure only; validate actual network roles, image fingerprints, TLS and target storage before handing off to the controller. |
| Build/import, install or replace the OCI controller | [OCI controller](references/oci-controller.md), [integration evidence](references/incus-smoke.md) | scripts/bootstrap and ControllerSmokeTests; retain state, private credentials and one writer; bootstrap is operator-owned. |

| Provision/update private users, metrics TLS or SMTP | [Private inputs](references/private-inputs.md), [resource model](references/resource-model.md) | Operator scripts/bootstrap/private-volume; ownership preflight, offline safety and real authorization case. |

| Configure upstream OCI ingress and private gateway metrics | [Native ingress](references/native-ingress.md), [resource model](references/resource-model.md#ingress) | Gateway lowering, NativeIngressSmokeTests and pinned cache inputs; verify private binding and HTTP/WebSocket decisions. |

## Complete the change

Keep command files and module descriptors aligned with code. Update the owning reference for
changed behavior, policy, pins or evidence; retain precise API declarations beside source.
Validate links, examples and claims against the selected dependencies. Keep the foundation and
this task map sufficient to find the changed constraint, implementation and checks.

Compile first, then run the relevant offline suite and CLI help. Run the full offline suite before
publishing. Reserve the full integration suite for one final coherent revision; rerun only when a
failure or a later change requires new evidence. Report exact commands, results and coverage limits.
