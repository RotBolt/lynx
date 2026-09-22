# Lynx Implementation Tickets

These tickets implement [MILESTONES.md](MILESTONES.md) and the architecture in
`ARCHITECTURE.md` and `IMPLEMENTATION.md`. Each ticket is intentionally scoped
to one reviewable change and has an explicit verification gate.

## M1.1 — Native correctness follow-up (all planned)

Detailed file maps, acceptance tests, dependencies and checkpoint rules are in the
[master implementation plan](../docs/superpowers/plans/2026-09-22-native-inspection-hardening.md).
Each ticket depends on the previous row; 00 has no dependency.

| Ticket | Scope | Acceptance gate |
|---|---|---|
| M1.1-00 | Baseline and external evidence harness | V0: both apps, DB and real H1/H2/WSS |
| M1.1-01 | Real socket ownership feasibility | V1: target and same-destination non-target |
| M1.1-02 | Native tool resolution | V2 resolver cases + V0 |
| M1.1-03 | Unified devices, doctor, first run | V2 + V0 |
| M1.1-04 | Isolated capture storage/context | V3 + V0 |
| M1.1-05 | Worker/proxy lifecycle recovery | V4 + V0 |
| M1.1-06 | Certificate issuance diagnostics/fix | V5 + V0 |
| M1.1-07 | iOS Simulator admission/ownership | V6 iOS + V0 both |
| M1.1-08 | Android relay/ownership | V6 Android + V0 both |
| M1.1-09 | Session catalog, snapshot, scoped list/get | V7 + V6 both + V0 |
| M1.1-10 | Extracted distribution and agent docs | V8 |

No ticket may be marked complete from unit tests alone; follow the master plan's
real-device/simulator regression and committed-tree checkpoint procedure.

## M1-01 — Versioned daemon command envelope
**Status:** Implemented

**Priority:** P0 · **Depends on:** none

### Scope

Replace the plain-text-only daemon contract with a versioned JSON request and
response envelope while retaining a human-readable renderer for interactive
use.

### Implementation details

- Add `protocol_version: 1` and `schema_version: "lynx.v1"`.
- Add command name, request ID, arguments, response type, and structured errors.
- Keep Unix-domain socket transport, but make each request/response one complete
  JSON line.
- Reject unsupported protocol versions deterministically.

### Acceptance criteria

- `attach`, `status`, and `detach` work through JSON requests.
- Every finite response contains `type` and `schema_version`.
- Every error contains `code`, `message`, `operation`, and `retryable`.
- Existing text mode renders the JSON response without changing semantics.

### Tests

- JSON request parsing;
- protocol/schema version rejection;
- structured error serialization;
- socket round trip.

## M1-02 — SessionSupervisor and process restart rebinding
**Status:** Implemented

**Priority:** P0 · **Depends on:** M1-01

### Scope

Make sessions package/device based instead of PID based and preserve the same
session across app process restarts.

### Implementation details

- Add states `ATTACHED`, `PROCESS_LOST`, `REATTACHING`, `DETACHED`.
- Poll `pidof <package>` on a configurable interval.
- Update the current PID when a replacement process appears.
- Preserve `session_id` and timeline evidence.
- Emit process lifecycle JSONL events.

### Acceptance criteria

- PID changes do not create a new session.
- Evidence records retain the PID observed at capture time.
- Database operations use the current PID/run-as context.
- Detach permanently ends the session.

### Tests

- same PID remains attached;
- missing PID enters `PROCESS_LOST`;
- new PID returns to `ATTACHED`;
- detach prevents rebinding.

## M1-03 — Network proxy engine adapter spike
**Status:** Implemented for the MVP proxy adapter; HTTP/1.1, HTTPS CONNECT MITM, HTTP/2, and WebSocket capture verified

**Priority:** P0 · **Depends on:** M1-01

### Scope

Select and integrate a mature embeddable proxy engine behind Lynx-owned
interfaces. Do not extend the temporary hand-written HTTP parser.

