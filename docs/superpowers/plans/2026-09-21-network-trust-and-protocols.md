# Network Trust and Protocol Support Implementation Plan

> **For agentic workers:** REQUIRED SUB-SKILL: Use superpowers:subagent-driven-development (recommended) or superpowers:executing-plans to implement this plan task-by-task.

**Goal:** Add first-run CA onboarding and platform trust diagnostics, then extend the proxy to HTTP/1.1, HTTP/2, and WebSocket capture.

**Architecture:** Keep a Lynx-owned `NetworkCaptureSource` boundary. Add a host CA lifecycle service and platform-specific trust adapters beside the existing Android proxy controller. Replace protocol parsing behind that boundary with an engine adapter whose events are translated into the existing evidence models.

**Tech Stack:** Kotlin/JVM 21, ADB, `xcrun simctl`, Bouncy Castle for CA material, JSONL daemon protocol, automated Kotlin tests.

**Spec:** `docs/superpowers/specs/2026-09-21-network-trust-and-protocols-design.md`

## Global Constraints

- Debuggable applications are the supported Android target.
- macOS is the first host platform.
- CLI and database operations remain read-only.
- QUIC/HTTP3 is explicitly unsupported in this phase.
- CA installation is explicit and reversible; no silent trust changes.

### Task 1: CA lifecycle and onboarding

**Status:** Implemented

**Files:**
- Create: `host/network/src/main/kotlin/dev/lynx/network/CertificateAuthorityManager.kt`
- Modify: `host/network/src/main/kotlin/dev/lynx/network/HttpProxyCapture.kt`
- Modify: `apps/cli/src/main/kotlin/dev/lynx/cli/CommandLine.kt`
- Modify: `apps/cli/src/main/kotlin/dev/lynx/cli/Main.kt`
- Test: `host/network/src/test/kotlin/dev/lynx/network/CertificateAuthorityManagerTest.kt`

- [ ] Add failing tests for stable CA material, fingerprint output, and idempotent show/remove behavior.
- [ ] Add `network ca show|install|remove` command parsing and structured responses.
- [ ] Persist the CA outside the proxy instance and use it for leaf certificates.
- [ ] Add first-start onboarding text and JSON fields for CA state and instructions.
- [ ] Run focused network and CLI tests.

### Task 2: Android trust adapter

**Status:** Implemented for staging and explicit installer launch

**Files:**
- Create: `host/network/src/main/kotlin/dev/lynx/network/AndroidCertificateInstaller.kt`
- Modify: `apps/cli/src/main/kotlin/dev/lynx/cli/Main.kt`
- Test: `host/network/src/test/kotlin/dev/lynx/network/AndroidCertificateInstallerTest.kt`

- [ ] Test certificate staging path, emulator/device distinction, and explicit confirmation state.
- [ ] Push DER/PEM material to the target and launch the platform certificate installer when available.
- [ ] Detect debug Network Security Config as a documented app-side requirement, not an implicit Lynx mutation.
- [ ] Report installed, awaiting-user-confirmation, unsupported, and failed states.

### Task 3: iOS trust adapters

**Status:** Simulator installation implemented; physical profile delivery pending

**Files:**
- Create: `host/network/src/main/kotlin/dev/lynx/network/AppleCertificateInstaller.kt`
- Modify: `apps/cli/src/main/kotlin/dev/lynx/cli/CommandLine.kt`
- Modify: `apps/cli/src/main/kotlin/dev/lynx/cli/Main.kt`
- Test: `host/network/src/test/kotlin/dev/lynx/network/AppleCertificateInstallerTest.kt`

- [ ] Test simulator command construction with a selected UDID.
- [ ] Implement `simctl keychain add-root-cert` for simulators.
- [ ] Generate a physical-device profile/install URL and report required user trust confirmation.
- [ ] Add cleanup and clear diagnostics for unsupported host tooling.

### Task 4: Protocol engine boundary

**Files:**
- Create: `host/network/src/main/kotlin/dev/lynx/network/ProxyEngine.kt`
- Modify: `host/daemon/src/main/kotlin/dev/lynx/daemon/SourceContracts.kt`
- Modify: `host/network/build.gradle.kts`
- Test: `host/network/src/test/kotlin/dev/lynx/network/ProxyEngineContractTest.kt`

- [ ] Define protocol capabilities for HTTP/1.1, HTTP/2, HTTPS MITM, and WebSocket.
- [ ] Select and pin an engine that supports all required protocols on JVM 21.
- [ ] Keep engine-specific types private to the network module.
- [ ] Preserve complete failures and body/frame events through the existing evidence accumulator.

### Task 5: HTTP/2 and WebSocket evidence

**Files:**
- Modify: `host/network/src/main/kotlin/dev/lynx/network/HttpProxyCapture.kt`
- Modify: `core/model/src/main/kotlin/dev/lynx/model/Evidence.kt`
- Modify: `host/daemon/src/main/kotlin/dev/lynx/daemon/DaemonProtocol.kt`
- Test: `host/network/src/test/kotlin/dev/lynx/network/Http2WebSocketCaptureTest.kt`

- [ ] Add failing integration tests for HTTP/2 request/response and WebSocket handshake/frame capture.
- [ ] Translate engine events into versioned protocol fields without exposing engine classes.
- [ ] Preserve binary WebSocket frames using the existing structured binary encoding policy.
- [ ] Report HTTP/3/QUIC as unsupported.

### Task 6: End-to-end manual gate

**Files:**
- Modify: `lynx-spec/MANUAL_SMOKE_TEST.md`
- Modify: `lynx-spec/MILESTONES.md`
- Modify: `lynx-spec/IMPLEMENTATION_TICKETS.md`

- [ ] Document Android emulator, Android device, iOS Simulator, and iOS device flows.
- [ ] Verify CA onboarding, HTTP/1.1, HTTP/2, WebSocket, trust failures, and cleanup.
- [ ] Run `./gradlew test :apps:cli:installDist --no-daemon`.

### Task 7: Android proxy compatibility (terminal-only)

**Files:**
- Modify: `host/network/src/main/kotlin/dev/lynx/network/AndroidProxyController.kt`
- Test: `host/network/src/test/kotlin/dev/lynx/network/AndroidProxyControllerTest.kt`

- [x] Add a failing controller test for complete proxy-key application/restoration.
- [x] Apply the complete Android proxy setting set without application changes.
- [ ] Report direct-socket bypasses clearly in `network doctor`.
- [ ] Verify app Ktor traffic is captured without modifying the app source.
