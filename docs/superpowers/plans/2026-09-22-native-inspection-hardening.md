# Native Inspection Hardening Implementation Plan

> **For agentic workers:** Use `superpowers:executing-plans` to execute one verified checkpoint at a time. Delegation is optional only when explicitly requested; one coordinator owns shared device/proxy state. Steps use checkboxes for tracking.

**Goal:** Deliver verified application-only network results, capture-session commands, and automatic cross-platform device/tool discovery without regressing working database or network behavior.

**Architecture:** Add an ownership gate ahead of the existing proxy protocols, with an Android ADB relay and a macOS socket resolver. Persist immutable capture contexts separately from app attachments, then expose session-scoped CLI commands. Centralize tool discovery without changing the database command contract.

**Tech Stack:** Kotlin Multiplatform/native, macOS libproc, Android NDK/POSIX relay, ADB, simctl, existing OpenSSL/nghttp2/Brotli adapters, Gradle and shell integration checks.

**Spec:** [App-scoped native inspection contract](../specs/2026-09-22-app-scoped-native-inspection.md).

## Executor handoff (Luna or any other agent)

Start with the [Luna handoff document](2026-09-22-luna-handoff.md), which includes
M1.1-P0 as a separate fix with its own commit and verification checkpoint.

**Priority safety exception:** Read the [macOS proxy recovery ticket](2026-09-22-macos-proxy-recovery.md)
first. M1.1-P0 follows read-only baseline inventory and precedes further live proxy
mutation. Complete the baseline gate after this fix. Ticket 05 extends its lease
implementation rather than duplicating it.

Read this master, the linked contract, and only the component plan for the next
incomplete ticket. Start at M1.1-00. Inspect current files before editing; planned
new filenames are not claims that those APIs already exist. Follow portable
repository guidance; historical injected-runtime requirements are superseded for
this explicitly requested native proxy work by the linked contract.

Execute **one ticket per turn/checkpoint**. Do not reimplement later tickets or
change sample apps to satisfy a test. Stop on a failed gate and report the exact
failure, evidence path and smallest next diagnostic; do not advance or label it
verified. Never spawn additional agents or change model unless requested.

At handoff report: ticket, changed files, failing-then-passing checks, live Android
and iOS results, remaining limitations, commit/tag, evidence directory, next ticket.
If an ownership mechanism fails its feasibility gate, stop for a design decision;
do not substitute URL filters, app proxy code, root or VPN.

## Global constraints (applies to every ticket)

- macOS ARM64 is the first live verification host; Android and iOS Simulator are separate mandatory rows.
- No VPN, device root, app proxy code, application source changes, or injected app runtime.
- The sample applications remain unchanged; public API calls are initiated through their existing buttons.
- Read-only database behavior, exact values/casing, and independent invocations remain protected.
- No default body/result trimming; explicit storage/attribution errors replace silent loss.
- CI is billing-blocked: verify locally; do not push or trigger Actions as part of these fixes.
- Linux remains a separately verified host target; macOS tests cannot certify Linux. Physical iOS and Windows attribution remain unsupported.
- All implementation items below are pending. A planning commit is not a working-runtime checkpoint.

## Verification gates first

| Gate | Required proof | Blocks |
|---|---|---|
| V0 Baseline | Fresh native/JVM checks; unchanged sample apps; actual DB rows; real HTTPS H1, HTTPS H2, and WSS on both targets; prior proxy state recorded. | Runtime changes except P0's documented safety exception; required before P0 completion. |
| VP0 Proxy recovery | Readiness before activation; durable conditional rollback; crash/startup/detach recovery; user edits preserved; system-proxy-aware connectivity. | P0 working checkpoint and subsequent live proxy changes. |
| V1 Ownership feasibility | Android real sample sockets through a device-local relay; iOS real process socket matching; positive and unrelated controls to the same destination. | Committing to the production attribution adapters. |
| V2 Environment | No `adb` on PATH but standard SDK installed; missing tools; partial Xcode; Android/iOS inventory with accurate attachment state; clean JSON. | Discovery checkpoint. |
| V3 Session/storage | Unique IDs; no cross-session records; immutable per-connection context; large/concurrent writes; legacy preservation; separate shell calls. | Session store checkpoint. |
| V4 Lifecycle | Start/stop/detach/restart/crash and partial startup restore proxy and owned transport resources; no duplicate workers. | Lifecycle checkpoint. |
| V5 TLS | Concurrent same-host/different-host certificate creation; stable CA; actionable stage errors; H1/H2/WSS decoding unchanged. | TLS checkpoint. |
| V6 App isolation | Verified target retained; same-host non-target and unknown traffic excluded and passed through without MITM; forged metadata rejected. | Each platform attribution checkpoint. |
| V7 Agent contract | `start -> snapshot`, session catalog, explicit session reads, full `get`, stopped history, errors, and schema migration. | CLI cutover. |
| V8 Release artifact | Extracted `lynx` archive outside repo with bundled relay/libs/skill; repeat V0, V2, V4, V6, V7; verify Linux separately. | Final supported-platform checkpoint. |

