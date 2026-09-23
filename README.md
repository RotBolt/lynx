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

## Quick start: use the released executable 🚀

Download the native `lynx` executable from the
[latest GitHub release](https://github.com/RotBolt/lynx/releases/latest). No
repository checkout, Gradle build, JAR, or JVM is needed at runtime. The
current snapshot provides database inspection and persisted attach state on
macOS Apple Silicon and Linux x64; Windows support is under construction 🚧.

### macOS Apple Silicon

```bash
mkdir -p "$HOME/.local/bin"
curl -fL \
  https://github.com/RotBolt/lynx/releases/download/v0.1.0-SNAPSHOT/lynx-macos-arm64.tar.gz \
  -o /tmp/lynx.tar.gz
tar -xzf /tmp/lynx.tar.gz -C "$HOME/.local/bin"
chmod +x "$HOME/.local/bin/lynx"
export PATH="$HOME/.local/bin:$PATH"
lynx --version
```

The archive also installs the vendor-neutral agent instructions at
`$HOME/.local/bin/lynx-skill/SKILL.md`. Point your agent harness at that file,
or copy it into the harness's skill directory; it explains the attach,
database, JSON, and network-evidence workflow without assuming a particular
editor or model.

Linux x64 users can substitute `lynx-linux-x64.tar.gz` in the download URL.
Lynx discovers Android SDK platform-tools from SDK configuration, standard
locations, or `PATH`; use `lynx doctor --json` for exact diagnostics.

Attach to a debuggable app and inspect its database:

```bash
lynx devices
lynx doctor --json
lynx attach emulator-5554 dev.lynx.dummyapp
lynx status
lynx db list --platform android --device emulator-5554 \
  --package dev.lynx.dummyapp
lynx db snapshot databases/dummyapp.db \
  --platform android --device emulator-5554 --package dev.lynx.dummyapp
```

The same `db list` and `db snapshot` commands work with an iOS Simulator by
changing `--platform` to `ios` and passing its UDID and bundle identifier:

```bash
lynx db list --platform ios --simulator <simulator-udid> \
  --bundle-id dev.lynx.dummyapp
lynx db snapshot Documents/dummyapp.db --platform ios \
  --simulator <simulator-udid> --bundle-id dev.lynx.dummyapp
```

The native executable supports persistent HTTP/1.1 and HTTPS MITM capture across
independent invocations:

```bash
lynx network start --json
lynx network list --json                 # session catalog
lynx network snapshot --json             # active session, finite view
lynx network list --session <session_id> --json
lynx network doctor --json
lynx network stop --json
```

The first run creates a CA at `$HOME/.lynx/certs/daemon.pem`; install it in the
debuggable app/device trust store for HTTPS. Native HTTP/2 capture is included;
TLS-WebSocket and QUIC/HTTP3 remain under construction 🚧.

Manage the native CA material without a JVM:

```bash
lynx network ca show --json
lynx network ca install --json
lynx network ca remove --json
```

### JVM compatibility backend

The Gradle/JVM distribution remains available for contributors and compatibility
testing. It is not required by developers or AI agents at runtime, and it is not
the primary network distribution. Use the standalone native `lynx` executable
above for HTTP/1.1, HTTPS CONNECT, HTTP/2, and plain WebSocket capture.

To run the compatibility backend from a source checkout:

```bash
./gradlew test :apps:cli:installJvmDist --no-daemon
./apps/cli/build/install/cli-jvm/bin/cli daemon
```

In another terminal:

```bash
LYNX=./apps/cli/build/install/cli-jvm/bin/cli
$LYNX doctor
$LYNX devices
$LYNX attach --device emulator-5554 --package com.example.app --json
$LYNX network start --json
$LYNX network doctor --json
$LYNX network list --json
```

The app must be debuggable and trust the Lynx CA for HTTPS interception. Use the
onboarding commands when needed:

```bash
$LYNX network ca show --json
$LYNX network ca install --android emulator-5554 --json
$LYNX network ca install --ios-simulator <simulator-udid> --json
```

## Build from source and verify the sample app 🛠️

This section is for contributors and maintainers who want to build Lynx or run
the Android/iOS sample app. Regular developers should use the
released executable above.

Build the native executable locally on macOS Apple Silicon:

```bash
./gradlew :apps:cli:linkReleaseExecutableMacosArm64 --no-daemon
./apps/cli/build/bin/macosArm64/releaseExecutable/lynx.kexe --version
```

For the complete Android attach, HTTP/2, database, restart, and detach smoke
test, see [the manual smoke test](lynx-spec/MANUAL_SMOKE_TEST.md). For the
complete iOS Simulator build/install/capture/query sequence, see the
[iOS Simulator smoke test](docs/IOS_SMOKE_TEST.md). The short iOS sample-app setup
is:

```bash
dummyapp/iosApp/build-simulator.sh
xcrun simctl install <simulator-udid> \
  dummyapp/iosApp/build/Debug-iphonesimulator/LynxSampleApp.app
```

The sample app emits HTTP/1.1, HTTP/2, and WebSocket exchanges and stores the
corresponding evidence in SQLite. Native database smoke commands are documented
in [native distribution](docs/distribution/native.md).

## Database workflow 🗄️

```bash
$LYNX db list --platform android --device emulator-5554 \
  --package ai.sarvam.prep.app --json
$LYNX db snapshot databases/conversation.db --platform android \
  --device emulator-5554 --package ai.sarvam.prep.app --json

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
unit/integration suite, shared sample-app tests, an Android emulator UI/integration
smoke test, and an iOS Simulator UI/integration smoke test on macOS. Hosted
runners without a compatible iOS Simulator runtime/SDK report a visible warning
and skip only that platform UI smoke test; they do not report a false pass.
Platform unit tests run in the corresponding Android and iOS jobs.

## License

Lynx is released under the [Apache License 2.0](LICENSE).
