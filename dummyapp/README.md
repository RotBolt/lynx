# Lynx Dummy App

KMP fixture used to exercise Lynx network and SQLite inspection. It is kept
separate from the production Lynx modules.

Status: 🚧 under construction.

The fixture provides Android and iOS simulator targets. It makes real requests
only after a developer presses a button: plain HTTP/1.1 to HTTPBin,
HTTPS/HTTP/2 to JSONPlaceholder, and TLS WebSocket echo to Postman Echo. The
WebSocket has separate Start and Close controls. No local test server or
application-level proxy setting is involved in the network capture path.

Build the iOS simulator fixture with `iosApp/build-simulator.sh`; it produces
an installable `.app` bundle for the booted arm64 simulator.

Each button runs only its corresponding request; there are no startup calls,
timers, or overlapping background scenarios. The app persists actual URLs,
statuses, response bodies, and errors to `network_events` for database
inspection. Start Lynx capture before pressing the desired button. Public
service availability and response status can vary; the validation is whether
Lynx captures the app-origin request and negotiated protocol.

Example iOS inspection flow:

```bash
cd dummyapp
UDID=<booted-simulator-udid>
LYNX=lynx
xcrun simctl boot "$UDID" || true
./iosApp/build-simulator.sh
xcrun simctl install "$UDID" iosApp/build/Debug-iphonesimulator/LynxDummyApp.app
$LYNX attach --device ios-simulator:$UDID --package dev.lynx.dummyapp --json
$LYNX network ca install --ios-simulator "$UDID" --json
$LYNX network start --json
# Relaunch the app after the proxy is active, then press the desired protocol button.
xcrun simctl terminate "$UDID" dev.lynx.dummyapp || true
xcrun simctl launch "$UDID" dev.lynx.dummyapp
sleep 10
$LYNX network list --json
$LYNX db list --json
$LYNX db snapshot Documents/dummyapp.db --json
$LYNX db tables --snapshot <snapshot-id> --json
$LYNX db query --snapshot <snapshot-id> \
  'SELECT transport, status, response_body, error FROM network_events' --json
$LYNX network stop --json
```

Expected network evidence contains an exchange for each button pressed.
Expected database evidence contains rows for `HTTP_1_1`, `HTTP_2`, and
`WEBSOCKET` after the corresponding buttons are used. The certificate installation command
may require explicit user trust confirmation in the Simulator.

For a copyable end-to-end smoke test with prerequisites and cleanup, see
[`docs/IOS_SMOKE_TEST.md`](../docs/IOS_SMOKE_TEST.md).
