# Lynx 🐾

> Agent-native network and SQLite inspection for debuggable Android apps.

[![Build](https://img.shields.io/badge/build-Gradle-02303A?logo=gradle)](https://gradle.org/)
[![Platform](https://img.shields.io/badge/host-macOS-lightgrey)](lynx-spec/FEATURE_REQUIREMENTS.md)
[![License](https://img.shields.io/badge/license-Apache--2.0-blue)](LICENSE)

Lynx is a vendor-agnostic, terminal-first inspector that lets developers and
AI agents attach to a debuggable Android app, observe network traffic, inspect
SQLite state, and consume the results as versioned JSON or JSONL.

It does not require Android Studio, a VPN/TUN service, application proxy code,
or a particular AI vendor.

## What works today ✅

- ADB-based attach with automatic device/package PID discovery.
- Session continuity when the app process restarts.
- HTTP/1.1 and HTTPS CONNECT MITM capture.
- HTTP/2 capture, including complete headers and bodies.
- WebSocket upgrade and frame capture.
- Complete request/response retrieval through `network get`.
- SQLite discovery, WAL-aware snapshots, schema inspection, and read-only SQL.
- Transactional Android system-proxy apply/restore.
- Structured capability, trust, bypass, and unsupported-protocol diagnostics.
- Android CA staging, iOS Simulator installation, and physical-iOS profile generation.

## Quick start

Requirements: macOS, JDK 21, Gradle wrapper, Android SDK platform-tools, and a
debuggable Android emulator or device.

```bash
./gradlew test :apps:cli:installDist --no-daemon
./apps/cli/build/install/lynx/bin/lynx daemon
```

In another terminal:

```bash
LYNX=./apps/cli/build/install/lynx/bin/lynx

$LYNX doctor
$LYNX devices
$LYNX attach --device emulator-5554 --package com.example.app --json
$LYNX network start --json
$LYNX network doctor --json
$LYNX network list --json
```

The app must be debuggable and must trust the Lynx CA for HTTPS interception.
Use the onboarding commands when needed:

```bash
$LYNX network ca show --json
$LYNX network ca install --android emulator-5554 --json
$LYNX network ca install --ios-simulator <simulator-udid> --json
$LYNX network ca install --ios-device physical-device --json
```

## Database workflow 🗄️

```bash
$LYNX db list --json
$LYNX db snapshot databases/conversation.db --json

# Use the snapshot_id returned above.
$LYNX db tables --snapshot <snapshot_id> --json
$LYNX db schema --snapshot <snapshot_id> --json
$LYNX db query --snapshot <snapshot_id> \
  'SELECT id, role, text FROM messages LIMIT 10' --json
```

Queries are read-only. Results preserve SQLite NULL, INTEGER, REAL, TEXT, and
BLOB values using stable JSON representations. Snapshots are valid only while
their owning Lynx session remains attached.

## Agent contract 🤖

Every finite machine-facing response includes `protocol_version`,
`schema_version`, `type`, and structured errors. Network evidence includes the
request ID, headers, body, status/failure, timing, protocol, and WebSocket
frames where applicable. Database responses include snapshot/database IDs,
columns, rows, and explicit BLOB encoding.

See [docs/COMMANDS.md](docs/COMMANDS.md) for the complete command contract and
[lynx-spec/FEATURE_REQUIREMENTS.md](lynx-spec/FEATURE_REQUIREMENTS.md) for the
versioned requirements.

## Scope and limitations ⚠️

- Debuggable Android applications are the MVP target.
- QUIC/HTTP3 is unsupported.
- Direct/native sockets that bypass the Android system proxy cannot be captured.
- HTTPS requires user-approved CA trust and may be blocked by certificate pinning.
- WAL snapshots can report `consistent=false` when coherence cannot be proven.
- Database operations are read-only.

### 🚧 Under construction

The following commands and features are specified for later milestones but are
not part of the current working CLI:

- `lynx timeline ...` merged timeline queries.
- `lynx network watch --jsonl` continuous streaming mode.
- `lynx db snapshots ...` snapshot history listing.
- `lynx db diff ...` and `lynx db watch ...`.
- TUI views, persistent evidence export/import, and JVMTI attribution.
- iOS traffic capture (certificate onboarding is available; capture is not).

## Documentation

- [Command reference](docs/COMMANDS.md)
- [Architecture](lynx-spec/ARCHITECTURE.md)
- [Implementation details](lynx-spec/IMPLEMENTATION.md)
- [Manual smoke test](MANUAL_SMOKE_TEST.md)
- [M1 smoke evidence](lynx-spec/MANUAL_SMOKE_TEST.md)
- [Roadmap](lynx-spec/ROADMAP.md)
- [Contributing](CONTRIBUTING.md)

## License

Lynx is released under the [Apache License 2.0](LICENSE).
