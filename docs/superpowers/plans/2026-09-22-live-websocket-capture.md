# M1.1-WS — Live WebSocket evidence implementation plan

**Status:** Separate fix; pending implementation and verification.
**Goal:** Read handshake and frames through network snapshot/list/get while the
WebSocket remains open, including text, binary, continuation, ping and pong.
**Parent:** [Master plan](2026-09-22-native-inspection-hardening.md).

## Root cause and scope

`PosixNativeNetworkInspector.relayPlainWebSocket` and `relayTlsWebSocket` collect
frames in a mutable list and call `store.append` only after the relay loop exits.
Consequently an open connection's observed traffic is not persisted for another
CLI invocation to read. This is a publication/lifecycle bug, not a requirement
to close the application's WebSocket.

Both WS and WSS paths must publish incrementally. Preserve forwarded bytes,
masking, fragmentation and control frames; do not synthesize ping/pong frames to
claim capture coverage. This does not introduce RFC 8441 support.

## Dependencies and placement

Execute after M1.1-08 (verified platform ownership and session store) and before
M1.1-09 (public scoped command cutover). Develop through repository/component
reads and existing native list/get; ticket 09 exposes the same live state through
scoped snapshot/list. Do not defer live recording to closure in either ticket.
Keep a separate fix commit/checkpoint, not part of proxy recovery or CLI parsing.

## Files and data contract

- Modify `host/network/src/posixMain/kotlin/dev/lynx/nativehost/PosixNativeNetworkInspector.kt`, both relay paths.
- Extend ticket 04's `CaptureContracts.kt` and `PosixCaptureRepository.kt`.
- Extend public model `core/model/src/main/kotlin/dev/lynx/model/NetworkInspection.kt` without breaking JVM constructors; use defaulted additive fields/adapters as appropriate.
- Add `NativeWebSocketLiveCaptureTest.kt` in host/network posixTest `dev/lynx/nativehost`; extend storage and native CLI tests.

Persist append-only events: handshake, frame, terminal state. Each carries capture
ID, connection/request ID, immutable verified origin and monotonic event sequence.
A frame carries direction, opcode/name, payload and encoding, observation time,
FIN flag and per-connection frame sequence. Preserve binary bytes via base64;
empty ping/pong payload is valid. Retain continuation frames and control frames
interleaved with fragments. Do not conflate protocol ping/pong with text messages
whose body happens to say "ping".

Materialize one logical exchange per request ID at a read watermark. Open exchange:
handshake status 101, `state: open`, accumulated frames, null completion time.
Terminal exchange: `state: closed|failed|interrupted`, completion time and reason.
Track half-close/close-handshake state without prematurely dropping the opposite
direction. Frames become queryable after each complete frame is decoded/persisted,
without waiting for the next frame or connection close. Reads are finite point-in-
time results, not a new streaming CLI command.

Do not rewrite the whole growing exchange on every frame (quadratic writes),
create duplicate exchange rows or silently discard retained frames on restart.
Expose storage failures/incomplete capture; never pretend observed bytes were
durably recorded when append failed.

## Test-first steps

- [ ] Add a failing integration test that keeps a WS/WSS connection open, sends one data frame, invokes a separate reader, and expects handshake plus frame before close.
- [ ] Add tests for each direction/opcode (0, 1, 2, 8, 9, 10), empty control payloads, binary encoding, fragmentation, interleaved ping, concurrent connections and duplicate-free repeated reads.
- [ ] Run `./gradlew :host:network:macosArm64Test --no-daemon`; record the close-only failure.
- [ ] Implement append-only handshake/frame/terminal events and watermark reconstruction; retain origin/capture context from admission, never current attachment.
- [ ] Publish handshake immediately and each complete observed frame independently. Preserve forwarding wire bytes and existing TLS/protocol behavior.
- [ ] Ensure normal close, transport error, capture stop and worker death finalize or recover state honestly without losing already-published frames. A process killed mid-frame reports incomplete tail instead of fabricating a frame.
- [ ] Assert readers do not block on connection close, return one exchange, contain increasing frame prefixes and cannot see later-than-watermark events. Include large binary frames and store write failure.
- [ ] Run focused plus native/JVM regression tests; existing exchange field compatibility must remain intact.

## Live verification and checkpoint

- [ ] On unchanged Android and iOS sample apps, start WSS, send/receive actual remote traffic, run native list/get while still connected, and prove frames exist before clicking Close. Repeat through scoped snapshot/list in ticket 09.
- [ ] Verify real opcode-9/10 traffic while open. If existing app/server generates none, explicitly mark the live control-frame row not-run; use a controlled protocol integration test for parser/publication proof, but never describe it as app-observed ping/pong. Do not modify the immutable sample app or inject control traffic into it.
- [ ] Confirm unrelated processes remain excluded; open connections from A never appear in B; snapshot counters include active connections honestly.
- [ ] Repeat both platforms' DB and real HTTP/1/HTTP/2 regressions plus WebSocket close-path checks.
- [ ] Commit `fix(network): publish live websocket frames`, verify committed tree, tag `checkpoint/m1-1/WS-live-frames` only for proven scope. Report missing live control-frame evidence rather than claiming full verification.