### Implementation details

- Define `ProxyEngine`, `ProxyEvent`, `ProxyEndpoint`, and `ProxyCapabilities`.
- Verify HTTP/1.1, CONNECT, TLS MITM, callbacks, body streaming, timing,
  shutdown, licensing, and JVM compatibility.
- Keep engine-specific request/response types inside the adapter module.
- Record the selected engine and version in `UPSTREAM.md`.

### Acceptance criteria

- HTTP request/response lifecycle events are emitted.
- CONNECT behavior is tested.
- Engine shutdown is deterministic.
- Unsupported protocols are reported through capabilities.

### Tests

- HTTP success;
- HTTP failure;
- CONNECT/TLS handshake;
- redirect;
- body streaming;
- shutdown and bind failure.

## M1-12 — CA lifecycle and first-run onboarding
**Status:** Implemented

**Priority:** P0 · **Depends on:** M1-03

### Scope

Generate and manage Lynx CA material independently of a proxy instance, expose
the certificate and fingerprint on demand, and explain platform trust setup on
first network start.

### Acceptance criteria

- First network setup creates stable host CA material.
- `network ca show` returns PEM path, fingerprint, and install guidance.
- `network ca install` performs only supported platform automation and reports
  user-confirmation requirements.
- `network ca remove` is idempotent and never removes unrelated certificates.
- `network doctor` reports CA/trust state.

## M1-13 — Android and Apple certificate installers
**Status:** Implemented for Android staging, iOS Simulator installation, and physical-iOS profile generation; trust confirmation remains explicit

**Priority:** P0 · **Depends on:** M1-12

### Scope

Automate Android staging/install flows, iOS Simulator `simctl` trust setup, and
physical iOS profile delivery with explicit user confirmation states.

### Acceptance criteria

- Android emulator/device state distinguishes staged, installed, and app-debug
  trust-required.
- iOS Simulator uses the selected UDID and supports cleanup.
- Physical iOS reports profile URL/path and required trust confirmation.

CLI support includes Android certificate staging/installer launch, iOS Simulator
`simctl` installation, and physical-iOS `.mobileconfig` profile generation.
The host cannot inspect a physical device's final user-trust toggle, so that
state remains explicitly user-confirmed rather than guessed.

## M1-14 — HTTP/2 proxy capture
**Status:** Implemented in the KMP native proxy and JVM adapter; native macOS
verification covers a real TLS HTTP/2 client and Linux cinterop/compile paths

**Priority:** P0 · **Depends on:** M1-03

### Scope

Replace the temporary HTTP parser behind the proxy boundary with a passive,
native HPACK/frame observer that decodes HTTP/2 over TLS and preserves complete
request/response evidence without changing application code or transport bytes.

## M1-15 — WebSocket capture
**Status:** Implemented and verified with an RFC 6455 upgrade/frame integration test

**Priority:** P0 · **Depends on:** M1-14

### Scope

Capture WebSocket upgrades and text/binary frames, retaining close/error events
and exposing them through versioned JSON/JSONL evidence.

## M1-16 — Android proxy compatibility (terminal-only)
**Status:** Implemented for Android system-proxy traffic; bypass/direct-socket limitation remains explicit

**Priority:** P0 · **Depends on:** M1-04, M1-05

### Scope

Configure the complete Android proxy setting set used by desktop proxy tools
(`http_proxy`, `https_proxy`, and host/port keys) for debuggable emulator and
device targets. Capture remains host-side and terminal-only; no VPN service,
companion app, or application code is introduced.

### Acceptance criteria

- No application proxy configuration is required for traffic honoring Android's
  system proxy.
- Lynx reports the configured proxy endpoint and trust state.
- Direct sockets that bypass the system proxy are reported as unsupported.
- Stop/detach restores the prior network state.

## M1-04 — Android System Proxy controller
**Status:** Implemented

