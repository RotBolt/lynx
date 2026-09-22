# Native Host Discovery Implementation Plan

> **For agentic workers:** Use `superpowers:executing-plans`; complete each ticket's focused tests and shared regression gate before committing or advancing.

**Goal:** Make the native CLI find usable tools, list supported Android/iOS targets, and explain setup without requiring standard-path exports.

**Architecture:** A single resolver supplies absolute tool paths to process adapters; provider-specific inventory normalizes into one model and joins immutable attachment state. Interactive onboarding and JSON rendering are separate concerns.

**Tech Stack:** Kotlin common/native, POSIX process adapter, ADB, xcode-select, xcrun/simctl JSON.

**Spec:** [Contract](../specs/2026-09-22-app-scoped-native-inspection.md); [master order](2026-09-22-native-inspection-hardening.md).

## Global constraints

- No edits to shell profiles, app code, or CA material during tool discovery.
- JSON stdout must contain only the requested response; diagnostics go to structured fields or stderr.
- Database byte handling, path casing, platform flags and read-only query behavior must be preserved.
- Xcode is not applicable on Linux; a missing Android provider must not suppress iOS inventory.

## Shared data boundary

Create `host/native/src/commonMain/kotlin/dev/lynx/nativehost/HostTools.kt`:

```kotlin
enum class HostTool { ADB, XCRUN, SIMCTL, SQLITE3, OPENSSL }
enum class ToolStatus { AVAILABLE, MISSING, UNUSABLE, NOT_APPLICABLE }
data class ResolvedTool(val tool: HostTool, val status: ToolStatus,
    val path: String?, val version: String?, val reason: String?)
fun interface HostToolResolver { fun resolve(tool: HostTool): ResolvedTool }
```

Create `host/native/src/commonMain/kotlin/dev/lynx/nativehost/DeviceInventory.kt`:

```kotlin
data class DeviceEntry(val id: String, val platform: String, val kind: String,
    val name: String, val state: String, val attachable: Boolean,
    val attachmentId: String?, val applicationId: String?, val captureId: String?)
data class DeviceInventory(val devices: List<DeviceEntry>, val tools: List<ResolvedTool>)
fun interface DeviceProvider { fun discover(): List<DeviceEntry> }
```

Provider failures must be represented in diagnostics, not discarded or returned
as a successful empty provider result. Only normalized device entries are joined
to attachment/capture state; foreign package names cannot be inserted by text parsing.

## M1.1-02 — Tool resolution and process diagnostics

**Files:**
- Create the `HostTools.kt` boundary above and `host/native/src/posixMain/kotlin/dev/lynx/nativehost/PosixHostToolResolver.kt`.
- Modify `host/native/src/posixMain/kotlin/dev/lynx/nativehost/PosixProcessRunner.kt`, `host/native/src/commonMain/kotlin/dev/lynx/nativehost/NativeHost.kt`, and native CLI platform factories in `apps/cli/src/{macosArm64Main,linuxX64Main}/kotlin/dev/lynx/nativecli/Platform.kt`.
- Test `host/native/src/commonTest/kotlin/dev/lynx/nativehost/HostToolResolverTest.kt` and `host/native/src/posixTest/kotlin/dev/lynx/nativehost/PosixProcessRunnerTest.kt`.
- Read the JVM resolver `host/adb/src/main/kotlin/dev/lynx/adb/AdbExecutableResolver.kt`; reuse its supported locations without importing JVM APIs into native.

**Consumes:** configured path/environment/PATH/host OS and filesystem/process probes.
**Produces:** `HostToolResolver`, preserving `NativeProcessRunner.run(List<String>)` and binary database transport semantics.

- [ ] Add failing table-driven tests for valid/invalid explicit overrides, SDK variables, PATH, macOS `~/Library/Android/sdk/platform-tools/adb`, Linux `~/Android/Sdk/platform-tools/adb`, Homebrew paths, spaces, nonexecutables and absent tools.
- [ ] Assert invalid explicit overrides return actionable errors; an unset optional source falls through to other candidates. Verify executable versions with a bounded invocation rather than file existence alone.
- [ ] Add process tests: preserve arguments containing literal `adb`, `$`, quotes, spaces and mixed casing; distinguish stdout from stderr; retain real exit status. Remove global substring replacement of `adb` inside shell snippets and quote only actual executable tokens at construction sites.
- [ ] Resolve Xcode using its selected developer directory, `xcrun --find simctl`, and a successful simctl JSON probe. Full Xcode absent, selected CLI-tools-only, missing runtime and failed license/setup are distinct actionable results.
- [ ] Implement the resolver and update all native ADB/simctl/SQLite/OpenSSL callers through the shared adapter. Avoid converting binary DB content into text while improving error capture.
- [ ] Verify this concrete subprocess contract using a controlled process test:

```text
command: sh -c 'printf ok; printf diagnostic >&2; exit 7'
expected: stdout="ok", stderr="diagnostic", exitCode=7
```

- [ ] Run `:host:native:macosArm64Test`, `:apps:cli:macosArm64Test`, JVM ADB tests, then V0 including both real DB snapshots and protocol checks. Commit/checkpoint 02.

## M1.1-03 — Unified devices, doctor and first run

**Files:**
- Create `DeviceInventory.kt` above, `AndroidDeviceProvider.kt`, and `IosSimulatorDeviceProvider.kt` under `host/native/src/commonMain/kotlin/dev/lynx/nativehost/`.
- Create `apps/cli/src/nativeMain/kotlin/dev/lynx/nativecli/HostDiagnosticsCommands.kt` and `FirstRunGuidance.kt`; modify `Main.kt` only to route commands.
- Test `host/native/src/commonTest/kotlin/dev/lynx/nativehost/DeviceInventoryTest.kt` and `apps/cli/src/nativeTest/kotlin/dev/lynx/nativecli/HostDiagnosticsCommandsTest.kt`.
- Update `README.md`, `docs/COMMANDS.md`, and `docs/agent-skill/SKILL.md` for these commands.

**Consumes:** `HostToolResolver`, `NativeProcessRunner`, existing attachment store; add capture ID joining when M1.1-04 supplies the capture registry.
**Produces:** normalized `devices [--platform android|ios] [--json]`, repeatable `doctor [--json]`, first-run display.

- [ ] Add recorded provider-input tests for Android authorized/unauthorized/offline devices and multiple simulators with booted/shutdown/unavailable runtimes. Parse simctl JSON rather than matching display-name text.
- [ ] Assert missing ADB still returns iOS devices; missing/partial Xcode still returns Android; Linux reports iOS not applicable. Unsupported physical iOS is not advertised as attachable.
- [ ] Join state without launching apps as a side effect. Show attached app/current capture plus liveness separately; stale attachment records are not proof of a live process.
- [ ] Implement first-run interactive ticks/warnings on stderr and structured tool diagnostics. Revalidate tools on subsequent calls. Print shell-profile exports only where needed, with no automatic profile edit.
- [ ] Verify the JSON contract with the newly built executable:

```bash
"$LYNX" devices --json | jq -e '.type == "devices" and (.devices | type == "array")'
"$LYNX" doctor --json | jq -e '.type == "doctor" and (.tools | type == "array")'
```

- [ ] Test non-TTY and first `--json` invocation, then a later interactive call; no prompts or emoji contaminate JSON. Use injected environment/test directories rather than replacing the real HOME or shell configuration.
- [ ] Run V2 and the complete V0 regression matrix; verify current attachment is correctly shown before/after detach. Commit/checkpoint 03.
