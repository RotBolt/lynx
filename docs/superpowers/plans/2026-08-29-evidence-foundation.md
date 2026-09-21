# Evidence Foundation Implementation Plan

> **For agentic workers:** REQUIRED SUB-SKILL: Use superpowers:executing-plans to implement this plan task-by-task.

**Goal:** Establish the source-independent evidence foundation required by the revised Lynx architecture.

**Architecture:** Network proxy capture and SQLite observation publish canonical evidence into a shared bounded timeline. Public models contain no proxy-library, SQLite-driver, Android Studio, or JVMTI types; ADB and runtime attribution remain replaceable adapters.

**Tech Stack:** Kotlin/JVM 21, Kotlin test, Gradle multi-module build.

**Spec:** `lynx-spec/ARCHITECTURE.md`, `lynx-spec/IMPLEMENTATION.md`, `lynx-spec/FEATURE_REQUIREMENTS.md`

## Global Constraints

- Debuggable Android apps only.
- Network foundation is proxy capture.
- Database foundation is SQLite snapshot/observer.
- Evidence Timeline is a first-class shared abstraction.
- CLI JSON/JSONL is the primary public API.
- Network/DB public schemas are source-independent.
- JVMTI is optional later enrichment and is not required for Network/DB.
- Database operations remain read-only.
- ADB and the injected runtime are the only attachment mechanisms.

### Task 1: Canonical evidence models

**Files:**
- Create: `core/model/src/main/kotlin/dev/lynx/model/Evidence.kt`
- Test: `core/model/src/test/kotlin/dev/lynx/model/EvidenceModelTest.kt`

Add inline identifiers, evidence source/type enums, shared metadata, network request/response/failure/timing/capture models, database fingerprint/snapshot models, and runtime attribution. IDs must be opaque strings and metadata must carry session, device, package, optional PID, source, and timestamp.

Verification: `gradle :core:model:test --no-daemon`.

### Task 2: Timeline contract and bounded implementation

**Files:**
- Create: `core/model/src/main/kotlin/dev/lynx/model/EvidenceTimeline.kt`
- Test: `core/model/src/test/kotlin/dev/lynx/model/EvidenceTimelineTest.kt`

Define `Evidence`, `EvidenceFilter`, `EvidenceSummary`, and `EvidenceTimeline`. Implement a thread-safe in-memory timeline with maximum item retention, deterministic observed-time ordering, and filtering by session, source, and time range.

Verification: `gradle :core:model:test --no-daemon`.

### Task 3: Network and database source contracts

**Files:**
- Create: `host/daemon/src/main/kotlin/dev/lynx/daemon/SourceContracts.kt`
- Test: `host/daemon/src/test/kotlin/dev/lynx/daemon/SourceContractsTest.kt`

Define source-independent lifecycle and capability interfaces matching the revised architecture: `NetworkCaptureSource.start/stop/events/capabilities` and `DatabaseSource.discover/fingerprint/snapshot`. Contracts must be suspendable and expose explicit limitations/capabilities.

Verification: `gradle :host:daemon:test --no-daemon`.

### Task 4: Integrate the timeline into the host service boundary

**Files:**
- Modify: `host/daemon/src/main/kotlin/dev/lynx/daemon/DaemonService.kt`
- Modify: `host/daemon/src/test/kotlin/dev/lynx/daemon/DaemonServiceTest.kt`

Give the daemon a timeline dependency and expose a read-only evidence query boundary without coupling it to ADB or future collectors. Preserve existing attach/status/detach behavior.

Verification: `gradle test :apps:cli:installJvmDist --no-daemon`.

### Task 5: Documentation and smoke verification

**Files:**
- Modify: `MANUAL_SMOKE_TEST.md`
- Modify: `lynx-spec/README.md`

Document the new foundation and the first manual checks for an empty timeline and source capability reporting. Verify all tests and the packaged CLI.

Verification: `gradle test :apps:cli:installJvmDist --no-daemon` and `lynx --version`.

## Anti-pattern guards

- Do not add Android Studio/App Inspection dependencies.
- Do not expose proxy-library or SQLite-driver classes from `core:model`.
- Do not add JVMTI to the foundational path.
- Do not model database change capture as CDC.
- Do not add database writes.
- Do not claim an empty network capture proves no request occurred.
