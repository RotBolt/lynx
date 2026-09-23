# Lynx Sample App (contributor validation) 🛠️

Small KMP sample application for validating Lynx network and SQLite inspection.
It is separate from the production Lynx modules. Regular Lynx users should
follow the release installation guide in [`../README.md`](../README.md) instead
of building this app.

Status: 🚧 under construction.

The app has Android and iOS Simulator targets. On explicit button presses it
makes ordinary app-origin requests to public services: HTTPS/HTTP/1.1 to the
HTTP Toolkit public HTTP test server, HTTPS (negotiated as HTTP/2 when the
platform supports it) to JSONPlaceholder, and a TLS WebSocket echo to Postman
Echo. The WebSocket has separate Start and Close controls. No network request
is made on launch or on a timer, and the app does not configure a proxy. Mock
responses are confined to unit tests; there is no local fixture server in the
sample app.

Build the iOS simulator app with `iosApp/build-simulator.sh`; it produces
an installable `.app` bundle for the booted arm64 simulator.

Each button runs only its corresponding request; there are no startup calls,
timers, or overlapping background scenarios. The app persists actual URLs,
statuses, response bodies, and errors to `network_events` for database
inspection. Test the app's direct request first with Lynx stopped; then run a
separate Lynx capture test and press the same button. Public service
availability and response status can vary, and negotiated protocol must be
verified from the observed exchange rather than inferred from the button name.

Example iOS inspection flow:

```bash
cd dummyapp
UDID=<booted-simulator-udid>
LYNX=lynx
xcrun simctl boot "$UDID" || true
./iosApp/build-simulator.sh
xcrun simctl install "$UDID" iosApp/build/Debug-iphonesimulator/LynxSampleApp.app
$LYNX attach ios-simulator:$UDID dev.lynx.dummyapp --json
$LYNX network ca install --json
# Install pemPath manually with:
xcrun simctl keychain "$UDID" add-root-cert <pemPath>
$LYNX network start --json
# Relaunch the app after the proxy is active, then press the desired protocol button.
xcrun simctl terminate "$UDID" dev.lynx.dummyapp || true
xcrun simctl launch "$UDID" dev.lynx.dummyapp
sleep 10
$LYNX network list --json
$LYNX db list --platform ios --simulator "$UDID" \
  --bundle-id dev.lynx.dummyapp --json
$LYNX db snapshot Documents/dummyapp.db --platform ios \
  --simulator "$UDID" --bundle-id dev.lynx.dummyapp --json
# Read `path` from the snapshot response.
$LYNX db tables <snapshot-path> --json
$LYNX db query <snapshot-path> \
  'SELECT transport, status, response_body, error FROM network_events' --json
$LYNX network stop --json
```

Expected network evidence contains an exchange for each button pressed.
Expected database evidence contains rows for `HTTP_1_1`, `HTTP_2`, and
`WEBSOCKET` after the corresponding buttons are used. The certificate installation command
may require explicit user trust confirmation in the Simulator.

For a copyable end-to-end smoke test with prerequisites and cleanup, see
[`docs/IOS_SMOKE_TEST.md`](../docs/IOS_SMOKE_TEST.md).
