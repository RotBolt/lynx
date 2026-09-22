# App-scoped Network Capture Implementation Plan

> **For agentic workers:** Use `superpowers:executing-plans`, one ticket at a time. All steps are pending. Read the master plan and contract before executing.

**Goal:** Return only verified attached-app traffic within explicitly addressed capture sessions.

**Architecture:** Admit connections by OS ownership before TLS interception. Carry immutable connection context through existing HTTP/1, HTTP/2 and WebSocket handling. Separate retained capture data from attachment and proxy leases.

**Tech Stack:** Kotlin/Native, POSIX, macOS libproc, Android NDK/ADB, existing OpenSSL/nghttp2 adapters.

**Spec:** [Contract](../specs/2026-09-22-app-scoped-native-inspection.md). [Master sequence and gates](2026-09-22-native-inspection-hardening.md).

## Global constraints

- macOS ARM64 first; independently verify Android and iOS Simulator.
- No VPN, root, application proxy configuration, sample-source changes or injected app runtime.
- No URL/header heuristics as application identity. Unknown ownership never becomes target ownership.
- Forward unrelated/unknown traffic without MITM or payload storage; expose coverage counters.
- Preserve installed CA, existing database behavior and complete network bodies.
- No push, release or CI invocation. Every ticket requires the master's regression/checkpoint procedure.
- Paths below are repository-relative; new filenames are proposed additions, not existing APIs.

## Shared interfaces (ticket 04)

Create `core/model/src/main/kotlin/dev/lynx/model/NetworkCaptureSession.kt` and
`NetworkOrigin.kt`; create `host/network/src/commonMain/kotlin/dev/lynx/nativehost/CaptureContracts.kt`.
Use serialization names from the contract, not Kotlin property names as an accidental public schema.

```kotlin
data class CaptureTarget(val platform: String, val deviceId: String,
    val applicationId: String, val androidUserId: Int? = null)
data class ProcessIdentity(val pid: Int, val startIdentity: String)
data class VerifiedOrigin(val target: CaptureTarget, val process: ProcessIdentity,
    val uid: Int?, val method: String)
enum class CaptureState { STARTING, RUNNING, STOPPING, STOPPED, INTERRUPTED, FAILED }
data class CaptureSession(val id: String, val attachmentId: String,
    val target: CaptureTarget, val state: CaptureState, val startedAtEpochMillis: Long)
data class ConnectionContext(val captureId: String, val connectionId: String,
    val origin: VerifiedOrigin, val acceptedAtEpochMillis: Long)
data class SocketTuple(val localAddress: String, val localPort: Int,
    val peerAddress: String, val peerPort: Int)
sealed interface OwnershipDecision {
    data class Target(val origin: VerifiedOrigin) : OwnershipDecision
    data object Other : OwnershipDecision
    data class Unknown(val reason: String) : OwnershipDecision
}
interface ConnectionOwnerResolver {
    fun resolve(target: CaptureTarget, socket: SocketTuple): OwnershipDecision
}
data class VerifiedExchange(val context: ConnectionContext, val exchange: NetworkExchange)
data class CaptureRead(val session: CaptureSession, val throughSequence: Long,
    val exchanges: List<VerifiedExchange>, val inFlightCount: Int)
interface CaptureRepository {
    fun create(session: CaptureSession)
    fun session(id: String): CaptureSession?
    fun sessions(): List<CaptureSession>
    fun transition(id: String, expected: CaptureState, next: CaptureState)
    fun append(exchange: VerifiedExchange): Long
    fun read(id: String, throughSequence: Long? = null): CaptureRead
}
```

`NetworkExchange` is the existing model: retain its request/response/body/frame fields.
Repository validation must reject origin/session target mismatch. Only the trusted
admission path can construct production verified context; arbitrary HTTP headers are not proof.

## M1.1-04 — Capture storage and immutable context

**Depends on:** 03. **Produces:** interfaces above and a versioned repository.