**Priority:** P0 · **Depends on:** M1-02, M1-03

### Scope

Apply and restore the Android System Proxy for an attached target.

### Implementation details

- Inspect current proxy state through ADB.
- Select a concrete device endpoint (`10.0.2.2` for emulator or host LAN IP).
- Apply proxy with a session-scoped `ProxyLease`.
- Verify reachability through a probe request.
- Restore the exact previous state on stop, detach, shutdown, and failure.
- Make restoration idempotent.

### Acceptance criteria

- Existing proxy settings are restored byte-for-byte where supported.
- Partial startup failures restore the prior state.
- `0.0.0.0` is never sent as the device endpoint.
- Diagnostics distinguish configured, unreachable, bypassed, and restored states.

### Tests

- emulator endpoint selection;
- proxy state inspect/apply/restore;
- repeated restore;
- startup rollback;
- reachability failure.

## M1-05 — Network evidence adapter and body store
**Status:** Implemented for session-owned in-memory retention

**Priority:** P0 · **Depends on:** M1-03, M1-04

### Scope

Convert proxy events into canonical `NetworkExchange` evidence and expose
complete exchanges by request ID.

### Implementation details

- Add an `ExchangeAccumulator` state machine.
- Preserve request/response headers, bodies, status, failures, and timing.
- Store complete bodies in a session-owned body store.
- Keep metadata indexing separate from body bytes.
- Do not silently truncate or discard failures.

### Acceptance criteria

- `network list` returns structured summaries.
- `network get` returns the complete request and response.
- Failed exchanges are retrievable.
- Storage failure returns `STORAGE_EXHAUSTED`.

### Tests

- complete HTTP exchange;
- failed upstream;
- TLS failure;
- request/response body persistence;
- retrieval by request ID;
- storage failure.

## M1-06 — SQLite database catalog and source IDs
**Status:** Implemented

**Priority:** P0 · **Depends on:** M1-02

### Scope

Discover app-private SQLite database sets through ADB/run-as and assign stable
`database_id` values.

### Implementation details

- Discover `.db`, `.sqlite`, and `.sqlite3` files.
- Associate `-wal` and `-shm` sidecars with their main database.
- Validate SQLite headers instead of trusting extensions alone.
- Return descriptors with session ID, relative path, size, and sidecar state.

### Acceptance criteria

- Database IDs remain stable while the app is attached.
- WAL/SHM membership is visible in discovery output.
- Missing or inaccessible databases produce structured errors.

### Tests

- direct SQLite database;
- Room-style WAL set;
- invalid extension/non-SQLite file;
- run-as failure;
- process restart rebinding.

## M1-07 — WAL-safe snapshot acquisition
**Status:** Implemented with online-backup and explicit fallback

**Priority:** P0 · **Depends on:** M1-06

### Scope

Replace main-file-only copying with a point-in-time SQLite snapshot strategy.

### Implementation details

- Evaluate device-side SQLite Online Backup API/helper first.
- Keep acquisition behind `SnapshotAcquirer`.
- Validate the host artifact with SQLite open and `quick_check` where useful.
- Set `consistent=true` only when the acquisition method guarantees coherence.
- Otherwise return `SNAPSHOT_INCONSISTENT` or an explicitly unsafe artifact.

### Acceptance criteria

- Committed rows remain visible during concurrent writes.
- Snapshot opens successfully on the host.
- Main DB/WAL/SHM state is represented in the source fingerprint.
- Snapshot metadata includes method, timestamp, consistency, and ID.

### Tests

- repeated snapshots during concurrent writes;
- WAL database;
- malformed acquisition;
- validation failure;
- snapshot fingerprint changes.

## M1-08 — Read-only SQLite query engine
**Status:** Implemented

**Priority:** P0 · **Depends on:** M1-07

### Scope

Expose tables, schema, and complete SQL results against live databases or
session-owned snapshots.

### Implementation details

