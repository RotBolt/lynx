# Feature Requirements

## Global

- Debuggable Android apps only.
- No Android Studio/App Inspection dependency in MVP.
- JSON for finite responses.
- JSONL for live streams.
- Session-owned retention; no semantic truncation in the MVP. Storage exhaustion
  is reported explicitly rather than silently dropping evidence.
- Shared evidence metadata: evidence ID, session ID, time, source, device, package, PID.
- Every machine-facing response includes `type` and `schema_version`.
- Finite commands return JSON; live commands return one self-contained JSONL record per event.
- `session_id` survives app process restarts while the Lynx session remains attached.

## Network

### Start/stop
```bash
lynx network start
lynx network stop
```

Must restore the exact prior Android System Proxy state transactionally on stop,
detach, shutdown, or startup failure. Restoration is idempotent.

### Watch/history
```bash
lynx network watch --jsonl
lynx network list --since 60s --json
lynx network get req_42 --json
```

Capture where available:
- method;
- URL;
- status/failure;
- request/response headers;
- complete request/response bodies;
- timestamps/duration.

### Filters
- method;
- status;
- URL substring;
- since;
- limit (a presentation/filter parameter, never silent data truncation).

### Diagnostics
```bash
lynx network doctor
```

Report:
- proxy reachable;
- proxy configured;
- CA state;
- HTTPS interception health;
- likely pinning;
- likely proxy bypass.

`network get` returns the complete stored exchange, including failed exchanges.
Never interpret an empty capture as proof that no network request happened.
The capability response must identify HTTP, HTTPS MITM, HTTP/2, WebSocket,
QUIC/HTTP3, CA trust, pinning, and proxy-bypass limitations. Lynx must not
claim coverage for traffic that bypasses the configured proxy or unsupported
transports.

## Database

### Discovery
```bash
lynx db list --platform android --device emulator-5554 --package com.example.app --json
lynx db list --platform ios --simulator <simulator-udid> --bundle-id <bundle-id> --json
```

Discover SQLite DBs via ADB + `run-as`.

### Snapshot
```bash
lynx db snapshot databases/app.db --platform android --device emulator-5554 --package com.example.app
lynx db snapshot Documents/app.db --platform ios --simulator <simulator-udid> --bundle-id <bundle-id>
```

Platform selection is an option on the same command; do not create platform-
specific command names such as `ios-list` or `ios-snapshot`. Android uses
`--device` with an ADB serial (emulator or physical device) and `--package`
with the Android application ID. iOS Simulator uses `--simulator` with the
Simulator UDID and `--bundle-id` with the app bundle identifier. The old iOS
aliases `--device` and `--package` remain accepted for compatibility.

Must account for WAL and declare:
- snapshot ID;
- timestamp;
- consistency method;
- consistency flag;
- source fingerprint.

`database_id` is the stable app-relative database path. `snapshot_id` identifies
one immutable state within the attached session. Snapshot queries are valid only
while that session remains attached.

### Query/schema
```bash
lynx db query --snapshot snap_12 "SELECT ..." --json
lynx db tables --snapshot snap_12 --json
lynx db schema --snapshot snap_12 --json
```

MVP is read-only. Snapshot IDs address an exact database state; database IDs
address the live app-relative database. Snapshots are queryable only while the
owning session is attached.

Query results return every selected column and row. SQLite NULL, INTEGER, REAL,
TEXT, and BLOB values have stable JSON representations; BLOB data is returned
as base64. No semantic result trimming is performed. Multiple statements are
allowed only when every statement is read-only. CREATE, ALTER, UPDATE, INSERT,
DELETE, DROP, ATTACH, DETACH, mutating PRAGMA, and transaction-control
statements are rejected. Query execution has a 15-minute deadline.

Queries may contain multiple statements only when every statement is read-only.
All result sets, columns, rows, and BLOB values are returned. BLOBs use an
explicit base64 JSON representation. No semantic result truncation is applied.
Queries have a 15-minute timeout and return a structured error on timeout or a
read-only violation.

```json
{
  "type": "database_query",
  "schema_version": "lynx.v1",
  "snapshot_id": "snap_123",
  "read_only": true,
  "results": [{
    "columns": [{"name": "payload", "declared_type": "BLOB"}],
    "rows": [[{"encoding": "base64", "data": "AAECAw=="}]]
  }]
}
```

### History
```bash
lynx db snapshots app.db --since 2m --json
```

### Watch
```bash
lynx db watch app.db --table users --jsonl
lynx db watch app.db --query "SELECT ..." --jsonl
```

Semantics:
- detect change;
- acquire safe snapshot;
- bounded query/diff;
- emit meaningful change.

Not CDC.

### Diff
```bash
lynx db diff snap_12 snap_13 --json
```

Support schema and keyed table/query diffs.

## Timeline

```bash
lynx timeline --since 30s --json
```

Return merged chronological summaries across Network + DB.

## Later runtime attribution

Call stacks/source/thread data enrich existing evidence IDs. They do not create a separate debugging model.

## Non-goals for foundational MVP

- Android Studio Network Inspector;
- Android Studio Database Inspector;
- JVMTI;
- Layout Inspector;
- request mocking;
- DB writes;
- non-debuggable apps.
