# Native distribution 🚀

The supported developer download is the standalone `lynx` executable attached
to the [GitHub Releases](https://github.com/RotBolt/lynx/releases) page. These
archives are intended for users of Lynx; cloning the repository is only needed
for contributors.

Lynx now has a Kotlin Multiplatform native CLI target graph:

| Target | Artifact | Status |
|---|---|---|
| macOS ARM64 | `lynx-macos-arm64.tar.gz` | Database inspection and persistent HTTP/1.1 capture available; HTTPS/HTTP2/WebSocket adapters under construction 🚧 |
| Linux x64 | `lynx-linux-x64.tar.gz` | Same native HTTP/1.1 path; Linux smoke test runs in CI |
| Windows x64 | — | Common KMP code compiles; native runtime adapters under construction 🚧 |

Each archive contains the executable named `lynx` and
`lynx-skill/SKILL.md`. Install the executable on `PATH` (for example,
`$HOME/.local/bin`) and point an agent harness at the bundled skill file.
Verify the executable with `lynx --version`.
SHA-256 checksums are published with each release.

## Build locally

```bash
./gradlew :apps:native-cli:linkReleaseExecutableMacosArm64 --no-daemon
./apps/native-cli/build/bin/macosArm64/releaseExecutable/lynx.kexe --version
```

The native database path uses the host `adb` executable. If it is not on
`PATH`, set `ANDROID_HOME` or `ANDROID_SDK_ROOT`:

```bash
ANDROID_HOME="$HOME/Library/Android/sdk" \
  ./apps/native-cli/build/bin/macosArm64/releaseExecutable/lynx.kexe devices
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
invocations. The native proxy currently forwards and records cleartext HTTP/1.1.
Native CA/TLS, HTTP/2, and WebSocket adapters remain under construction 🚧;
the JVM CLI remains the regression oracle for those protocols until their native
adapters are verified.
