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
