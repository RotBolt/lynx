# iOS Simulator smoke test (native `lynx`) 🧪

This verifies the native macOS executable against the iOS Simulator sample
app, including explicit HTTP/1.1, HTTP/2, and WebSocket button actions.

This is the reproducible macOS smoke test for the currently supported iOS
target. It verifies that Lynx can attach to the sample app, capture HTTP/1.1,
HTTP/2, and WebSocket traffic, and inspect the app's SQLite database.

## Prerequisites

- macOS with Xcode command-line tools and a booted iOS Simulator.
- The native KMP executable built or downloaded and available as `lynx`.
- A booted iOS Simulator.

Build Lynx and select the executable:

```bash
./gradlew :apps:cli:linkReleaseExecutableMacosArm64 --no-daemon
LYNX=./apps/cli/build/bin/macosArm64/releaseExecutable/lynx.kexe
```

Select a booted Simulator UDID:

```bash
xcrun simctl list devices booted
UDID=<booted-simulator-udid>
```

## Build, install, and attach

```bash
dummyapp/iosApp/build-simulator.sh
xcrun simctl install "$UDID" \
  dummyapp/iosApp/build/Debug-iphonesimulator/LynxSampleApp.app

$LYNX attach ios-simulator:"$UDID" dev.lynx.dummyapp
```

Native attachment launches/resolves the selected Simulator app and records its
host PID. The database commands use the same command names on both platforms:
`db list` and `db snapshot`, with platform and target selected by flags.

## Trust and network capture

First, with Lynx stopped, tap each sample-app action and verify its own status
shows the direct API result. Then start a separate Lynx capture run:

```bash
$LYNX network ca show --json
$LYNX network ca install --ios-simulator "$UDID" --json
$LYNX network start --json

xcrun simctl terminate "$UDID" dev.lynx.dummyapp || true
xcrun simctl launch "$UDID" dev.lynx.dummyapp

# Tap HTTP/1.1 Call, HTTP/2 Call, WebSocket Start, then WebSocket Close in the
# Simulator UI. The app makes no requests on launch.

$LYNX network doctor --json
$LYNX network list --json
```

The verified run produced these exchange classes:

```text
GET https://http1.testserver.host/anything?... 200  HTTP/1.1
GET https://jsonplaceholder.typicode.com/...   200  HTTP/2
GET https://ws.postman-echo.com/raw             101  WebSocket
```

The HTTP/1.1 case is HTTPS: `http1.testserver.host` is a public test endpoint
that negotiates HTTP/1.1 via ALPN. This verifies TLS interception and HTTP/1.1
capture together, not only cleartext HTTP/1.1 forwarding.

The exact HTTP status can vary with the public endpoints; the protocol and
request/response evidence are the assertions. Use `network get <request_id>`
to retrieve one complete exchange.

## Database inspection

```bash
$LYNX db list --platform ios --simulator "$UDID" \
  --bundle-id dev.lynx.dummyapp --json
$LYNX db snapshot Documents/dummyapp.db --platform ios --simulator "$UDID" \
  --bundle-id dev.lynx.dummyapp --json

# Copy the snapshot_id from the snapshot response.
$LYNX db tables --snapshot <snapshot_id> --json
$LYNX db schema --snapshot <snapshot_id> --json
$LYNX db query --snapshot <snapshot_id> \
  'SELECT transport, status, response_body, error FROM network_events' --json
```

The query should return rows for `HTTP_1_1`, `HTTP_2`, and `WEBSOCKET`, with
the recorded response body or error. Snapshot queries are valid only while the
owning Lynx session remains attached.

## Cleanup

```bash
$LYNX network stop --json
$LYNX detach --json
```

If a prior run was interrupted, verify the host proxy state and disable only
the Lynx-applied proxy settings before continuing. Do not leave the macOS
system proxy enabled after the smoke test.

## Scope and 🚧 under construction

This smoke test covers the iOS Simulator adapter only. The following are not
part of the current verified deliverable:

- physical iOS device attachment, capture, or database access 🚧
- localhost/private-address bypass parity with Android 🚧
- QUIC/HTTP3 capture 🚧
- continuous `network watch --jsonl` streaming 🚧
- durable snapshot history and timeline commands 🚧
