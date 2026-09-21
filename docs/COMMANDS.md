# Lynx command reference 🧭

The examples below use independent shell invocations. The local Lynx daemon
keeps the attached session and evidence between commands.

```bash
LYNX=./apps/cli/build/install/lynx/bin/lynx
```

## Build and daemon ✅

```bash
./gradlew test :apps:cli:installDist --no-daemon
$LYNX doctor
$LYNX devices
$LYNX daemon
```

Run `daemon` in its own terminal.

## Attach and lifecycle ✅

```bash
$LYNX attach --device emulator-5554 --package ai.sarvam.prep.app --json
$LYNX status --json
$LYNX detach --json
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
$LYNX db list --json
$LYNX db snapshot databases/conversation.db --json
$LYNX db tables --snapshot <snapshot_id> --json
$LYNX db schema --snapshot <snapshot_id> --json
$LYNX db query --snapshot <snapshot_id> \
  'SELECT id, role, text FROM messages LIMIT 10' --json
```

Use the full app-relative `database_id` returned by `db list`. Snapshot IDs are
valid while the owning session remains attached. Queries are read-only and
return all selected columns/rows; BLOBs use explicit base64 JSON values.

## 🚧 Under construction

These are specified but not implemented in the current CLI:

- `lynx network watch --jsonl` continuous event streaming.
- `lynx timeline ...` merged evidence queries.
- `lynx db snapshots ...`, `lynx db diff ...`, and `lynx db watch ...`.
- TUI, durable evidence export/import, and JVMTI attribution.
- iOS network traffic capture; only certificate onboarding is available.

Do not interpret an empty capture as proof that no traffic occurred: direct
native sockets, QUIC/HTTP3, certificate pinning, or missing CA trust can keep
traffic outside the supported proxy path. `network doctor` reports these
limitations.
