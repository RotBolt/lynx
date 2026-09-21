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
production attachment/database/network path remains Android/ADB-only; simulator
inspection is 🚧 under construction and is not claimed by this fixture yet.
