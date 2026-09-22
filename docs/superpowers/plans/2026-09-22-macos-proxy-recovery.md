# Priority macOS Proxy Recovery Implementation Plan

**Status:** Diagnosed from source; runtime fix pending. Do not mark the incident resolved from this plan.

**Goal:** No Lynx-owned dead listener should leave the host routed to that listener; preserve user proxy configuration and user edits.

**Bug:** [Report](../bugs/2026-09-22-macos-proxy-not-restored.md).
**Parent:** [Native inspection plan](2026-09-22-native-inspection-hardening.md).

## Confirmed code defects

In `host/network/src/posixMain/kotlin/dev/lynx/nativehost/PosixNativeNetworkInspector.kt`:

1. `start` calls `applyDeviceProxy` before worker launch/readiness, creating an outage window.
2. `setRunning` persists prior settings only after application, leaving a crash window without durable rollback.
3. Startup error handling suppresses restoration failures then deletes runtime state, losing recovery information.
4. Worker `finally` clears readiness/closes its socket without proxy restoration. Abrupt termination cannot execute `finally` at all.
5. Repeated start re-snapshots the Lynx endpoint as the previous proxy, potentially losing the user's original configuration.

Additionally, native `Main.kt` routes `detach` directly to `sessions.detach()`;
there is no network-stop call. Wire successful network cleanup before attachment
deletion, and keep the attachment/lease recoverable if cleanup fails.

`PosixNativeNetworkStateStore.workerReady` accepts any nonempty readiness file;
`isRunning` checks persisted state only, not a live owned listener.
`NativeMacSystemProxyController.restore` overwrites settings without ownership
checks and stops at the first error, potentially leaving HTTPS enabled after an
HTTP restoration error. These explain the reported failure class; the exact
historical process termination is not known.

## Execution order

Add priority ticket **M1.1-P0** immediately after the read-only baseline inventory
portion of M1.1-00, before any new live test changes host routing. If baseline
itself is blocked by stale settings, record it and perform only ownership-proven
recovery. Do not blindly turn proxies off. Complete V0 after this fix before
marking the first working checkpoint. Ticket 05 consumes this implementation;
it must not recreate a second lease mechanism.

## Files and boundaries

- Modify existing native inspector, state store and `NativeMacSystemProxyController.kt`.
- Add `NativeMacProxyLease.kt` and `NativeMacProxyRecovery.kt` in host/network posixMain `dev/lynx/nativehost`.
- Add a macOS worker supervisor/recovery entrypoint in native CLI routing. It must survive worker termination, not just execute inside the worker's `finally`.
- Tests: `NativeMacSystemProxyControllerTest.kt`, new `NativeMacProxyRecoveryTest.kt`, `NativeMacProxyLifecycleTest.kt` in host/network posixTest `dev/lynx/nativehost`.
- Integration evidence: `docs/verification/M1_1_CHECKPOINTS.md`; raw evidence stays ignored under `build/verification/m1-1/P0/`.

## Durable state and transitions

Use a separate, atomically written recovery lease that remains readable when
runtime state is cleared. Fields: lease UUID, actual network service, original
HTTP/HTTPS settings, intended installed settings, per-setting application and
restoration progress, worker PID/start identity, endpoint and capture token.
Record PAC/autodiscovery/bypass values for comparison; do not modify them in this
fix. Preserve authentication settings; if exact authenticated-proxy restoration
cannot be supported without credentials, reject activation before mutation.

Transition sequence:

```text
lock -> inspect -> persist PREPARED lease -> launch worker/supervisor
     -> verify owned endpoint ready -> mark APPLYING -> apply each setting
     -> verify settings -> ACTIVE
stop/failure/death -> RESTORING -> restore owned settings independently
                  -> verify -> RESTORED -> remove completed lease
```

Persist intent before each external mutation, then reconcile actual settings
after a crash. Repeated start returns the existing healthy capture; it must not
replace its original lease. Stale legacy state lacking trustworthy previous
values returns `PROXY_RECOVERY_REQUIRED` with service/endpoint instructions,
not guessed settings. A stale readiness file or shell PID is not readiness.

## Test-first implementation steps

- [ ] Build a stateful fake networksetup runner with original/current settings and command failures. Add a fake worker probe and durable lease store.
- [ ] Reproduce: readiness failure must result in zero proxy mutations; crash after first setting must retain sufficient intent to restore; repeated start must retain original values.
- [ ] Reproduce: failed HTTP restoration must still attempt HTTPS restoration; failed cleanup must retain lease; recovery repeated twice must not change already-restored settings.
- [ ] Reproduce: a user's edit to one setting must survive while independently owned settings restore. Return `PROXY_OWNERSHIP_CONFLICT`; never overwrite the user edit.
- [ ] Run `./gradlew :host:network:macosArm64Test --no-daemon`, save failing results before editing production code.
- [ ] Separate controller inspect/apply/conditional-restore. Persist lease before mutation; enforce lock and exact per-setting comparison. Preserve untouched PAC/bypass/auth configuration.
- [ ] Move proxy activation after authenticated readiness from the correct worker instance. Check actual endpoint and PID start identity; bind failure cannot activate routing.
- [ ] Use supervisor worker-death monitoring plus startup/doctor reconciliation. Worker graceful exit also requests idempotent cleanup. SIGKILL cannot run a signal/finally handler; verify supervisor-driven restoration. If supervisor and worker both die, next invocation must recover/report stale state; do not claim impossible recovery while all Lynx processes are absent.
- [ ] Distinguish short-lived `network start` CLI exit (capture stays active) from interrupted startup (rollback). Normal CLI exit must not stop independent-shell capture.
- [ ] Attempt all owned restoration actions even if one fails. Keep unresolved progress and actionable error; clear runtime/recovery state only when safe. Handle stop/detach without needing a current attachment to identify the lease's service.
- [ ] Pass focused tests and full local native/JVM gate. Do not rotate CA, alter application code or broaden to protocol changes.

## Live acceptance gate

Before modifying routing, record actual service and all original settings. Use a
separate cleanup observer with the recorded values; never use a blanket proxy-off
cleanup. Do not kill existing unrelated captures or network processes.

- [ ] Start -> owned listener confirmed -> capture real HTTPS -> stop; exact settings restored.
- [ ] Repeat failed bind, interrupted startup, normal worker exit, forced worker termination and detach.
- [ ] Verify supervisor restoration after worker death and next-invocation recovery after both exit.
- [ ] Change one setting during capture; preserve that change and report conflict while restoring the other owned setting.
- [ ] Verify disabled proxies and a pre-existing custom proxy fixture, PAC/autodiscovery and bypass remain correct. Test credential-dependent cases through mocks unless an authorized test configuration is available.
- [ ] Verify system-proxy-aware HTTP/HTTPS clients after recovery (plain curl may ignore macOS system proxy and is insufficient proof alone).
- [ ] Repeat Android/iOS DB and real H1/H2/WSS regression gates; no sample changes.
- [ ] Commit `fix(network): recover macOS proxy leases` only with verified scope. Rebuild/check committed tree and tag `checkpoint/m1-1/P0-proxy-recovery`. Record pending cases honestly; do not close the incident while required live cases remain unverified.