- Add `DatabaseInspector`, `QueryTarget`, `QueryResponse`, and schema models.
- Open SQLite read-only.
- Allow multiple statements only when every statement is read-only.
- Use parser/prepare checks plus SQLite authorizer enforcement.
- Reject writes, schema mutations, attachment, mutating PRAGMA, and transaction
  control.
- Enforce a hard 15-minute deadline and interrupt timed-out work.
- Return every row and column; encode BLOBs as base64 JSON.

### Acceptance criteria

- `db tables --snapshot <id> --json` works.
- `db schema --snapshot <id> --json` works.
- `db query --snapshot <id> ... --json` returns all result sets.
- Mutating SQL returns `SQL_READ_ONLY_VIOLATION`.
- Timeout returns `QUERY_TIMEOUT`.
- Snapshot queries fail with `SESSION_DETACHED` after detach.

### Tests

- all SQLite scalar types;
- BLOB round trip;
- multiple SELECT statements;
- each forbidden statement class;
- timeout/cancellation;
- detached snapshot;
- complete large result serialization.

## M1-09 — Agent-facing JSON/JSONL serializers
**Status:** Implemented for finite JSON and one-shot JSONL watch output

**Priority:** P0 · **Depends on:** M1-05, M1-08

### Scope

Make network, database, timeline, and error results consumable without parsing
human-readable text.

### Implementation details

- Serialize every finite response as one JSON document.
- Serialize live events as independent JSONL records.
- Include `type`, `schema_version`, session ID, evidence/request/snapshot IDs,
  timestamps, and capability/consistency fields.
- Preserve complete values and explicit base64 BLOB encoding.

### Acceptance criteria

- An agent can retrieve a complete network exchange by request ID.
- An agent can retrieve tables/schema/query data by snapshot ID.
- Unknown additive fields can be ignored by clients.
- Errors have stable machine-readable codes.

### Tests

- golden JSON records;
- JSONL stream parsing;
- unknown-field compatibility;
- error envelope;
- BLOB and NULL serialization.

## M1-10 — End-to-end attach/debug workflow
**Status:** Implemented and verified across independent CLI invocations, app PID restart, and detach cleanup

**Priority:** P0 · **Depends on:** M1-02 through M1-09

### Scope

Wire the collectors and APIs into the daemon and CLI for one complete debugging
workflow.

### Implementation details

- `attach` creates the session and supervisor.
- `network start/stop/list/get` controls and queries the network collector.
- `db list/snapshot/tables/schema/query` controls and queries the database path.
- `detach` closes collectors, restores proxy, and invalidates snapshots.
- Keep session state across independent shell invocations.

### Acceptance criteria

- Real debuggable app attaches without a supplied PID.
- HTTP request is observable and retrievable as JSON.
- Database snapshot is queryable as JSON.
- App restart preserves session and resumes inspection.
- Detach cleanup is complete and deterministic.

### Tests

- daemon integration test with fake ADB/proxy/database adapters;
- independent CLI invocation test;
- process restart test;
- detach cleanup test;
- manual emulator/app smoke test.

## M1-11 — Manual smoke test and release gate
**Status:** Android emulator gate passed; iOS Simulator and physical-device trust artifacts are verified by adapter tests and CLI output

**Priority:** P0 · **Depends on:** M1-10

### Scope

Run the complete workflow against a real macOS emulator or device and record
the exact commands and outputs.

### Acceptance criteria

- `lynx doctor` resolves ADB.
- `lynx attach` succeeds for a debuggable package.
- Network capture records a reproducible request.
- `network get` returns complete structured data.
- `db list` finds a real database.
- `db snapshot` returns a validated snapshot ID.
- `db query` returns actual rows and BLOB values.
- Proxy state is restored after stop/detach.

### Required manual environment

- macOS;
- Android emulator or USB device;
- ADB platform-tools;
- debuggable app with one HTTP/HTTPS request;
- debuggable app with a SQLite database, preferably WAL-enabled.