Detailed commands, evidence requirements, and failure classification are in the
[verification plan](2026-09-22-native-inspection-verification.md). A public-service
outage, unavailable device, or skipped live check is `blocked/not-run`, never a
pass. Do not weaken an assertion or alter the sample app to move to the next item.

## Implementation sequence and commit checkpoints

M1.1 is a native correctness follow-up to M1. Existing M1 ticket statuses often
describe the JVM backend and must not be treated as proof of native parity.

| Order / ticket | Independently reviewable deliverable | Required gate | Planned commit / local checkpoint tag |
|---|---|---|---|
| M1.1-P0 (after 00 read-only inventory) | Separate critical macOS proxy restoration fix; see priority plan. | VP0 + V0 before completion | `fix(network): recover macOS proxy leases` / `checkpoint/m1-1/P0-proxy-recovery` |
| M1.1-00 | Preserve baseline artifacts, add external verification harness and evidence ledger. | V0 | `test: preserve native inspection baseline` / `checkpoint/m1-1/00-baseline` |
| M1.1-01 | Bounded attribution proof with real app traffic; document limits and measured lookup timing. | V1 + V0 | `test: prove app socket ownership` / `checkpoint/m1-1/01-ownership-proof` |
| M1.1-02 | Shared tool resolution and usable-tool diagnostics, including existing DB callers. | V2 resolver cases + V0 | `fix(cli): discover native host tools` / `checkpoint/m1-1/02-tools` |
| M1.1-03 | Combined devices, attachment indicators, doctor, first-run guidance. | V2 + V0 | `feat(cli): add unified device discovery` / `checkpoint/m1-1/03-devices` |
| M1.1-04 | Versioned session storage, unique IDs, immutable ownership/context interfaces. | V3 + V0 | `feat(network): isolate capture session state` / `checkpoint/m1-1/04-sessions` |
| M1.1-05 | Transactional worker/proxy lifecycle, restart handling, crash recovery. | V4 + V0 | `fix(network): restore capture resources` / `checkpoint/m1-1/05-lifecycle` |
| M1.1-06 | Reproduce/fix certificate issuance failures and preserve diagnostic causes. | V5 + V0 | `fix(network): serialize certificate issuance` / `checkpoint/m1-1/06-tls` |
| M1.1-07 | Shared connection gate plus iOS Simulator ownership adapter and pass-through. | V6 iOS + V0 both | `feat(network): scope simulator capture to app` / `checkpoint/m1-1/07-ios-scope` |
| M1.1-08 | Production Android relay, host transport, ownership resolver, concurrent-client handling, cleanup and ABI packaging. | V6 Android + V0 both; H1/H2/WS must coexist in one capture | `feat(network): verify Android socket owners` / `checkpoint/m1-1/08-android-scope` |
| M1.1-WS | Publish open WebSocket handshake/data/control frames incrementally; no close required. | Live reads before close + V0 + V6 | `fix(network): publish live websocket frames` / `checkpoint/m1-1/WS-live-frames` |
| M1.1-09 | Native CLI session catalog/snapshot/scoped list/get; update consumers atomically. | V7 + V0 + V6 both | `feat(cli)!: scope network inspection sessions` / `checkpoint/m1-1/09-scoped-cli` |
| M1.1-09a | Cross-target lifecycle hardening: an active capture is immutable to its attached device/app; snapshot/start reject a different attachment, while stop/detach restore the persisted capture target instead of routing iOS identifiers through ADB. | V7 + iOS stop/detach regression + V0 | `fix(network): isolate cross-target cleanup` / `checkpoint/m1-1/09a-cross-target-cleanup` |
| M1.1-09b | iOS snapshot and cleanup regression: validate active iOS snapshots with real target traffic; prevent stale Android proxy records from invoking ADB during iOS stop/detach; preserve Android cleanup and database behavior. | iOS snapshot non-empty positive + no-traffic contract + no-ADB stop/detach + V0 | Verified in `c41d81c` / `checkpoint/m1-1/09b-ios-cleanup` |
| M1.1-10 | Extracted distribution, bundled agent instructions, complete regression report. | V8 | `6d4d1cc` / `checkpoint/m1-1/10-macos-release-ready` |

