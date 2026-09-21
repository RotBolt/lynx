# Architecture

## 1. Core model

Lynx is an **evidence collection system**, not a headless Android Studio clone.

```text
NetworkSource              DatabaseSource
     │                           │
ProxyCapture                SQLiteObserver
     │                           │
     └──── canonical evidence ───┘
                  │
           Evidence Timeline
                  │
         CLI / JSONL / TUI

        optional future layer
                  │
          Runtime Attribution
           JVMTI/call stacks
```

Network and DB functionality must remain fully usable without runtime attribution.

## 2. Evidence Timeline

Every evidence item shares metadata:

```kotlin
data class EvidenceMeta(
    val id: EvidenceId,
    val sessionId: SessionId,
    val observedAt: Instant,
    val source: EvidenceSource,
    val deviceSerial: String,
    val packageName: String,
    val processId: Int?
)
```

Network:

```kotlin
data class NetworkExchange(
    val meta: EvidenceMeta,
    val requestId: RequestId,
    val request: NetworkRequest,
    val response: NetworkResponse?,
    val failure: NetworkFailure?,
    val timing: NetworkTiming,
    val capture: NetworkCaptureMetadata
)
```

Database:

```kotlin
data class DatabaseSnapshot(
    val meta: EvidenceMeta,
    val snapshotId: SnapshotId,
    val databaseId: DatabaseId,
    val sourceFingerprint: DatabaseFingerprint,
    val localPath: Path,
    val consistent: Boolean,
    val consistencyMethod: String
)
```

Future attribution:

```kotlin
data class RuntimeAttribution(
    val meta: EvidenceMeta,
    val threadId: Long?,
    val callstack: List<StackFrame>,
    val correlatedEvidenceIds: List<EvidenceId>
)
```

This lets us correlate later without changing Network/DB schemas.

## 3. Network architecture

```text
Android app
   │ HTTP/HTTPS
   ▼
Lynx proxy
   │
   ├── request/response lifecycle
   ├── headers
   ├── complete bodies (session retention)
   └── timing
   │
   ▼
NetworkCaptureAdapter
   │
   ▼
NetworkExchange
   │
   ▼
Evidence Timeline
```

Interface:

```kotlin
interface NetworkCaptureSource {
    suspend fun start(config: NetworkCaptureConfig)
    suspend fun stop()
    fun events(): Flow<NetworkDomainEvent>
    suspend fun capabilities(): NetworkCapabilities
}
```

The proxy engine is replaceable and never leaks into public models.

The production boundary has two adapters:

```kotlin
interface ProxyEngine { /* HTTP/HTTPS lifecycle and capabilities */ }
interface AndroidProxyController {
    /* inspect/apply/restore Android System Proxy with a transactional lease */
}
```

The controller records the exact previous proxy configuration and restores it
on stop, detach, daemon shutdown, and failed startup. A lease is idempotent.
HTTP and HTTPS MITM are separate capabilities; certificate trust, pinning,
proxy bypass, and unsupported native/custom transports are explicit diagnostics.

Each completed or failed exchange is retained as a canonical `NetworkExchange`
and can be retrieved by request ID. `network list` may return summaries, while
`network get` returns the full stored request/response body. There is no silent
semantic truncation. Retention is session-owned and may use disk-backed blobs;
storage exhaustion is an explicit error.

## 4. Android proxy control

Owns:
- configure/remove Android system proxy;
- determine device-reachable host endpoint;
- save and restore previous proxy state;
- CA setup/diagnostics;
- reachability checks.

Limitations must be explicit:
- app may ignore proxy;
- CA may not be trusted;
- pinning may block MITM;
- custom/native transports may bypass proxy.

Proxy control is session-scoped and transactional. Lynx records the prior
Android System Proxy state, applies a concrete device-reachable endpoint, and
restores the exact prior state on network stop, detach, daemon shutdown, or
partial startup failure. Emulator `10.0.2.2` and physical-device LAN
addresses are endpoint choices; `0.0.0.0` is only a bind address.

The proxy engine is replaceable behind an adapter. Public models must not expose
proxy-library request, response, flow, or TLS types. Failed exchanges are
canonical evidence, not discarded errors. Complete bodies remain retrievable
through `network get`.

## 5. Database architecture

```text
debuggable app
   │
 adb + run-as
   │
 databases/
   ├── app.db
   ├── app.db-wal
   └── app.db-shm
   │
 consistent snapshot acquisition
   │
 host SQLite
   │
 schema/query/diff
   │
 DatabaseEvidence
   │
 Evidence Timeline
```

Interface:

```kotlin
interface DatabaseSource {
    suspend fun discover(): List<DatabaseDescriptor>
    suspend fun fingerprint(database: DatabaseId): DatabaseFingerprint
    suspend fun snapshot(database: DatabaseId): DatabaseSnapshot
}
```

Future iOS/local-file implementations can reuse the SQLite engine.

`DatabaseId` is the stable app-relative logical database identity. `SnapshotId`
identifies one exact captured state and is used by schema, tables, query, and
diff operations. Snapshot access ends when its owning session detaches.

## 6. DB watch

```text
poll lightweight fingerprint
       │
   unchanged -> no-op
       │
    changed
       ▼
consistent snapshot
       ▼
query/diff
       ▼
emit evidence
```

The fingerprint detects change. The snapshot remains the source of truth.

## 7. Runtime attribution later

JVMTI may later emit:
- network callsite;
- DB operation callsite;
- source file/line;
- thread;
- stack.

Correlation uses:
- session;
- timestamps;
- PID;
- thread ID where available;
- explicit evidence IDs when possible.

JVMTI must not become required for:
`network list/get/watch`, `db snapshot/query/diff/watch`.

## 8. ADRs

### ADR-001: proxy-first network
Rich HTTP evidence, mature model, platform portability, no Android Studio internals.

### ADR-002: SQLite-first database
SQLite is already the durable data representation. Observe it directly and safely.

### ADR-003: Evidence Timeline
End-to-end AI debugging needs chronology across subsystems.

### ADR-004: JVMTI as enrichment
Attribution is valuable but not foundational.

### ADR-005: source-independent schemas
CLI JSON must not expose proxy-library or SQLite-driver implementation details.

### ADR-006: session continuity across process restarts
The session is identified by package/device, not PID. If the app process dies,
the supervisor keeps the session in `PROCESS_LOST`, discovers the replacement
PID, and resumes collectors. Evidence records retain the PID observed when
captured.

### ADR-007: versioned machine contract
Every JSON/JSONL record includes `type` and `schema_version`; daemon transport
also declares `protocol_version`. Additive fields are compatible; removals or
renames require a new schema version. Stable errors use a machine-readable
`code`, operation, resource, message, retryability, and session ID.
