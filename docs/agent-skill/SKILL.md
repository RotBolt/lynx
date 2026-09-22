---
name: lynx
description: Use the Lynx CLI to attach to a debuggable mobile app, inspect SQLite databases, and retrieve structured network evidence for debugging.
---

# Lynx agent skill 🐾

Lynx is a vendor-neutral, read-only inspector. Prefer its JSON output for
machine work and preserve the returned IDs when following up. Do not assume a
device, package, PID, database path, or snapshot ID from an earlier task: list
or attach again when the target changes.

## First-run setup

1. Confirm `lynx` is executable and on `PATH`:

   ```bash
   lynx --version
   lynx doctor --json
   lynx devices --json
   ```

2. Attach to the debuggable app. Android uses the device serial and package;
   the iOS Simulator uses the `ios-simulator:<UDID>` device form:

   ```bash
   lynx attach emulator-5554 dev.lynx.dummyapp
   # or: lynx attach ios-simulator:<UDID> dev.lynx.dummyapp
   lynx status
   ```

3. Keep the attach session alive while using database snapshots and queries.
   A snapshot is session-owned and cannot be queried after detach.

`devices --json` reports Android and iOS Simulator availability together. Use
its stable `id`; do not attach a shutdown or unavailable simulator.

## Database investigation

Discover database IDs before taking a snapshot. Use the exact app-relative ID
returned by the command; do not shorten `databases/foo.db` to `foo.db`.

```bash
lynx db list --platform android --device emulator-5554 \
  --package dev.lynx.dummyapp --json
lynx db snapshot databases/dummyapp.db \
  --platform android --device emulator-5554 \
  --package dev.lynx.dummyapp --json
```

For iOS Simulator, keep the same command names and use the simulator UDID:

```bash
lynx db list --platform ios --simulator <simulator-udid> \
  --bundle-id dev.lynx.dummyapp --json
lynx db snapshot Documents/dummyapp.db --platform ios \
  --simulator <simulator-udid> --bundle-id dev.lynx.dummyapp --json
```

Read `snapshot_id` from the response, then inspect schema and data:

```bash
lynx db tables --snapshot <snapshot_id> --json
lynx db schema --snapshot <snapshot_id> --json
lynx db query --snapshot <snapshot_id> \
  'SELECT * FROM network_events ORDER BY id DESC' --json
```

Only read-only SQL is accepted. Use explicit projections and predicates when
the result may be large. Treat `consistent: false` as a WAL-coherence warning,
not as proof that the database is unusable.

## Network investigation

The native executable supports persistent HTTP/1.1, HTTPS MITM, and HTTP/2 capture. Start it
in one shell and query it from another:

```bash
lynx network start --json
lynx network doctor --json
lynx network list --json
```

Use the `requestId` from `network list` with `network get` to retrieve the
complete request/response bodies, headers, timing, failures, and WebSocket
frames. Capture requires the app to use the Android system proxy and trust the
Lynx CA for HTTPS; direct/native sockets, certificate pinning, and QUIC/HTTP3
are reported as limitations rather than silently treated as captured.

The native executable also persists attach state and network evidence. Its first
network run creates `$HOME/.lynx/certs/daemon.pem`; install that CA in the
debuggable app/device before HTTPS capture. TLS-WebSocket and QUIC/HTTP3 are under
construction 🚧 and must be reported as native limitations.

## Agent operating rules

- Prefer `--json` for one response and `--jsonl` for streams when available.
- Treat `code` and `retryable` fields as the error contract; never parse human
  prose when a structured field exists.
- Keep `device`, `package`, database IDs, snapshot IDs, and request IDs exactly
  as returned (including case).
- Do not mutate app data: Lynx database commands are read-only.
- Redact secrets from logs and do not persist network bodies outside the
  evidence path requested by the user.
- If a command returns an empty list, check `status`, attachment, proxy state,
  and timing before concluding that the app made no request.

For the full command contract and platform details, see `docs/COMMANDS.md` in
the source repository or the online project documentation.