**Files:** New `host/network/src/posixMain/kotlin/dev/lynx/nativehost/PosixCaptureRepository.kt`;
modify `PosixNativeNetworkStateStore.kt`, `PosixNativeNetworkInspector.kt` and
`NativeNetworkEvidenceMeta.kt` in their existing source sets. Tests:
`host/network/src/posixTest/kotlin/dev/lynx/nativehost/PosixCaptureRepositoryTest.kt`
and existing `NativeNetworkEvidenceMetaTest.kt`.

- [ ] Write failing tests for A/B sessions with identical URLs, late completion after attachment switch, mismatched device/app origins, and monotonic per-session sequences.
- [ ] Add failing tests for a multi-MiB body, concurrent writers, a corrupt complete record and a crash-truncated trailing record. Never parse fixed 64-KiB chunks as whole JSON records.
- [ ] Run `./gradlew :host:network:macosArm64Test --no-daemon`; record the expected failing assertions.
- [ ] Implement UUID identities, process-safe serialized appends, atomic session metadata and committed-record watermarks. Diagnose corruption explicitly. Keep legacy unscoped logs unchanged and outside verified reads.
- [ ] Capture context once at admission; pass it through completion callbacks. Remove mutable attachment lookup from the new evidence path. Keep existing public commands until ticket 09.
- [ ] Assert `read(A).exchanges.all { it.context.captureId == A }`, nonempty positive results, exact large-body bytes and no B records; snapshot while appending must stop at its captured watermark.
- [ ] Pass V3 and V0, then commit/checkpoint 04 using the master procedure.

## M1.1-05 — Transactional lifecycle and recovery

**Prerequisite:** Separate [M1.1-P0](2026-09-22-macos-proxy-recovery.md) must already
be verified. Reuse its lease, readiness and recovery implementation; extend it
for capture-session lifecycle rather than introducing a competing mechanism.

**Depends on:** 04. **Consumes:** CaptureRepository. **Produces:** one-active-capture coordinator and recoverable resource leases.

**Files:** New `NativeCaptureCoordinator.kt`, `NativeCaptureLease.kt`,
`NativeCaptureWorkerSupervisor.kt` in `host/network/src/posixMain/kotlin/dev/lynx/nativehost/`;
new `NativeProcessIdentityResolver.kt` in `host/native/src/commonMain/kotlin/dev/lynx/nativehost/`
(match existing package before creation). Modify existing native session store,
platform proxy controllers and inspector. Tests: `NativeCaptureCoordinatorTest.kt`
and `NativeCaptureRecoveryTest.kt` in host/network posixTest package.

- [ ] Write failing tests for repeated start, competing start, failed bind, failed proxy application, stale ready file, dead/reused worker PID and partial restoration.
- [ ] Run host/network native tests and record failures.
- [ ] Implement a single-active lock and persist previous proxy values before changing them. Ready acknowledgement must include capture ID, PID/start identity and bound endpoint; file existence alone is insufficient.
- [ ] Repeated start for the same running target returns its existing session ID. Different target returns `CAPTURE_ACTIVE`. No repeated start overwrites the original restoration lease.
- [ ] Resolve actual macOS network services/settings, not hardcoded Wi-Fi. Restore only settings still owned by this capture; report user-change conflicts and retain unresolved leases.
- [ ] On stop restore routing first, drain for a documented 30-second grace, mark incomplete exchanges, then stop owned workers/relay/reverse mappings. Do not delete other ADB mappings.
- [ ] Resolve app PID/start identity without launching the app. App restart preserves capture ID but requires fresh ownership for new connections. Worker crash marks capture interrupted; recovery reconciles persisted leases.
- [ ] Test stop/detach, app restart, device disconnect/reconnect and interrupted startup with exact before/after proxy values. Pass V4 and V0; commit/checkpoint 05.

## M1.1-06 — Certificate issuance and actionable TLS failures

**Depends on:** 05. **Consumes:** process diagnostics from 02. **Produces:** race-safe leaf issuance and stage-specific failures.

**Files:** Existing `PosixNativeCertificateAuthority.kt`, `MacosNativeTls.kt`,
`LinuxNativeTls.kt`, `PosixNativeNetworkInspector.kt`; tests
`PosixNativeCertificateAuthorityTest.kt` and `NativeTlsFailureTest.kt` in host/network posixTest.

