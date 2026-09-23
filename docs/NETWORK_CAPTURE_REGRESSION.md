# Real network-capture regression 🧪

This regression uses the installed sample app and its explicit buttons. It does
not use fixtures, a local server, app-level proxy code, or fabricated traffic.
The HTTP/1.1 action uses a public HTTPS endpoint that negotiates HTTP/1.1, so
the test exercises TLS interception as well as HTTP/1.1 parsing.
The script starts Lynx capture, automatically invokes each real sample-app UI
action (Android UIAutomator button taps; iOS Simulator app selectors via LLDB),
then requires a new `network list` exchange with the expected transport,
status, and attached app metadata. The requests go directly from the sample app
to public services; neither fixtures nor a local test server are involved.

For the v2 agent contract, use `network snapshot` during capture and
`network list --session <id>` for finite app/device-scoped results. Plain
`network list` intentionally returns a session catalog, not every retained
exchange.

## Prerequisites

- A built native Lynx executable (`./gradlew :apps:cli:linkReleaseExecutableMacosArm64`).
- The sample app installed on the selected Android emulator or iOS Simulator.
- The selected target trusts the current Lynx CA. Android debug builds must
  trust user CAs; install the CA through Android Settings first. The script adds
  the current Lynx root CA to an iOS Simulator keychain with `simctl`.
- `adb`, `xcrun`, `lldb`, `python3`, and `jq` on `PATH` as appropriate.
- Working internet access from the emulator/simulator.

## Android

```bash
export ANDROID_HOME="$HOME/Library/Android/sdk"
export PATH="$ANDROID_HOME/platform-tools:$PATH"
LYNX=./apps/cli/build/bin/macosArm64/releaseExecutable/lynx.kexe \
  .github/scripts/network-capture-smoke.sh \
  android emulator-5554 dev.lynx.dummyapp
```

The script launches the Android sample app and taps each named button through
the device UI hierarchy. WebSocket Start sends a real ping; the script then
presses WebSocket Close and verifies the echoed frame in `network list`.

## iOS Simulator

```bash
xcrun simctl list devices booted
LYNX=./apps/cli/build/bin/macosArm64/releaseExecutable/lynx.kexe \
  .github/scripts/network-capture-smoke.sh \
  ios <simulator-udid> dev.lynx.dummyapp
```

The script launches the iOS sample app and invokes the same Objective-C UI
actions through LLDB. The app itself performs the real `URLSession` request;
LLDB is only the local test driver, not a network-capture mechanism.

## Assertions

Each action must append a new request ID; old retained events cannot make the
check pass. The script checks:

- HTTPS/HTTP/1.1: `https://` URL, successful HTTP response, and `HTTP/1.1`
  protocol.
- HTTP/2: successful or `304 Not Modified` response and `HTTP/2` protocol.
- When HTTP/2 returns `200`, the stored response body must decode to JSON with
  the `userId` field. This catches compressed gzip/Brotli payload regressions.
- WebSocket: `101 Switching Protocols`, `WebSocket` protocol, and the echoed
  `lynx-sample-ping` frame.
- Every event has a fresh request ID, the selected device/simulator and app ID,
  and a non-null app process ID.

WebSocket evidence is finalized when the sample app closes the connection, so
the script presses **WebSocket Close** before checking `network list`. Cleanup
stops capture and asks Lynx to restore the prior proxy settings.

## CI scope

The regular Android and iOS CI jobs remain build/launch checks and do not claim
to validate live Lynx traffic. Run this automated end-to-end regression on a
developer Mac with a booted emulator or simulator, trusted Lynx CA, and public
internet access. It is intentionally device-backed because it proves that
actual app-origin traffic traverses Lynx and appears in the CLI's live evidence
store.
