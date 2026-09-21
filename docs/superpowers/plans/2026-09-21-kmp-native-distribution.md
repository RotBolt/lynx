# KMP Native Distribution Implementation Plan

> **For agentic workers:** REQUIRED SUB-SKILL: Use superpowers:subagent-driven-development (recommended) or superpowers:executing-plans to implement this plan task-by-task. Steps use checkbox (`- [ ]`) syntax for tracking.

**Goal:** Convert Lynx from a JVM-only Gradle project into a Kotlin Multiplatform project with a shared protocol/model core, a verified macOS native executable first, a Linux native executable second, and an explicitly scaffolded Windows target.

**Architecture:** Keep platform-neutral evidence models, command contracts, serialization, and inspection interfaces in `commonMain`. Keep the current JVM implementation as a compatibility backend while adding native adapters for process execution, filesystem, local IPC, SQLite, and network capture. The native CLI will use the same command protocol and regression fixtures as the JVM CLI.

**Tech Stack:** Kotlin Multiplatform 2.3.0, Kotlin/Native, kotlinx.serialization, SQLite C interop/native adapter, platform process and socket APIs, Gradle native executable binaries, GitHub Actions matrix builds.

**Spec:** `lynx-spec/ARCHITECTURE.md`, `lynx-spec/IMPLEMENTATION.md`, `lynx-spec/MILESTONES.md`, `lynx-spec/IMPLEMENTATION_TICKETS.md`

## Global Constraints

- Preserve the existing JVM CLI until the native implementations pass the same regression tests.
- Public models must not expose JVM, Netty, Bouncy Castle, SQLite-JDBC, or AOSP implementation types.
- Android attachment remains ADB/run-as based; iOS remains simulator/device file and proxy based.
- Network and database operations remain read-only from Lynx.
- macOS ARM64 is the first native host target; Linux x64 follows; Windows x64 is represented in the KMP target graph with platform work explicitly marked TODO.
- Every implementation task must have a failing test or compile probe before production code.

---

### Task 1: Convert the shared model and session modules to KMP

**Files:**
- Modify: `build.gradle.kts`, `settings.gradle.kts`
- Modify: `core/model/build.gradle.kts`
- Modify: `host/session/build.gradle.kts`
- Modify: `core/model/src/main/kotlin/dev/lynx/model/*.kt`
- Modify: `host/session/src/main/kotlin/dev/lynx/session/*.kt`
- Test: existing model and session tests plus new common serialization tests

**Interfaces:**
- Produce `commonMain` model types with no `java.*` imports.
- Produce `commonMain` `EvidenceTimeline` and `SessionRegistry` contracts.
- Preserve JVM package names and existing serialized field names.

- [ ] Write a compile/test probe proving model sources compile under `commonMain`.
- [ ] Replace `java.time.Instant` with a common timestamp representation and add JVM conversion only in `jvmMain`.
- [ ] Replace JVM-only JSON assumptions with `kotlinx.serialization` models while preserving the existing JSON contract.
- [ ] Configure `macosArm64`, `linuxX64`, and `mingwX64` targets and keep `jvm()` enabled.
- [ ] Run all model/session tests and commit.

### Task 2: Introduce platform contracts for host capabilities

**Files:**
- Create: `core/contracts/build.gradle.kts`
- Create: `core/contracts/src/commonMain/kotlin/dev/lynx/contracts/HostContracts.kt`
- Create: `core/contracts/src/commonTest/kotlin/dev/lynx/contracts/HostContractsTest.kt`
- Modify: `settings.gradle.kts`
- Modify: `host/adb`, `host/daemon`, `host/database`, `host/network` build files

**Interfaces:**
- `ProcessRunner.run(command: List<String>, stdin: ByteArray?): ProcessResult`
- `HostFileSystem.read/write/copy/delete/exists`
- `LocalIpcServer` and `LocalIpcClient`
- `DatabaseEngine.openReadOnly(path)`
- `ProxyEngineFactory.create(config)`

- [ ] Add failing contract tests for argument preservation, binary stdout, and read-only database opening.
- [ ] Implement JVM adapters delegating to current code.
- [ ] Make daemon/database/network depend on contracts rather than concrete JVM helpers.
- [ ] Run the existing full JVM test suite and commit.

### Task 3: Add the macOS native process, filesystem, SQLite, and CLI adapters