Execution is sequential. Inactive adapter code may be added behind internal test
entrypoints before cutover, while existing protocol checks still pass. Do not
introduce a public option that bypasses app-only filtering. A platform capability
is not announced until its own ownership gate passes.

Read these component plans in dependency order:

- Priority exception: [macOS proxy recovery](2026-09-22-macos-proxy-recovery.md), M1.1-P0, after 00 read-only inventory and before completing its live gate.
- Separate fix: [live WebSocket capture](2026-09-22-live-websocket-capture.md), M1.1-WS, after 08 and before 09.

1. [Baseline, feasibility, checkpoint and release verification](2026-09-22-native-inspection-verification.md): M1.1-00, 01, 10.
2. [Tool and device discovery](2026-09-22-native-host-discovery.md): M1.1-02, 03.
3. [Session state, lifecycle, TLS, attribution and CLI cutover](2026-09-22-app-scoped-network-capture.md): M1.1-04 through 09.
4. [Cross-target cleanup regression](../bugs/2026-09-23-cross-target-capture-cleanup.md): M1.1-09a.

### Added regression reports (2026-09-23)

The latest iOS reports are explicitly part of M1.1-09b, not separate unscoped
work:

- `network snapshot --json` returned `schema_version: lynx.v2`,
  `through_sequence: 0`, and `exchanges: []` while the attached sample app was
  producing traffic. The fix must prove a non-empty, target-attributed snapshot;
  an empty no-traffic result remains valid and must be tested separately.
- `network stop --json` routed the simulator UDID through Android cleanup and
  failed with `adb: unknown host service '<UDID>:features'`.
- `detach --json` surfaced the same failure through its stop-before-detach path.

The acceptance gate is iOS host-proxy cleanup only: no ADB or Android-relay
invocation, idempotent stop/detach, restored proxy state, and no regression in
Android cleanup or read-only database commands.

## Checkpoint procedure

For every runtime work item, in order:

- [ ] Add a focused test that fails for the reported behavior; record the failure.
- [ ] Implement that item only; run the focused test and confirm the cause is addressed.
- [ ] Run the native regression gate and JVM compatibility checks described in V0.
- [ ] Run real Android and iOS H1/H2/WSS plus database list/snapshot/tables/query checks.
- [ ] Run the item's negative/lifecycle checks; compare baseline evidence and app hashes.
- [ ] Review the diff and check output privacy; update the local evidence ledger.
- [ ] Commit only the ticket's scoped files and its sanitized verification report.
- [ ] From the committed tree, rebuild/retest without source changes; record exact commit,
  binary/archive hashes and outputs. Create the local annotated checkpoint tag only now.
- [ ] Advance to the next ticket only after this gate is green.

Docs-only plan commits require link/content/diff validation, not a runtime tag.
Do not combine work items in one patch or label partially tested builds working.
Do not amend a verified checkpoint; corrections get another commit and gate.

Evidence directories live under ignored `build/verification/m1-1/<ticket>/<run>/`.
Commit only a sanitized summary, never raw unrelated traffic, certificates,
private keys, application database files, tokens, or screenshots with secrets.
The report committed with a patch records its base revision/tree; the post-commit
local ledger/tag records the resulting commit, avoiding a self-referential hash.

## Rollback

Keep the baseline executable **and matching native libraries**, hashes, and CA
fingerprint locally. Preserve existing CA material without rotating it. Preserve
old unscoped logs; use a versioned new state directory with no destructive migration.
Before trying an older executable, stop the new capture, restore its proxy/ADB
leases, and confirm its workers are gone. Use a separate checkout of a checkpoint
or an explicit revert commit; never hard-reset unrelated work or overwrite a
user's current proxy choices. New code must not make legacy state unreadable.

## Exit condition

- [ ] All tickets have verified local checkpoints and reproducible evidence.
- [ ] Both native app-only commands exclude same-destination non-target controls.
- [ ] iOS snapshot reports real target exchanges, and iOS stop/detach never route
      simulator identifiers through ADB.
- [ ] Existing DB and real H1/H2/WSS results remain readable and correct on both targets.
- [ ] No source changes occurred in the sample apps.
- [ ] Missing ownership is explicit; physical-iOS/background-service limits are documented.
- [ ] Host/device/proxy state is restored and the extracted macOS distribution is verified.
- [ ] Linux/physical-device rows are honestly marked verified or pending; no unavailable CI run is claimed.
