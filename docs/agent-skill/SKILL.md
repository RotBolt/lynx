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
   lynx devices
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

## Database investigation

Discover database IDs before taking a snapshot. Use the exact app-relative ID
returned by the command; do not shorten `databases/foo.db` to `foo.db`.

```bash
lynx db list --device emulator-5554 --package dev.lynx.dummyapp --json
lynx db snapshot databases/dummyapp.db \
  --device emulator-5554 --package dev.lynx.dummyapp --json
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

The native executable supports persistent cleartext HTTP/1.1 capture. Start it
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

The native executable also persists attach state and network evidence. Native
HTTPS/TLS, HTTP/2, and WebSocket capture are under construction 🚧. For those
protocols, use the JVM distribution and report the native limitation explicitly.

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
