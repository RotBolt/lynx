# Native distribution 🚀

This is the canonical installation guide for the released `lynx` executable.
Use it when distributing Lynx to developers or AI agents. Contributors who
need to build the executable should use the final section.

## Release artifacts

| Host | Artifact | Status |
| --- | --- | --- |
| macOS Apple Silicon | `lynx-macos-arm64.tar.gz` | Supported ✅ |
| Linux x64 | `lynx-linux-x64.tar.gz` | Runtime smoke only; app-scoped capture is not yet published 🚧 |
| Windows x64 | — | Under construction 🚧 |

Each supported archive contains:

- the executable named `lynx`;
- the platform runtime libraries needed by that archive;
- the Android relay helper when the target supports Android capture;
- `lynx-skill/SKILL.md`, the vendor-neutral AI-agent instructions;
- a bundle manifest and release checksum.

Release archives include the runtime components required by the supported host.
The Android NDK is only needed when maintainers build native relay helpers.

## Install on macOS Apple Silicon

Prerequisites:

- Android SDK platform-tools (`adb`) for Android targets;
- Xcode Command Line Tools (`xcrun`) for iOS Simulator targets;
- a debuggable app installed on the target device or simulator.

Install with the one-command installer. It detects the host, downloads the
matching release archive, installs `lynx` and the bundled skill under
`$HOME/.local/bin`, and persists that directory in the user's shell profile:

```bash
curl -fsSL https://raw.githubusercontent.com/RotBolt/lynx/main/install.sh | bash
```

Open a new terminal, then verify the regular command:

```bash
command -v lynx
lynx --version
lynx doctor --json
lynx devices --json
```

To pin a release:

```bash
curl -fsSL https://raw.githubusercontent.com/RotBolt/lynx/main/install.sh | \
  bash -s -- v0.1.0-SNAPSHOT
```

The installer verifies a published checksum when available. The `.kexe`
filename only appears when building from source; released users run `lynx`.

## Android SDK setup

If `lynx doctor --json` reports `ADB_NOT_FOUND`, install Android SDK
platform-tools and make the `adb` command available to the shell. Lynx checks
the conventional SDK locations; normal users do not need to set
`ANDROID_HOME`.

## AI-agent skill setup

The release installs the skill beside the executable:

```text
$HOME/.local/bin/lynx-skill/SKILL.md
```

Portable setup:

```bash
mkdir -p "$HOME/.config/lynx/skills"
ln -sfn "$HOME/.local/bin/lynx-skill/SKILL.md" \
  "$HOME/.config/lynx/skills/SKILL.md"
```

Point the agent harness at this installed skill or copy it into the harness's
native skill directory (for example `~/.codex/skills/lynx/SKILL.md`,
`~/.claude/skills/lynx/SKILL.md`, or a repository's `.cursor/rules/lynx.mdc`).
The skill contains the JSON-first attach, capture, database, and cleanup flow.

## First run and CA onboarding

```bash
lynx doctor --json
lynx devices --json
lynx network ca show --json
lynx network ca install --json
```

The native command creates or refreshes the host CA only. It does not install
trust on Android or Apple devices automatically yet 🚧.

Android emulator or device:

```bash
lynx network ca show --json
openssl x509 -in <pemPath> -outform DER -out /tmp/Lynx-Local-CA.cer
adb -s <adb-serial> push /tmp/Lynx-Local-CA.cer \
  /sdcard/Download/Lynx-Local-CA.cer
```

On Android, open **Settings → Security & privacy → More security settings →
Encryption & credentials → Install a certificate → CA certificate**. Select
the copied file and verify the fingerprint under **Trusted credentials → User**.
Do not choose the VPN/app credential path. Debug apps targeting Android 7/API
24 or newer must opt in to user CAs using Network Security Configuration.

iOS Simulator:

```bash
xcrun simctl keychain <simulator-udid> add-root-cert <pemPath>
```

Then enable full trust in Simulator **Settings → General → About → Certificate
Trust Settings** if requested. Physical iOS installation and capture remain
under construction 🚧.

Target-specific `lynx network ca install --android ...` and
`--ios-simulator ...` commands are planned but not implemented yet.

## User workflow

```bash
# Android
lynx attach emulator-5554 com.example.app --json

# iOS Simulator
lynx attach ios-simulator:<simulator-udid> com.example.app --json

# Network
lynx network start --json
# exercise the app
lynx network snapshot --json
lynx network list --json
lynx network list --session <session_id> --json
lynx network get <request_id> --json
lynx network stop --json

# Cleanup
lynx detach --json
```

The native executable shares attachment and evidence state across independent
shell invocations. Network results are scoped to the attached device and
application. Direct sockets, certificate pinning, and QUIC/HTTP/3 are reported
as limitations rather than represented as successful captures.

## Database workflow

Use the exact app-relative database ID returned by `db list`:

```bash
lynx db list --platform android --device emulator-5554 \
  --package com.example.app --json
lynx db snapshot databases/app.db --platform android \
  --device emulator-5554 --package com.example.app --json
```

The snapshot response contains a local path. Pass that path to the read-only
inspection commands while the owning attachment remains active:

```bash
lynx db tables <snapshot_path> --json
lynx db schema <snapshot_path> --json
lynx db query <snapshot_path> \
  'SELECT * FROM messages ORDER BY id DESC' --json
```

Use `--platform ios --simulator <udid> --bundle-id <id>` for iOS Simulator.
Snapshot paths and IDs are not durable after detach.

## Uninstall

```bash
lynx network stop --json || true
lynx detach --json || true
lynx network ca remove --json || true
rm -rf "$HOME/.local/bin/lynx" "$HOME/.local/bin/lynx-skill"
rm -rf "$HOME/.lynx"
```

Remove the Lynx `PATH` line from your shell profile. Remove the CA separately
from Android Settings or the iOS Simulator keychain if it is no longer needed.

## Local and development installation (contributors)

When working from a Lynx checkout, install the local native build with the same
installer:

```bash
./install.sh --local
```

To test the release download path from a checkout:

```bash
./install.sh --remote
```

The remote path requires a published archive asset for the host platform.

## Build from source (contributors)

macOS packaging requires Homebrew OpenSSL/nghttp2/Brotli and the prebuilt
Android relay helper. Maintainers, not release users, need the NDK to build the
helper:

```bash
./scripts/build-android-relay.sh build/android-relay
./gradlew :apps:cli:linkReleaseExecutableMacosArm64 --no-daemon
./scripts/package-macos-native.sh \
  apps/cli/build/bin/macosArm64/releaseExecutable/lynx.kexe \
  /tmp/lynx-macos-arm64.tar.gz \
  build/android-relay
./scripts/verify-macos-native-package.sh /tmp/lynx-macos-arm64.tar.gz
```

The source-built executable is:

```text
apps/cli/build/bin/macosArm64/releaseExecutable/lynx.kexe
```

Use the packaged archive for developer distribution so the executable is
installed and invoked as `lynx`.
