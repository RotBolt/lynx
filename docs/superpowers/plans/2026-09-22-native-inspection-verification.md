# Native Inspection Verification Implementation Plan

> **For agentic workers:** Use `superpowers:executing-plans`; execute only the ticket next in the master plan. Checkboxes are pending verification, not claims of success.

**Goal:** Establish the existing working behavior before changes and prove every checkpoint and final archive against it.

**Architecture:** An external harness drives unchanged installed sample apps, records independent app results and CLI results, and checks device/proxy cleanup. Ownership controls are separate from sample application code.

**Tech Stack:** Gradle, native CLI, ADB/UIAutomator, simctl/LLDB UI actions, SQLite, jq, shell, real public HTTPS/WSS services.

**Spec:** [Contract](../specs/2026-09-22-app-scoped-native-inspection.md); order and constraints in the [master plan](2026-09-22-native-inspection-hardening.md).

## Global constraints

- Source/build configuration of `dummyapp/` remains unchanged throughout execution.
- A host curl or local fixture is a control/test input, never evidence that target-app capture works.
- Every result is tied to fresh actions, run time, executable hash, OS/build, device, app, and actual process identity.
- Each shared-runtime checkpoint repeats Android and iOS DB + HTTPS H1/H2/WSS verification.
- Tests own only their helper processes, mappings, temporary files and proxy leases.
- Public endpoint failure is investigated and recorded; do not substitute a local server for the real-app gate.

## Mandatory assertions

| Area | Positive assertion | Negative/regression assertion |
|---|---|---|
| DB discovery | Same platform flags find `databases/dummyapp.db` / `Documents/dummyapp.db`. | Exact path/casing preserved; shell/tool changes do not corrupt database bytes. |
| DB snapshot/query | Snapshot opens; table/schema and every selected value agree with source app records after writes settle. | Snapshot write attempt cannot modify the source or read-only snapshot; no newly asserted WAL consistency. |
| HTTPS HTTP/1.1 | A new app action produces HTTPS, `HTTP/1.1`, status 200 and readable body. | A CONNECT success alone is not a pass. |
| HTTPS HTTP/2 | A new action negotiates `HTTP/2`; 200 includes readable JSON with `userId`; 304 is reported honestly. | An H1 fallback does not pass H2. Require at least one actual 200/body run at each checkpoint. |
| WSS | Real app opens, receives `lynx-sample-ping`, and closes; capture contains 101 and matching text/close frames. | CONNECT-only/failed handshake/host-generated echo does not pass. |
| Isolation | The positive flow has OS ownership proof and the right capture/device/application. | Same URL/User-Agent from another process/device, unknown owner and old history are excluded. |
| TLS pass-through | A foreign HTTPS control sees the origin certificate and succeeds without trusting the Lynx CA. | Non-target connections never call leaf creation or appear in retained target payloads. |
| Lifecycle | Stop/detach/recovery restore the exact acquired settings and remove owned mappings/helpers. | No lingering worker, stale listener, duplicate worker or accidental removal of another mapping. |
| Data retention | Large (>64 KiB and multi-MiB) bodies survive concurrent append/read/get. | No timestamp ID collisions, split JSON records, silently skipped corrupt records or default clipping. |

## M1.1-00 — Baseline and evidence harness

**Files:**
- Create `scripts/verify-native-inspection.sh`: orchestrates checks and evidence, with separate `legacy` and `scoped` command adapters.
- Create `scripts/verify-native-database.sh`: exact native DB syntax and source/snapshot comparisons.
- Modify `.github/scripts/network-capture-smoke.sh`: retain existing real app actions, add independent result checks and fresh-run correlation; do not switch its default to new commands before M1.1-09.
- Create `docs/verification/M1_1_CHECKPOINTS.md`: sanitized gate ledger; raw artifacts remain under ignored `build/verification/`.
- Read `docs/NETWORK_CAPTURE_REGRESSION.md`, `dummyapp/iosApp/SampleApp.swift`, and `dummyapp/shared/src/androidMain/kotlin/dev/lynx/dummyapp/AndroidExchangeStore.kt`.

**Interface:** `scripts/verify-native-inspection.sh <absolute-binary> <legacy|scoped> <android-serial> <simulator-udid> <output-directory>`. Refuse concurrent captures not owned by the harness; never assume stale proxy defaults are safe to overwrite.