- [ ] Reproduce simultaneous same-host/different-host generation, interrupted output, invalid cache and unwritable paths. Shared certificate/serial files are a hypothesis, not a proven cause.
- [ ] Record failing tests and actual OpenSSL exit status/stderr without logging keys.
- [ ] Fix the reproduced cause using process-safe CA/leaf locks, unique temporary files, atomic validated publication and serialized or collision-resistant serial allocation. Cache identity includes CA identity and destination SAN.
- [ ] Preserve the existing CA/key pair and fingerprint. Do not add proxy IP as a replacement for destination SAN; verify chain, hostname and key match.
- [ ] Map errors to `CA_LEAF_SIGN_FAILED`, `TLS_CLIENT_REJECTED_CERTIFICATE` or `TLS_UPSTREAM_FAILED` with stage context; no generic `Failed requirement` and no trust bypass.
- [ ] Run at least 32 concurrent issuance requests and actual H1/H2/WSS; verify unchanged CA and retained target TLS failures. Pass V5/V0 and checkpoint 06. Adjust commit wording to the demonstrated fix.

## M1.1-07 — Admission gate and iOS Simulator ownership

**Depends on:** 06 and successful V1. **Consumes:** ConnectionOwnerResolver. **Produces:** pre-TLS admission and macOS resolver.

**Files:** New `ConnectionAdmission.kt` (host/network commonMain),
`MacosConnectionOwnerResolver.kt` (macosArm64Main), `NativeProxyPassThrough.kt`
(posixMain), matching tests `ConnectionAdmissionTest.kt` and
`MacosConnectionOwnerResolverTest.kt`. If needed add
`host/network/src/nativeInterop/cinterop/lynx-proc-macos.def`, `lynx_proc.h`
and build configuration for libproc.

- [ ] Write failing tests for exact tuple/PID-start match, wrong simulator/app, IPv4/IPv6, PID reuse, socket closure and denied process inspection.
- [ ] Implement `proc_pidinfo(PROC_PIDLISTFDS)` and `proc_pidfdinfo(PROC_PIDFDSOCKETINFO)` lookup, linked to simulator/container/application identity. A shared background URLSession process is unknown unless independently attributable.
- [ ] Retain accepted peer tuple. Resolve ownership before generating a leaf or reading HTTP content. Start with a bounded one-second lookup deadline; measure and document timing rather than polling indefinitely.
- [ ] Gate dispatch: `Target -> intercept(context)`; `Other/Unknown -> tunnelWithoutRecording()`. Carry context through all H2 streams, WebSocket frames and errors.
- [ ] Implement ordinary HTTP forwarding and raw CONNECT tunneling for other/unknown connections, including half-close/backpressure. Only diagnostic counters may be retained for unknown traffic.
- [ ] Test identical URL/headers for target and host curl. Assert foreign connections never call the certificate issuer or store request/response payloads.
- [ ] Run unchanged real iOS sample H1/H2/WSS with independent OS ownership evidence; simultaneous host curl to the same origin must work without Lynx CA and remain absent. Mark physical iOS/delegated background traffic unsupported explicitly.
- [ ] Pass V6 iOS and V0 on both platforms; checkpoint 07.

## M1.1-08 — Android device-local relay and ownership

**Depends on:** 07 and successful Android V1. **Produces:** app-scoped Android transport without app modifications.

**Files:** New `tools/android-relay/CMakeLists.txt`, `src/main.c`,
`src/socket_owner.c`, `src/relay_protocol.h`, `tests/socket_owner_test.c`;
`scripts/build-android-relay.sh`; host/network posixMain
`AndroidRelayController.kt`, `AndroidRelayProtocol.kt`; corresponding posixTest files.
Wire platform controller/coordinator and packaging inputs.

