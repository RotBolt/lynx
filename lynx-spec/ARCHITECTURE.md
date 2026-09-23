# Architecture

## 0. Kotlin Multiplatform structure

Lynx uses Kotlin Multiplatform for public models, native host integration, and
the released CLI. The native executable is the primary developer distribution;
the JVM path remains a compatibility and regression-test backend while the
remaining host services migrate.

```text
core:model                 KMP common contracts and JSON-facing models
host:native                 KMP POSIX process, tool, proxy, and target adapters
host:network                KMP network contracts + native proxy implementations
apps:cli                    KMP command surface and platform entry points
                              ├── macosArm64Main  released `lynx`
                              ├── linuxX64Main    native runtime/smoke target
                              ├── mingwX64Main    target scaffold
                              └── jvmMain         compatibility CLI

host:session                JVM compatibility session service
host:adb                    JVM compatibility ADB client
host:daemon                 JVM compatibility daemon protocol
host:database               JVM SQLite inspector and regression backend
```

Source-set boundaries:

- `commonMain` owns platform-neutral models, commands, and contracts.
- `nativeMain`/`posixMain` owns native process execution and host integration.
- `macosArm64Main`, `linuxX64Main`, and `mingwX64Main` provide platform entry
  points and native interop.
- `jvmMain` is retained for compatibility tests and the legacy daemon path; it
  is not required to run a released native executable.

Public models never depend on JVM classes, proxy-library types, SQLite-driver
types, or AOSP protocol types. Platform code implements adapters behind the
common contracts.

Build targets:

```bash
./gradlew :apps:cli:linkReleaseExecutableMacosArm64 --no-daemon
./gradlew test --no-daemon
```

The first command produces the native `lynx` executable. The repository-wide
`test` task remains JVM-backed so existing compatibility and database tests stay
available while native tests run through their target-specific tasks.

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
    val localPath: String,
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
Android app or iOS Simulator app
   │ HTTP/HTTPS/WebSocket
   ▼
Host-side Lynx proxy
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

The production boundary has target-specific adapters:

```kotlin
interface ProxyEngine { /* HTTP/HTTPS lifecycle and capabilities */ }
interface AndroidProxyController {
    /* inspect/apply/restore Android System Proxy with a transactional lease */
}

interface HostProxyController {
    /* inspect/apply/restore the host proxy for Simulator capture */
}
```

Native macOS implementations configure Android through ADB and iOS Simulator
traffic through the macOS system proxy. JVM proxy implementations remain behind
the compatibility backend and are not public models.

The controller records the exact previous proxy configuration and restores it
on stop, detach, daemon shutdown, and failed startup. A lease is idempotent.
HTTP and HTTPS MITM are separate capabilities; certificate trust, pinning,
proxy bypass, and unsupported native/custom transports are explicit diagnostics.

Each completed or failed exchange is retained as a canonical `NetworkExchange`
and can be retrieved by request ID. `network list` may return summaries, while
`network get` returns the full stored request/response body. There is no silent
semantic truncation. Retention is session-owned and may use disk-backed blobs;
storage exhaustion is an explicit error.

## 4. Target proxy control

Owns:
- configure/remove Android system proxy through ADB;
- configure/restore the macOS proxy used by iOS Simulator capture;
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
Android System Proxy or macOS service proxy state, applies a concrete
device-reachable endpoint, and restores the exact prior state on network stop,
detach, daemon shutdown, or partial startup failure. Emulator `10.0.2.2` and
physical-device LAN addresses are endpoint choices; `0.0.0.0` is only a bind
address.

The proxy engine is replaceable behind an adapter. Public models must not expose
proxy-library request, response, flow, or TLS types. Failed exchanges are
canonical evidence, not discarded errors. Complete bodies remain retrievable
through `network get`.

## 5. Database architecture

```text
Android app files ── ADB + run-as ─────┐
                                       ├── target database adapter
iOS Simulator files ── simctl/files ──┘
             │ read-only snapshot acquisition
   ▼
host `sqlite3` inspection
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

The native CLI uses target adapters plus the host `sqlite3` executable. The JVM
`host:database` module remains the compatibility inspector and test backend.
Both paths publish the same source-independent database models and read-only
semantics. Future native SQLite bindings can replace the external executable
without changing the CLI contract.

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