- [ ] Record `git rev-parse HEAD`, working-tree status, executable and dynamic-library hashes, sample source hashes, installed app identity, CA fingerprint, proxy state, and ADB mappings.
- [ ] Run the current native and JVM gate; save complete output and exit status:

```bash
./gradlew test :core:model:macosArm64Test :host:native:macosArm64Test \
  :host:network:macosArm64Test :apps:cli:macosArm64Test \
  :apps:cli:linkReleaseExecutableMacosArm64 --no-daemon
```

- [ ] Run the current app smoke tests sequentially with discovered target IDs. Record public endpoint reachability separately from app outcomes.

```bash
LYNX="$PWD/apps/cli/build/bin/macosArm64/releaseExecutable/lynx.kexe" \
  .github/scripts/network-capture-smoke.sh android "$ANDROID_SERIAL" dev.lynx.dummyapp
LYNX="$PWD/apps/cli/build/bin/macosArm64/releaseExecutable/lynx.kexe" \
  .github/scripts/network-capture-smoke.sh ios "$SIMULATOR_UDID" dev.lynx.dummyapp
```

- [ ] Verify source DB rows for these fresh actions, then native snapshot/tables/query. The current native DB commands do not have the JVM snapshot-ID/JSON envelope; do not append `--json` to native SQL where it would become part of the SQL string.

```bash
"$LYNX" db list --platform android --device "$ANDROID_SERIAL" --package dev.lynx.dummyapp
"$LYNX" db snapshot databases/dummyapp.db --platform android \
  --device "$ANDROID_SERIAL" --package dev.lynx.dummyapp
"$LYNX" db tables /tmp/lynx-native-databases_dummyapp.db.db
"$LYNX" db query /tmp/lynx-native-databases_dummyapp.db.db \
  'SELECT id,transport,method,url,status,response_body,error FROM network_events ORDER BY id;'
"$LYNX" db list --platform ios --simulator "$SIMULATOR_UDID" --bundle-id dev.lynx.dummyapp
"$LYNX" db snapshot Documents/dummyapp.db --platform ios \
  --simulator "$SIMULATOR_UDID" --bundle-id dev.lynx.dummyapp
"$LYNX" db tables /tmp/lynx-native-Documents_dummyapp.db.db
"$LYNX" db query /tmp/lynx-native-Documents_dummyapp.db.db \
  'SELECT id,transport,method,url,status,response_body,error FROM network_events ORDER BY id;'
```

- [ ] Query source rows read-only through ADB `run-as`/simulator container access; compare settled rows by ID/value, not merely count. Do not add production device-side hashing. Native snapshots currently copy the main file; any existing WAL gap is an explicitly separate baseline defect.
- [ ] Add harness self-checks: a stale request ID, empty list, CONNECT-only result, altered response body, mismatched app result, and cleanup failure must each fail the harness.
- [ ] Distinguish known baseline shortcomings (broad attribution, unscoped list, machine output differences) from protected protocol/DB successes. If a protected behavior fails, diagnose before advancing; do not mark V0 passed on old evidence.
- [ ] Preserve the executable and its matching runtime dependencies locally; record exact sample source hashes. Commit harness/ledger and create checkpoint 00 only after V0 is green.

## M1.1-01 — Ownership feasibility gate

**Files:** Create `tests/attribution/README.md`, `tests/attribution/android-owner-probe.c`, `tests/attribution/macos-owner-probe.c`, `scripts/verify-attribution-probes.sh`.

**Interfaces:** Probe output is versioned JSON with peer tuple, PID/start identity, UID where applicable, socket inode where applicable, target-match result, and duration. It contains no unrelated request bodies. Probe inputs are explicit device/application IDs and temporary endpoints.

