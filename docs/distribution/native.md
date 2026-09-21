# Native distribution 🚀

The supported developer download is the standalone `lynx` executable attached
to the [GitHub Releases](https://github.com/RotBolt/lynx/releases) page. These
archives are intended for users of Lynx; cloning the repository is only needed
for contributors.

Lynx now has a Kotlin Multiplatform native CLI target graph:

| Target | Artifact | Status |
|---|---|---|
| macOS ARM64 | `lynx-macos-arm64.tar.gz` | Database inspection, HTTP/1.1, HTTPS MITM, HTTP/2, and plain WebSocket capture available; TLS-WebSocket adapter under construction 🚧 |
| Linux x64 | `lynx-linux-x64.tar.gz` | Native HTTP/1.1, HTTPS MITM, HTTP/2, and plain WebSocket capture; the archive carries OpenSSL/nghttp2 runtime libraries |
| Windows x64 | — | Common KMP code compiles; native runtime adapters under construction 🚧 |

The network API itself is also a KMP boundary. `host:network` exposes the
portable command/result contract in `commonMain`; its JVM proxy is isolated in
`jvmMain`, while macOS and Linux resolve the same API to the native POSIX
proxy. The standalone executable therefore does not load the JVM network
backend, Netty, or a JVM TLS provider.

Each archive contains the executable named `lynx`, native TLS/HTTP2 runtime libraries,
and
`lynx-skill/SKILL.md`. Install the executable on `PATH` (for example,
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

The Linux release archive carries the OpenSSL 3 and nghttp2 runtime libraries,
so a release install does not require a JVM or a separate native networking
installation. Building from source on Linux still requires the corresponding
development packages (`libssl-dev` and `libnghttp2-dev`).

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
lynx db list --device emulator-5554 --package dev.lynx.dummyapp
lynx db snapshot databases/dummyapp.db \
  --device emulator-5554 --package dev.lynx.dummyapp
lynx db tables /tmp/lynx-native-databases_dummyapp.db.db \
  --package dev.lynx.dummyapp
lynx db query /tmp/lynx-native-databases_dummyapp.db.db \
  'select * from network_events;' --package dev.lynx.dummyapp
```

## iOS Simulator database smoke path

The macOS native binary can resolve the simulator app container through
`simctl` and inspect the copied SQLite file:

```bash
lynx db ios-list --udid booted --package dev.lynx.dummyapp
lynx db ios-snapshot Documents/dummyapp.db \
  --udid booted --package dev.lynx.dummyapp
lynx db tables /tmp/lynx-native-ios-Documents_dummyapp.db.db \
  --package dev.lynx.dummyapp
lynx db query /tmp/lynx-native-ios-Documents_dummyapp.db.db \
  'select count(*) as count from network_events;' --package dev.lynx.dummyapp
```

Snapshots are copied through `adb shell run-as`; SQLite inspection is performed
by the host `sqlite3` command in read-only mode. Native `network start`, `stop`,
`list`, `get`, and `doctor` share a JSONL evidence store across independent
invocations. The native proxy forwards and records cleartext HTTP/1.1, HTTPS
CONNECT, and HTTP/2 traffic using the host OpenSSL and nghttp2 runtimes. The
first run creates a CA under `$HOME/.lynx/certs`; install `daemon.pem` in the
debug app/device trust store. TLS-WebSocket and QUIC/HTTP3 remain under
construction 🚧.

Native CA lifecycle commands are available without the JVM distribution:

```bash
lynx network ca show --json
lynx network ca install --json
lynx network ca remove --json
```

`install` creates or refreshes the host CA and prints explicit trust-store
instructions; it never mutates an Android or iOS trust store implicitly.
