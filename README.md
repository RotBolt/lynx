# Lynx 🐾

> Agent-native network and SQLite inspection for debuggable Android apps.

[![Build](https://img.shields.io/badge/build-Gradle-02303A?logo=gradle)](https://gradle.org/)
[![Platform](https://img.shields.io/badge/host-macOS-lightgrey)](lynx-spec/FEATURE_REQUIREMENTS.md)
[![License](https://img.shields.io/badge/license-Apache--2.0-blue)](LICENSE)
[![CI](https://github.com/RotBolt/lynx/actions/workflows/ci.yml/badge.svg)](https://github.com/RotBolt/lynx/actions/workflows/ci.yml)

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
debuggable Android emulator/device or an iOS Simulator. The iOS Simulator
fixture additionally requires Xcode command-line tools (`xcrun`, `simctl`, and
the iOS Simulator SDK).

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

For the verified iOS Simulator workflow, build/install the fixture, attach it
with the `ios-simulator:` target prefix, and then use the same network and
database commands:

```bash
dummyapp/iosApp/build-simulator.sh
xcrun simctl install <simulator-udid> \
  dummyapp/iosApp/build/Debug-iphonesimulator/LynxDummyApp.app
$LYNX attach --device ios-simulator:<simulator-udid> \
  --package dev.lynx.dummyapp --json
$LYNX network ca install --ios-simulator <simulator-udid> --json
$LYNX network start --json
$LYNX network list --json
$LYNX db list --json
$LYNX db snapshot Documents/dummyapp.db --json
```

The fixture produces one HTTP/1.1 request, one HTTP/2 request, and one
WebSocket exchange. See the complete [iOS Simulator smoke test](docs/IOS_SMOKE_TEST.md)
for the build, launch, capture, and query sequence.

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
- physical iOS capture and complete simulator transport parity; simulator
  attach/database and host-proxy capture are available, with localhost/bypass
  cases 🚧 under construction.
- automated physical-device iOS capture and device-side database access 🚧
  under construction.

## Documentation

- [Command reference](docs/COMMANDS.md)
- [Architecture](lynx-spec/ARCHITECTURE.md)
- [Implementation details](lynx-spec/IMPLEMENTATION.md)
- [Manual smoke test](MANUAL_SMOKE_TEST.md)
- [M1 smoke evidence](lynx-spec/MANUAL_SMOKE_TEST.md)
- [iOS Simulator smoke test](docs/IOS_SMOKE_TEST.md)
- [Roadmap](lynx-spec/ROADMAP.md)
- [Contributing](CONTRIBUTING.md)

## Continuous integration 🧪

Every push to `main`, pull request, and manual workflow dispatch runs the host
unit/integration suite, shared fixture tests, an Android emulator UI/integration
smoke test, and an iOS Simulator UI/integration smoke test on macOS. Hosted
runners without a compatible iOS Simulator runtime/SDK report a visible warning
and skip only that platform UI smoke test; they do not report a false pass.
Platform unit tests run in the corresponding Android and iOS jobs.

## License

Lynx is released under the [Apache License 2.0](LICENSE).
## Native executable (experimental)

Lynx is being migrated to Kotlin Multiplatform. The current native macOS ARM64
binary can inspect Android SQLite databases without a JVM:

```bash
./gradlew :apps:native-cli:linkReleaseExecutableMacosArm64 --no-daemon
./apps/native-cli/build/bin/macosArm64/releaseExecutable/native-cli.kexe --version
```

See [native distribution documentation](docs/distribution/native.md). Network
capture in the native binary is under construction 🚧; use the existing JVM
distribution for the currently supported network inspector workflow.
