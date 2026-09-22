# Luna implementation handoff

Status: planning only. No runtime fix is claimed by these documents.

Read the [master plan](2026-09-22-native-inspection-hardening.md) and
[contract](../specs/2026-09-22-app-scoped-native-inspection.md), then the component
plan for the current ticket. Follow repository guidance and inspect the current
working tree before editing. Do not overwrite unrelated changes.

## Separate priority fix: M1.1-P0 — macOS proxy restoration

**Status:** Pending implementation and verification.

- Bug: [macOS proxy not restored](../bugs/2026-09-22-macos-proxy-not-restored.md).
- Detailed implementation: [proxy recovery plan](2026-09-22-macos-proxy-recovery.md).
- Scope: durable original settings before mutation, owned-worker readiness before
  activation, conditional/idempotent restoration, detach cleanup, worker-death
  supervision and startup recovery. Keep unresolved leases when restoration fails.
- Exclusions: no CA rotation, protocol rewrite, sample-app changes or app filtering
  implementation in this fix. Never blindly disable a user's existing proxy.
- Verification: failed bind/startup, interrupted startup, stop, detach, graceful
  exit, forced worker death, repeated start, user-edited settings, failed rollback
  and recovery. Verify system-proxy-aware connectivity and both platforms' DB and
  real HTTP/1, HTTP/2, WebSocket regressions.
- Dedicated commit: `fix(network): recover macOS proxy leases`.
- Dedicated verified checkpoint: `checkpoint/m1-1/P0-proxy-recovery`.

Do not fold P0 into M1.1-05 or mark it complete from unit tests alone. Ticket 05
later extends the same recovery mechanism for capture lifecycle needs.

## Execution order

1. M1.1-00 read-only inventory: preserve binary/libraries, hashes, current proxy
   values, state and app identity. Run non-mutating checks. Record unsafe baseline
   conditions without reactivating a dead proxy.
2. M1.1-P0: implement and verify the separate safety fix. Run its live acceptance
   checks and V0 against the candidate before its working commit/checkpoint.
3. Finish M1.1-00 harness/evidence work and its verification checkpoint. Distinguish
   preserved pre-fix evidence from the post-P0 verified baseline.
4. Execute M1.1-01 through M1.1-08 sequentially.
5. Execute separate **M1.1-WS — live WebSocket capture** using the
   [detailed fix plan](2026-09-22-live-websocket-capture.md). Handshake, data and
   ping/pong frames must be readable while open; never wait for Close. Use its
   own tests, commit and checkpoint; preserve app/device ownership.
6. Execute M1.1-09 and M1.1-10. Verify scoped snapshot/list also expose open frames.

P0 is the explicit exception to requiring a complete live V0 before runtime edits:
do not deliberately reproduce an unprotected host-wide outage to obtain baseline
evidence. All P0 completion claims still require live regression verification.

## Per-ticket handoff and stopping rule

Use focused failing tests, implement only the ticket, run its gates, inspect the
diff, commit the verified scoped change, then verify the committed tree before
creating its checkpoint. Do not push, trigger CI, publish releases, spawn agents
or change model without a user request.

Report ticket ID, changed files, test results, live Android/iOS results, remaining
limitations, evidence paths, commit/checkpoint and next ticket. If a required gate
fails or cannot run, report it as blocked/not-run and stop; never relax assertions
or alter the sample app to advance. A docs commit is not a working-runtime tag.
