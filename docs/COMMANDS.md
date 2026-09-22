# Lynx command reference 🧭

The supported developer executable is the native KMP `lynx` binary downloaded
from Releases. The examples below use independent shell invocations and share
state through the native local service/evidence store.

```bash
LYNX=lynx
```

## Native executable ✅

```bash
$LYNX devices
$LYNX --version
$LYNX network ca show --json
```

Download and installation instructions are in
[native distribution](distribution/native.md). No JVM or repository checkout
is required at runtime.

## JVM compatibility backend

The remaining examples in this file use the legacy JVM daemon protocol for
compatibility testing. Contributors can build it explicitly:

```bash
./gradlew test :apps:cli:installJvmDist --no-daemon
LYNX=./apps/cli/build/install/cli-jvm/bin/cli
$LYNX daemon
```

Run `daemon` in its own terminal when using this compatibility backend.

## Attach and lifecycle ✅

```bash
$LYNX attach --device emulator-5554 --package ai.sarvam.prep.app --json
$LYNX status --json
$LYNX detach --json
```

For an iOS Simulator, use the explicit target prefix:

```bash
$LYNX attach --device ios-simulator:<simulator-udid> --package dev.lynx.dummyapp --json
```

The current iOS target is the booted Simulator. Build/install the sample app
before attaching:

```bash
dummyapp/iosApp/build-simulator.sh
xcrun simctl install <simulator-udid> \
  dummyapp/iosApp/build/Debug-iphonesimulator/LynxSampleApp.app
```

`--device` is optional when exactly one online device is available. Lynx resolves
the process PID; agents should not supply a fabricated PID.

## Network capture ✅

```bash
$LYNX network start --json
$LYNX network doctor --json
$LYNX network list --json
$LYNX network get <request_id> --json
$LYNX network stop --json
```

`network doctor` reports protocol support, proxy endpoint/status, CA
fingerprint/path, trust guidance, and explicit unsupported/bypass limitations.
`network list` returns complete retained exchanges; `network get` retrieves one
exchange by request ID.

Supported protocol evidence:

- HTTP/1.1 and HTTPS CONNECT MITM.
- HTTP/2 over TLS (`protocol: "HTTP/2"`).
- WebSocket upgrade and frames (`protocol: "WebSocket"`).

On iOS Simulator, `network start` applies the Lynx proxy to the macOS Wi-Fi
service. Stop capture when finished so the host proxy is restored. The iOS
sample app uses public endpoints (`httpbin.org`, `jsonplaceholder.typicode.com`,
and `ws.postman-echo.com`) because private-address and localhost bypass cases
are not yet supported by the simulator adapter.

On Android, after `network start` applies the device-wide proxy, force-stop and
relaunch an app that was already running before capture began, then trigger its
requests. The app should use its normal HTTP client and debug trust
configuration; it must not contain Lynx-specific proxy settings or code.

## CA onboarding ✅

```bash
$LYNX network ca show --json
$LYNX network ca install --json
$LYNX network ca install --android emulator-5554 --json
$LYNX network ca install --ios-simulator <simulator-udid> --json
$LYNX network ca install --ios-device physical-device --json
$LYNX network ca remove --json
```

Installation is explicit. Android opens/stages the platform installer;
iOS Simulator uses `simctl`; physical iOS receives a generated
`lynx-ca.mobileconfig`. User trust confirmation is never silently claimed.

## Database inspection ✅

```bash
$LYNX db list --platform android --device emulator-5554 \
  --package dev.lynx.dummyapp --json
$LYNX db snapshot databases/conversation.db --platform android \
  --device emulator-5554 --package dev.lynx.dummyapp --json
$LYNX db tables --snapshot <snapshot_id> --json
$LYNX db schema --snapshot <snapshot_id> --json
$LYNX db query --snapshot <snapshot_id> \
  'SELECT id, role, text FROM messages LIMIT 10' --json
```

Use the full app-relative `database_id` returned by `db list`. Snapshot IDs are
valid while the owning session remains attached. Queries are read-only and
return all selected columns/rows; BLOBs use explicit base64 JSON values.

For iOS Simulator, use exactly the same commands and flags. The app-relative
database ID is `Documents/dummyapp.db`:

```bash
$LYNX db list --platform ios --device <simulator-udid> \
  --package dev.lynx.dummyapp --json
$LYNX db snapshot Documents/dummyapp.db --platform ios \
  --device <simulator-udid> --package dev.lynx.dummyapp --json
$LYNX db tables --snapshot <snapshot_id> --json
$LYNX db query --snapshot <snapshot_id> \
  'SELECT transport, status, response_body, error FROM network_events' --json
```

## 🚧 Under construction

These are specified but not implemented in the current CLI:

- `lynx network watch --jsonl` continuous event streaming.
- `lynx timeline ...` merged evidence queries.
- `lynx db snapshots ...`, `lynx db diff ...`, and `lynx db watch ...`.
- TUI, durable evidence export/import, and JVMTI attribution.
- physical iOS attachment and complete simulator transport parity; simulator
  capture uses the macOS system proxy and localhost/bypass cases remain 🚧.
- iOS physical-device network/database inspection 🚧.

Do not interpret an empty capture as proof that no traffic occurred: direct
native sockets, QUIC/HTTP3, certificate pinning, or missing CA trust can keep
traffic outside the supported proxy path. `network doctor` reports these
limitations.
