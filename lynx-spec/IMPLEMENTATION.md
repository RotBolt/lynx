# Implementation Details

## 1. Network proxy

Select a mature embeddable proxy library before implementing HTTP/TLS ourselves.

Required:
- HTTP/1.1;
- HTTPS MITM;
- CONNECT;
- programmable callbacks;
- headers;
- request/response bodies;
- timing;
- complete body capture for the session retention policy;
- CA support;
- graceful shutdown;
- no GUI dependency.

Hide it behind `NetworkCaptureSource`.

Implement a `ProxyEngine` adapter that emits request-start, headers, body
chunks, response, completion, and failure events. `CONNECT` establishes a
per-host MITM TLS session using a Lynx CA; certificate trust and pinning failures
remain explicit outcomes. A separate `AndroidProxyController` applies the
device System Proxy and returns a lease containing the prior state. The lease
must restore on every lifecycle exit and be safe to restore repeatedly.

The engine must support HTTP/1.1 and CONNECT/TLS interception before the
Network MVP gate. Model each exchange as request headers/body, upstream
connection, response headers/body, then completed or failed. Store complete
bodies for the active session and expose them through `network get`; do not
silently drop failed exchanges.

## 2. Android proxy setup

Flow:

```text
start host proxy
→ determine host endpoint reachable by device/emulator
→ save existing Android proxy
→ configure proxy via ADB
→ verify reachability
→ capture
→ restore old proxy on stop/detach
```

Do not assume device `127.0.0.1` points to host.

## 3. TLS

HTTPS interception needs a Lynx CA trusted by the debuggable app.

Provide:

```bash
lynx network doctor
```

Explicitly diagnose:
- CA not trusted;
- TLS interception failure;
- likely certificate pinning;
- likely proxy bypass.

Do not require root and do not silently disable pinning.

## 4. Network storage

Proxy callbacks become internal events:
- request started;
- headers;
- body chunks;
- response headers;
- response body;
- completed;
- failed.

Assemble to canonical `NetworkExchange`.

Bodies are written to a session-owned `BlobStore` as they arrive and referenced
by stable request IDs. `network list` may omit body bytes for readability;
`network get` must stream the complete stored bodies. No semantic truncation or
silent dropping is allowed; report `STORAGE_EXHAUSTED` if persistence fails.

The daemon retains evidence while the session is alive, including while the
app process is temporarily lost. A replacement PID is rebound to the same
session after polling. Detach ends snapshot/query access and cleans session
resources after collectors and proxy restoration complete.

## 5. DB discovery

Use:

```bash
adb shell run-as <package> ...
```

to inspect app-private database files.

Detect DB/WAL/SHM sets and validate SQLite files instead of trusting file extensions only.

## 6. Consistent SQLite snapshot

This is the main DB architecture spike.

Do NOT define `adb pull app.db` as the correctness model.

Evaluate:

1. on-device SQLite backup API/tool;
2. controlled DB + WAL + SHM capture;
3. another SQLite-native point-in-time snapshot mechanism.

The selected method must survive concurrent writes in integration tests.

After acquisition:
- open host-side;
- validate readability;
- run `quick_check`/appropriate validation when useful;
- record consistency method.

## 7. Host SQLite

Support:
- tables;
- schema;
- read-only query;
- snapshots;
- diffs.

Normalize:
NULL, INTEGER, REAL, TEXT, BLOB.

Return BLOB bytes as base64 JSON. Return every selected row and column; do not
silently trim results. The query service accepts multiple statements only when
all are read-only and rejects mutating statements through parser, SQLite
authorizer, and read-only connection checks. Enforce a 15-minute deadline.

## 8. DB watcher

Algorithm:

```text
fingerprint DB/WAL
if unchanged -> sleep
if changed:
    acquire consistent snapshot
    run bounded query/diff
    compare hash/result
    emit if meaningful
```

Guardrails:
- interval;
- debounce;
- one snapshot in flight per DB;
- session retention and disk accounting (no semantic row/body limits in MVP);
- cleanup.

Database IDs are stable app-relative paths; snapshot IDs identify immutable
captured states. Snapshot query access ends when the owning session detaches.
The session itself survives an app PID change and rebinds to the replacement
process.

## 9. Evidence Timeline

Index by:
- session;
- time;
- evidence type;
- request ID;
- DB ID;
- snapshot ID.

Host APIs:

```kotlin
interface EvidenceTimeline {
    fun append(evidence: Evidence)
    fun network(filter: NetworkFilter): List<NetworkExchange>
    fun snapshots(databaseId: DatabaseId): List<DatabaseSnapshot>
    fun between(from: Instant, to: Instant): List<EvidenceSummary>
}
```

## 10. Example debugging flow

```bash
lynx network start
lynx db watch app.db --table users --jsonl
```

After reproducing:

```bash
lynx network list --since 60s --json
lynx network get req_42 --json
lynx db snapshots app.db --since 60s --json
lynx db diff snap_12 snap_13 --table users --json
lynx timeline --since 60s --json
```

## 11. Runtime attribution later

Only after evidence foundation is stable.

Potential JVMTI producers:
- network request creation callsite;
- DB operation callsite.

They append `RuntimeAttribution` to the same timeline.

No proxy/SQLite contract changes should be required.

## 12. Tests

Network fixture:
- HTTP;
- HTTPS;
- redirects;
- failure;
- large body;
- pinning failure;
- proxy bypass where practical.

DB fixture:
- Room WAL DB;
- direct SQLite;
- concurrent writes;
- schema migration;
- mixed SQLite types.

Primary DB gate: repeated snapshots during concurrent writes remain consistent and contain committed data.

## 13. Machine contract

JSON and JSONL records include `type` and `schema_version: "lynx.v1"`.
Daemon requests/responses include `protocol_version: 1`. Errors use stable codes
such as `NO_ACTIVE_SESSION`, `PROCESS_LOST`, `PROXY_CONFIG_FAILED`,
`CA_NOT_TRUSTED`, `TLS_PINNING_DETECTED`, `SNAPSHOT_NOT_FOUND`,
`SQL_READ_ONLY_VIOLATION`, `QUERY_TIMEOUT`, and `STORAGE_EXHAUSTED`, together
with operation, resource, message, retryability, and session ID.

Machine-facing errors use a versioned JSON envelope with stable codes. The
daemon transport and JSON schema have independent versions (`protocol_version`
and `schema_version`). Multiple read-only SQL statements are allowed, but every
statement must pass SQLite read-only enforcement; query timeout is 15 minutes.
