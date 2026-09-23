# Native distribution 🚀

The supported developer download is the standalone `lynx` executable attached
to the [GitHub Releases](https://github.com/RotBolt/lynx/releases) page. These
archives are intended for users of Lynx; cloning the repository is only needed
for contributors.

Lynx now has a Kotlin Multiplatform native CLI target graph:

| Target | Artifact | Status |
|---|---|---|
| macOS ARM64 | `lynx-macos-arm64.tar.gz` | Database inspection, HTTP/1.1, HTTPS MITM, HTTP/2, and WebSocket-over-TLS capture available |
| Linux x64 | `lynx-linux-x64.tar.gz` | Archive/runtime smoke only; device-backed app-scoped attribution and bundled Android relay remain unverified and unpublished 🚧 |
| Windows x64 | — | Common KMP code compiles; native runtime adapters under construction 🚧 |

The network API itself is also a KMP boundary. `host:network` exposes the
portable command/result contract in `commonMain`; its JVM proxy is isolated in
`jvmMain`, while macOS and Linux resolve the same API to the native POSIX
proxy. The standalone executable therefore does not load the JVM network
backend, Netty, or a JVM TLS provider.

The macOS archive also contains ABI-matched Android relay helpers under
`android-relay/`, a verified `lynx-bundle.json` manifest, native TLS/HTTP2/Brotli
runtime libraries, and `lynx-skill/SKILL.md`. Lynx auto-selects the relay helper
from its extracted archive; `LYNX_ANDROID_RELAY_BINARY` remains a development
override. Install the executable on `PATH` (for example,
`$HOME/.local/bin`) and point an agent harness at the bundled skill file.
Verify the executable with `lynx --version`.
SHA-256 checksums are published with each release.

## Build locally

```bash
./gradlew :apps:cli:linkReleaseExecutableMacosArm64 --no-daemon
./apps/cli/build/bin/macosArm64/releaseExecutable/lynx.kexe --version
```

The native database path uses the host `adb` executable. If it is not on
`PATH`, set `ANDROID_HOME` or `ANDROID_SDK_ROOT`:

```bash
ANDROID_HOME="$HOME/Library/Android/sdk" \
  ./apps/cli/build/bin/macosArm64/releaseExecutable/lynx.kexe devices
```

The Linux release archive carries the OpenSSL 3, nghttp2, and Brotli runtime
libraries, so a release install does not require a JVM or a separate native
networking installation. Building from source on Linux still requires the
corresponding development packages (`libssl-dev`, `libnghttp2-dev`, and
`libbrotli-dev`).

After obtaining a Linux binary, run the same executable-level regression used
by the native workflow:

```bash
./scripts/native-linux-smoke.sh ./lynx
```

## Attach session (macOS/Linux native CLI)

Native attach state is shared by independent invocations through
`$HOME/.lynx/native-session`:

```bash
lynx attach emulator-5554 dev.lynx.dummyapp
lynx status
lynx detach
```

Windows common code is compiled in CI, while its native runtime adapters remain
under construction 🚧.

## Android database smoke path

```bash
lynx db list --platform android --device emulator-5554 \
  --package dev.lynx.dummyapp
lynx db snapshot databases/dummyapp.db \
  --platform android --device emulator-5554 --package dev.lynx.dummyapp
lynx db tables /tmp/lynx-native-databases_dummyapp.db.db \
  --package dev.lynx.dummyapp
lynx db query /tmp/lynx-native-databases_dummyapp.db.db \
  'select * from network_events;' --package dev.lynx.dummyapp
```

## iOS Simulator database smoke path

The macOS native binary can resolve the simulator app container through
`simctl` and inspect the copied SQLite file:

```bash
lynx db list --platform ios --simulator <simulator-udid> \
  --bundle-id dev.lynx.dummyapp
lynx db snapshot Documents/dummyapp.db --platform ios \
  --simulator <simulator-udid> --bundle-id dev.lynx.dummyapp
lynx db tables /tmp/lynx-native-Documents_dummyapp.db.db \
  --package dev.lynx.dummyapp
lynx db query /tmp/lynx-native-Documents_dummyapp.db.db \
  'select count(*) as count from network_events;' --package dev.lynx.dummyapp
```

Snapshots are copied through `adb shell run-as` on Android or `simctl` on iOS;
SQLite inspection is performed by the host `sqlite3` command in read-only mode.
Both platforms use the same `db list` / `db snapshot` syntax. Android targets
use `--device` (ADB serial) and `--package` (application ID); iOS Simulator
targets use `--simulator` (UDID) and `--bundle-id`. Native `network start`, `stop`,
`list`, `snapshot`, `get`, and `doctor` share a JSONL evidence store across independent
invocations. The native proxy forwards and records cleartext HTTP/1.1, HTTPS
CONNECT, HTTP/2, and WebSocket-over-TLS traffic using the host OpenSSL and
nghttp2 runtimes. The first run creates a CA under `$HOME/.lynx/certs`; install
`daemon.pem` in the debug app/device trust store. QUIC/HTTP3 remains under
construction 🚧.

Native CA lifecycle commands are available without the JVM distribution:

```bash
lynx network ca show --json
lynx network ca install --json
lynx network ca remove --json
```

`install` creates or refreshes the host CA and prints explicit trust-store
instructions; it never mutates an Android or iOS trust store implicitly.

## Package from source

macOS packaging requires Homebrew OpenSSL/nghttp2/Brotli and prebuilt Android
relay helpers. Developers need no NDK to run extracted archives; NDK is only
needed by maintainers building helpers:

```bash
./scripts/build-android-relay.sh build/android-relay
./gradlew :apps:cli:linkReleaseExecutableMacosArm64 --no-daemon
./scripts/package-macos-native.sh \
  apps/cli/build/bin/macosArm64/releaseExecutable/lynx.kexe \
  /tmp/lynx-macos-arm64.tar.gz \
  build/android-relay
./scripts/verify-macos-native-package.sh /tmp/lynx-macos-arm64.tar.gz
```
