# Lynx Dummy App

KMP fixture used to exercise Lynx network and SQLite inspection. It is kept
separate from the production Lynx modules.

Status: 🚧 under construction.

The fixture provides Android and iOS simulator targets and a deterministic
local HTTP/1.1 + WebSocket server for Android. Start it from this directory
with:

```bash
python3 test-server/server.py
```

The HTTPS/HTTP/2 call uses JSONPlaceholder. The iOS host uses public HTTP/1.1
and WebSocket echo endpoints because the simulator bypasses private-address
proxy traffic.

Build the iOS simulator fixture with `iosApp/build-simulator.sh`; it produces
an installable `.app` bundle for the booted arm64 simulator.

The fixture’s iOS app and SQLite writes are verified with `xcrun simctl`. Lynx’s
simulator target, host-proxy lifecycle, and read-only SQLite inspection are now
available. The verified simulator run captures HTTP/1.1, HTTP/2, and WebSocket
traffic and reads the corresponding rows from `Documents/dummyapp.db`. Full
simulator transport parity (especially localhost bypasses and system traffic
filtering) remains 🚧 under construction.

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
# Relaunch the app after the proxy is active, then wait for its scenario.
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

Expected network evidence contains one `HTTP/1.1`, one `HTTP/2`, and one
`WebSocket` exchange. Expected database evidence contains rows for
`HTTP_1_1`, `HTTP_2`, and `WEBSOCKET`. The certificate installation command
may require explicit user trust confirmation in the Simulator.

For a copyable end-to-end smoke test with prerequisites and cleanup, see
[`docs/IOS_SMOKE_TEST.md`](../docs/IOS_SMOKE_TEST.md).