- [ ] Add a negative control that copies the target URL and User-Agent from a different OS owner; a hostname/metadata-only classifier must fail.
- [ ] Build a bounded Android relay probe with the NDK and run it as ADB shell. Pin the actual compiler/NDK revision in the probe manifest. Resolve both IPv4 and IPv6 socket tables and verify app PID membership through `run-as`.
- [ ] Use unchanged Android app actions against real services through this relay; correlate their sockets before forwarding. The prior `run-as nc` experiment alone is insufficient for this gate.
- [ ] Repeat with the unchanged iOS Simulator app, matching the peer tuple to libproc data and simulator container identity. Run a host curl control against the same origin and prove exclusion without TLS interception.
- [ ] Measure lookup latency and failures under concurrent short-lived connections; test denial, PID restart/reuse, unrelated same UID, and socket closure during lookup. Persist `unknown` rather than guessing.
- [ ] Validate the proposed API 26 helper baseline plus the current emulator. If an API image or physical device is unavailable, leave its support row pending and keep runtime capability checks mandatory.
- [ ] Ensure every helper/process/mapping is removed or stopped and prior proxy state restored. No source changes to the sample apps.
- [ ] Run V0 and commit checkpoint 01 only for the environments actually verified. If real-app identity cannot be proved reliably, stop the dependent adapter work and record the blocker.

## M1.1-10 — Distribution and final release gate

**Files:**
- Create `scripts/package-macos-native.sh` by extracting the existing workflow packaging steps into a locally runnable script.
- Modify `.github/workflows/native.yml`, `scripts/package-linux-native.sh`, `scripts/native-linux-smoke.sh`, `README.md`, `docs/COMMANDS.md`, `docs/distribution/native.md`, `docs/agent-skill/SKILL.md`, and `docs/NETWORK_CAPTURE_REGRESSION.md`.
- Update `lynx-spec/README.md`, `FEATURE_REQUIREMENTS.md`, `ARCHITECTURE.md`, `IMPLEMENTATION.md`, `ROADMAP.md`, `AGENT_EXECUTION.md`, `UPSTREAM.md`, and `AGENTS.md` to remove conflicting native/JVM and historical architecture claims for this increment.

**Interfaces:** `scripts/package-macos-native.sh <native-binary> <archive-path>` produces a relocatable `lynx` plus native libraries, Android relay ABI binaries, helper manifest, and `lynx-skill/SKILL.md`. End-user runtime does not need Gradle or an NDK.

- [ ] Add packaging checks for missing relay, wrong helper ABI/hash/protocol, missing dylib, invalid executable permission/signature and absent skill file.
- [ ] Package the macOS build, extract it to a fresh `mktemp -d` directory outside the repository, and invoke its executable from an unrelated working directory.
- [ ] Run V0/V2/V4/V6/V7 with this exact executable/archive. Include a process with no SDK environment variables and PATH omitting platform-tools; the default SDK location must still resolve.
- [ ] Confirm archived data from session A remains correct after session B and after detach; verify old unscoped logs cannot leak into either command.
- [ ] Verify the Linux build/runtime on a Linux host when available. Split its fixture protocol test from its device-backed strict CLI test; the old unattached host-curl smoke cannot certify app attribution. Never let a test remove the developer's active CA.
- [ ] Record macOS and Linux status independently. Local macOS completion is allowed while Linux remains explicitly unverified and unpublished. Physical Android devices require their own device-backed evidence; physical iOS remains unsupported for attribution.
- [ ] Check that bundled skill examples use `devices`, `doctor`, `network snapshot`, and `network list --session`, including unknown attribution and process-restart behavior.
- [ ] Produce a concise sanitized report containing commit/tree IDs, artifact hashes, command output samples, platform matrix, cleanup results and remaining limitations.
- [ ] Create checkpoint 10 only after the committed macOS archive passes. Leave publication, pushing, and paid CI outside this task.

## Evidence classification and cleanup

Use `pass`, `fail`, `blocked`, and `not_run` per assertion/platform; record the
actual expected/observed value. A failed negative control blocks the checkpoint
even if every positive request succeeds. Capture final request IDs and compare
against the previous high-water mark; existing persisted examples cannot pass.

Before mutation, save proxy settings and mapping identities. On exit, restore
only owned resources and verify restoration. Do not blindly set `:0` or disable
the host proxy when the initial state was another user's proxy. If the initial
state points to a dead Lynx listener without a recoverable lease, record that
state and resolve it explicitly before using the environment for a clean pass.

Long-lived WSS and in-flight requests must be stopped/closed through their normal
actions. Debugger use is limited to invoking the sample's existing UI selectors,
not replacing networking configuration or injecting traffic implementations.
