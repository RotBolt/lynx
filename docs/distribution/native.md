# Native distribution

Lynx now has a Kotlin Multiplatform native CLI target graph:

| Target | Artifact | Status |
|---|---|---|
| macOS ARM64 | `native-cli.kexe` | Database inspection available; network adapter under construction 🚧 |
| Linux x64 | `native-cli.kexe` | Native build and shared code compile; runtime verification follows CI |
| Windows x64 | `native-cli.kexe` | Shared code compile only; platform adapters under construction 🚧 |

## Build locally

```bash
./gradlew :apps:native-cli:linkReleaseExecutableMacosArm64 --no-daemon
./apps/native-cli/build/bin/macosArm64/releaseExecutable/native-cli.kexe --version
```

The native database path uses the host `adb` executable. If it is not on
`PATH`, set `ANDROID_HOME` or `ANDROID_SDK_ROOT`:

```bash
ANDROID_HOME="$HOME/Library/Android/sdk" \
  ./apps/native-cli/build/bin/macosArm64/releaseExecutable/native-cli.kexe devices
```

## Android database smoke path

```bash
lynx-native db list --device emulator-5554 --package dev.lynx.dummyapp
lynx-native db snapshot databases/dummyapp.db \
  --device emulator-5554 --package dev.lynx.dummyapp
lynx-native db tables /tmp/lynx-native-databases_dummyapp.db.db \
  --package dev.lynx.dummyapp
lynx-native db query /tmp/lynx-native-databases_dummyapp.db.db \
  'select * from network_events;' --package dev.lynx.dummyapp
```

Snapshots are copied through `adb shell run-as`; SQLite inspection is performed
by the host `sqlite3` command in read-only mode. This native slice intentionally
does not claim network capture yet. The native proxy, CA lifecycle, HTTP/2, and
WebSocket adapters are the next implementation milestone; the existing JVM CLI
remains the regression oracle for those capabilities.
