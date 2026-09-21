# Lynx Dummy App

KMP fixture used to exercise Lynx network and SQLite inspection. It is kept
separate from the production Lynx modules.

Status: 🚧 under construction.

The fixture provides Android and iOS simulator targets and a deterministic
local HTTP/1.1 + WebSocket server. Start it from this directory with:

```bash
python3 test-server/server.py
```

The HTTPS/HTTP/2 call currently uses the public JSONPlaceholder HTTPS endpoint
until the local TLS test server is added.

Build the iOS simulator fixture with `iosApp/build-simulator.sh`; it produces
an installable `.app` bundle for the booted arm64 simulator.

The fixture’s iOS app and SQLite writes are verified with `xcrun simctl`. Lynx’s
simulator target, host-proxy lifecycle, and read-only SQLite inspection are now
available. Full simulator transport parity (especially localhost bypasses and
system traffic filtering) remains 🚧 under construction.

Example iOS inspection flow:

```bash
UDID=25CD22C1-E1F2-417F-87BA-09D7600F3B93
lynx attach --device ios-simulator:$UDID --package dev.lynx.dummyapp --json
lynx network start --json
lynx network list --json
lynx db list --json
lynx db snapshot Documents/dummyapp.db --json
lynx db tables --snapshot <snapshot-id> --json
lynx db query --snapshot <snapshot-id> 'SELECT * FROM network_events' --json
```
