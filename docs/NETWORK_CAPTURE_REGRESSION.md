# Real network-capture regression 🧪

This regression uses the installed sample app and its explicit buttons. It does
not use fixtures, a local server, app-level proxy code, or fabricated traffic.
The HTTP/1.1 action uses a public HTTPS endpoint that negotiates HTTP/1.1, so
the test exercises TLS interception as well as HTTP/1.1 parsing.
The script starts Lynx capture, waits while the tester taps each button, then
requires a new `network list` exchange with the expected transport and status.

## Prerequisites

- A built native Lynx executable (`./gradlew :apps:cli:linkReleaseExecutableMacosArm64`).
- The sample app installed on the selected Android emulator or iOS Simulator.
- The selected target trusts the current Lynx CA. Android debug builds must
  trust user CAs; install the CA through Android Settings first. The script adds
  the current Lynx root CA to an iOS Simulator keychain with `simctl`.
- `adb`, `xcrun`, and `jq` on `PATH` as appropriate.
- Working internet access from the emulator/simulator.

## Android

```bash
export ANDROID_HOME="$HOME/Library/Android/sdk"
export PATH="$ANDROID_HOME/platform-tools:$PATH"
LYNX=./apps/cli/build/bin/macosArm64/releaseExecutable/lynx.kexe \
  .github/scripts/network-capture-smoke.sh \
  android emulator-5554 dev.lynx.dummyapp
```

At each prompt, tap the named button in the Android sample app. For WebSocket,
wait for the echoed ping and tap **WebSocket Close** before continuing.

## iOS Simulator

```bash
xcrun simctl list devices booted
LYNX=./apps/cli/build/bin/macosArm64/releaseExecutable/lynx.kexe \
  .github/scripts/network-capture-smoke.sh \
  ios <simulator-udid> dev.lynx.dummyapp
```

At each prompt, tap the named button in Simulator. For WebSocket, wait for the
echoed ping and tap **WebSocket Close** before continuing.

## Assertions

Each action must append a new request ID after the prompt; old retained events
cannot make the check pass. The script checks:

- HTTPS/HTTP/1.1: `https://` URL, successful HTTP response, and `HTTP/1.1`
  protocol.
- HTTP/2: successful or `304 Not Modified` response and `HTTP/2` protocol.
- WebSocket: `101 Switching Protocols`, `WebSocket` protocol, and the echoed
  `lynx-sample-ping` frame.

WebSocket evidence is finalized when the sample app closes the connection, so
the script waits for the **Close** button before checking `network list`.
Cleanup stops capture and asks Lynx to restore the prior proxy settings.

## CI scope

The regular Android and iOS CI jobs are app build/launch/UI smoke checks. They
do not claim to validate live Lynx traffic. The live end-to-end regression above
is currently an interactive test because network requests must be initiated by
the sample app's explicit buttons; it is designed to run on a developer Mac
with real emulators/simulators and public endpoints.