- [ ] Test /proc TCP/TCP6 client tuple parsing, UID/inode mapping, PID ownership, Android user/profile, shared UID ambiguity, PID reuse and access denial. UID-only attribution is insufficient when ambiguous.
- [ ] Implement a shell-owned relay in a capture-owned `/data/local/tmp` directory. Resolve socket ownership before ADB/NAT loses the peer tuple; validate app process via run-as process FD evidence where available.
- [ ] Build API 26 arm64-v8a and x86_64 artifacts. Record/pin the actual NDK revision used; no device OpenSSL or APK installation.
- [ ] Send a 4-byte big-endian metadata length, UTF-8 JSON preface then original proxy bytes through a dedicated ADB reverse channel. Preface includes protocol version, capture/connection UUID, per-capture token and ownership decision. Limit preface to 64 KiB, not bodies.
- [ ] Reject wrong token, wrong capture/device channel, malformed length and unsupported version. HTTP headers cannot substitute for the preface. Unknown ownership routes without capture; missing resolver support is explicit startup failure.
- [ ] Wait for relay and host readiness before setting device-loopback global proxy. Persist original settings; own and clean only this capture's helper and reverse mapping.
- [ ] Run real unchanged Android sample H1/H2/WSS and unrelated UID traffic to identical destinations. Add second-device negative control where available; otherwise record that live row as pending.
- [ ] Test helper death, unsupported ABI, ADB reconnect and stop cleanup. Pass V6 Android and V0 both, including iOS isolation regression; checkpoint 08.

## M1.1-09 — Public session command cutover

**Depends on:** 08 and separate [M1.1-WS live recording fix](2026-09-22-live-websocket-capture.md). **Consumes:** CaptureRepository and coordinator. **Produces:** lynx.v2 native network commands.

**Files:** New `apps/cli/src/nativeMain/kotlin/dev/lynx/nativecli/NetworkInspectionCommands.kt`,
`NativeCommandErrors.kt`; modify native `Main.kt`; new host/network commonMain
`ScopedNetworkInspection.kt`; tests native `NetworkInspectionCommandsTest.kt` and
common `ScopedNetworkInspectionTest.kt`. Update README, command documentation,
bundled skill and `.github/scripts/network-capture-smoke.sh` together.

- [ ] Write failing command tests: list catalog; both `--session=id` and `--session id`; active snapshot; no-active/unknown-ID errors; stopped-session reads; verified get; independent shell calls.
- [ ] Expose `network list` as catalog and `network list --session` as captured exchanges. `network snapshot` reads the active capture's committed watermark without stopping or creating another session.
- [ ] Reject capture filters on the catalog with `SESSION_REQUIRED`. Exclude legacy/unknown records. Retain complete exchange fields and add capture ID, sequence and verified attribution; actual origin PID replaces assumed attached PID.
- [ ] Return `schema_version: lynx.v2`; `NO_ACTIVE_CAPTURE` and `CAPTURE_SESSION_NOT_FOUND` are explicit. Document this intentional native output migration.
- [ ] Run native CLI tests, then this live sequence after attachment (LYNX is the absolute candidate executable):

```bash
START_JSON=$("$LYNX" network start --json)
CAPTURE_ID=$(printf '%s\n' "$START_JSON" | jq -er '.session_id')
# Click the unchanged sample's HTTP1, HTTP2 and WebSocket controls now.
"$LYNX" network list --json | jq -e '.type=="network_sessions" and (.sessions|type=="array")'
"$LYNX" network snapshot --json | jq -e --arg sid "$CAPTURE_ID" '.type=="network_snapshot" and .session_id==$sid and .schema_version=="lynx.v2"'
"$LYNX" network list --session="$CAPTURE_ID" --json | jq -e --arg sid "$CAPTURE_ID" '.type=="network_list" and .session_id==$sid and (.exchanges|length>0) and all(.exchanges[]; .capture_session_id==$sid and .attribution.status=="verified")'
```

- [ ] Compare full bodies/frames through snapshot, scoped list and get. Assert nonempty positives before `all`; empty results never prove successful capture. Independent OS ownership evidence is required, not just emitted metadata.
- [ ] Run A/B captures against identical URLs, app restart and platform switches. Check stopped history, in-flight WS reporting, explicit limits and unknown counters without unrelated payloads.
- [ ] Pass V7, V6 both and V0; commit/checkpoint 09. Continue to distribution ticket 10 only after the gate passes.