**Files:**
- Create: `host/native/build.gradle.kts`
- Create: `host/native/src/nativeMain/kotlin/dev/lynx/nativehost/*.kt`
- Create: `apps/native-cli/build.gradle.kts`
- Create: `apps/native-cli/src/nativeMain/kotlin/dev/lynx/nativecli/Main.kt`
- Create: native tests for ADB discovery and SQLite read-only queries
- Modify: `settings.gradle.kts`

**Interfaces:**
- Native `ProcessRunner` executes `adb` and `sqlite3` without a JVM.
- Native `DatabaseEngine` opens copied SQLite snapshots read-only.
- Native CLI implements `--version`, `devices`, `attach`, `db list`, `db snapshot`, `db tables`, and `db query` using the shared command envelope.

- [ ] Add failing native compile/test probes for macOS ARM64.
- [ ] Implement POSIX process execution and filesystem operations.
- [ ] Implement SQLite C interop with read-only URI/open flags and row/blob/null conversion.
- [ ] Implement the native CLI command router and JSONL output.
- [ ] Build `apps:native-cli:linkReleaseExecutableMacosArm64` and run native tests.
- [ ] Run Android dummy-app DB attach/list/snapshot/tables/query end-to-end and commit.

### Task 4: Add macOS native network capture

**Files:**
- Create: `host/native/src/nativeMain/kotlin/dev/lynx/nativehost/network/*.kt`
- Create: native network contract tests and local HTTP/HTTPS fixture tests
- Modify: `apps/native-cli/src/nativeMain/kotlin/dev/lynx/nativecli/Main.kt`

**Interfaces:**
- Native proxy implements `ProxyEngine` and emits the same `NetworkExchange` schema.
- Native CA manager exposes PEM path/fingerprint and trust diagnostics.
- Native Android proxy controller uses ADB settings; iOS simulator controller uses macOS system proxy state.

- [ ] Add failing tests for HTTP request/response capture, CONNECT failure evidence, and retention across independent CLI calls.
- [ ] Implement HTTP/1 forwarding and CONNECT MITM using native-compatible sockets/TLS.
- [ ] Implement HTTP/2 and WebSocket capability reporting; keep unsupported paths explicit until fixture tests pass.
- [ ] Wire `network start`, `network list`, `network get`, `network stop`, and `network doctor`.
- [ ] Verify against Android and iOS dummy apps on macOS and commit.

### Task 5: Linux native implementation and CI artifact

**Files:**
- Modify: `host/native` native source sets
- Modify: `apps/native-cli/build.gradle.kts`
- Create: `.github/workflows/native-linux.yml`
- Modify: `README.md`, `docs/` distribution docs

- [ ] Add failing Linux x64 compile/test probe.
- [ ] Implement Linux POSIX process/filesystem/Unix-socket adapters.
- [ ] Run DB and HTTP fixture tests on Ubuntu CI.
- [ ] Publish `lynx-linux-x64` as a GitHub Actions artifact and tag-release asset.
- [ ] Commit after JVM, macOS, and Linux regressions pass.

### Task 6: Windows target graph and explicit TODO boundary

**Files:**
- Modify: KMP target declarations for `mingwX64`.
- Create: `host/native/src/mingwX64Main/kotlin/dev/lynx/nativehost/WindowsTodo.kt`
- Modify: `README.md`, `lynx-spec/ROADMAP.md`

- [ ] Prove common model/protocol code compiles for `mingwX64`.
- [ ] Add explicit runtime errors for unimplemented Windows process, IPC, proxy, and SQLite adapters.
- [ ] Document Windows native implementation as under construction rather than claiming support.
- [ ] Keep Windows target compilation in CI where the runner supports it.

### Task 7: Native packaging, CI, and regression gate

**Files:**
- Create: `.github/workflows/native.yml`
- Modify: `README.md`, `lynx-spec/MANUAL_SMOKE_TEST.md`
- Create: `docs/distribution/native.md`

- [ ] Build macOS ARM64, Linux x64, and Windows target artifacts from supported runners.
- [ ] Package native executables with help, CA, database, and network usage documentation.
- [ ] Run the existing JVM regression suite plus native contract suites.
- [ ] Record end-to-end Android and iOS evidence for macOS native CLI.
- [ ] Publish a working checkpoint commit with artifact names and verification commands.
