# Lynx 🐾

> Agent-native, read-only network and SQLite inspection for debuggable mobile apps.

[![License](https://img.shields.io/badge/license-Apache--2.0-blue)](LICENSE)
[![Host](https://img.shields.io/badge/host-macOS-lightgrey)](lynx-spec/FEATURE_REQUIREMENTS.md)

Lynx is a vendor-neutral command-line inspector. It lets a developer or an AI
agent attach to a debuggable app, capture supported network traffic, inspect
SQLite state, and consume deterministic JSON responses. Android Studio is not
required. The app does not need Lynx-specific proxy code, a VPN/TUN service, or
a particular HTTP client.

## Why Lynx helps AI agents 🧠

AI agents need runtime facts, not guesses from source code or an unscoped proxy
stream. Lynx returns target identity, process attribution, protocol,
request/response data, timing, and database results as structured JSON. This
lets an agent verify what happened before suggesting a fix.

## What works today ✅

- Native `lynx` executable for macOS Apple Silicon.
- Android and iOS Simulator device discovery.
- Attach/detach with persistent state across independent shell invocations.
- HTTP/1.1, HTTPS MITM, HTTP/2, and WebSocket-over-TLS capture.
- Session-scoped network snapshots and complete exchange lookup.
- SQLite discovery, snapshots, schema inspection, and read-only SQL.
- Explicit CA onboarding and proxy cleanup.
- Vendor-neutral agent instructions bundled with the release archive.

Windows support, physical iOS capture, QUIC/HTTP/3, and continuous watch
streams remain under construction 🚧.

## Lynx at a glance

| Question | Answer |
| --- | --- |
| What is Lynx? | A vendor-neutral CLI for read-only network and SQLite inspection. |
| Who uses it? | Developers and AI agents debugging a debuggable mobile app. |
| Does it require Android Studio? | No. Lynx uses ADB and Xcode command-line tools. |
| Does the app need Lynx proxy code? | No. The app keeps its normal Ktor, OkHttp, URLSession, or other networking stack. |
| Is database access read-only? | Yes. Lynx snapshots and permits read-only SQL only. |
| Which targets are supported? | Android devices/emulators and iOS Simulators from a macOS host. |
| What does output look like? | Stable JSON envelopes with target identity, attribution, timing, payload, and errors. |

Lynx is an inspection boundary, not an application SDK. Attach the target,
start a capture, exercise the app, and query the returned evidence.

## Compatibility

| Host and target | Attach | Network | SQLite | Status |
| --- | --- | --- | --- | --- |
| macOS Apple Silicon + Android emulator/device | ✅ | HTTP/1.1, HTTPS, HTTP/2, WebSocket | ✅ | Supported |
| macOS Apple Silicon + iOS Simulator | ✅ | HTTP/1.1, HTTPS, HTTP/2, WebSocket | ✅ | Supported |
| macOS + physical iOS device | 🚧 | 🚧 | 🚧 | Under construction 🚧 |
| Linux x64 + Android | 🚧 | Release/runtime smoke | 🚧 | Under construction 🚧 |
| Windows | 🚧 | 🚧 | 🚧 | Under construction 🚧 |

Applications must be debuggable. Direct sockets, certificate pinning, and
QUIC/HTTP/3 can bypass a host proxy and are reported as limitations rather than
invented captures.

## Sample command output

### Android network snapshot

Run after `network start` and after exercising the app:

```bash
lynx network snapshot --json
```

Sample response:

```json
{
  "protocol_version": 1,
  "schema_version": "lynx.v2",
  "type": "network_snapshot",
  "session_id": "capture_mue4nzwz",
  "through_sequence": 18,
  "exchanges": [
    {
      "requestId": "req_mue4o8yf_3",
      "protocol": "HTTP/2",
      "request": {
        "method": "GET",
        "url": "https://jsonplaceholder.typicode.com/todos/1?source=lynx-sample-http2"
      },
      "response": {
        "status": 200,
        "body": "{\n  \"userId\": 1,\n  \"id\": 1,\n  \"title\": \"delectus aut autem\",\n  \"completed\": false\n}"
      },
      "attribution": {
        "status": "verified",
        "deviceId": "emulator-5554",
        "applicationId": "dev.lynx.dummyapp",
        "processId": 28623
      }
    }
  ],
  "in_flight_count": 0
}
```

### iOS Simulator network snapshot

Use the same command after attaching with the `ios-simulator:<UDID>` target:

```bash
lynx network snapshot --json
```

Sample response:

```json
{
  "protocol_version": 1,
  "schema_version": "lynx.v2",
  "type": "network_snapshot",
  "session_id": "capture_mue4y4hr",
  "through_sequence": 23,
  "exchanges": [
    {
      "requestId": "req_mue4yf3g_1",
      "protocol": "HTTP/2",
      "request": {
        "method": "GET",
        "url": "https://jsonplaceholder.typicode.com/todos/1?source=lynx-sample-http2"
      },
      "response": {
        "status": 200,
        "body": "{\n  \"userId\": 1,\n  \"id\": 1,\n  \"title\": \"delectus aut autem\",\n  \"completed\": false\n}"
      },
      "attribution": {
        "status": "verified",
        "deviceId": "25CD22C1-E1F2-417F-87BA-09D7600F3B93",
        "applicationId": "dev.lynx.dummyapp",
        "processId": 16142
      }
    }
  ],
  "in_flight_count": 0
}
```

### Database query and schema

After `db snapshot`, use its returned path:

```bash
lynx db schema <snapshot_path> --json
lynx db query <snapshot_path> \
  'SELECT transport, status, response_body, error FROM network_events' --json
```

Sample query response:

```json
[
  {
    "id": 1,
    "transport": "HTTP_2",
    "method": "GET",
    "url": "https://jsonplaceholder.typicode.com/todos/1?source=lynx-sample-http2",
    "status": 200,
    "response_body": "{\n  \"userId\": 1,\n  \"id\": 1,\n  \"title\": \"delectus aut autem\",\n  \"completed\": false\n}",
    "error": null
  }
]
```

The network response and persisted database row can now be compared directly.

## Install the released executable 🚀

This is the standard installation path for developers and AI agents. Download
the native release archive for your host and follow the first-run checks below.

### Requirements

| Host | Required tools | Status |
| --- | --- | --- |
| macOS Apple Silicon | `adb` from Android SDK platform-tools; `xcrun` from Xcode Command Line Tools | Supported ✅ |
| Linux x64 | `adb` from Android SDK platform-tools | Release/runtime smoke only 🚧 |
| Windows | — | Under construction 🚧 |

For Android, install the Android SDK platform-tools. Lynx checks the standard
SDK locations and the normal command search path; no `ANDROID_HOME` export is
required for the normal installation.

For iOS Simulator support, install Xcode and its command-line tools:

```bash
xcode-select --install
xcrun simctl list devices
```

### One-command install

The repository installer detects the host, downloads the matching release
archive, installs the `lynx` command and bundled AI skill under
`$HOME/.local/bin`, and persists that directory in the user's shell profile.

```bash
curl -fsSL https://raw.githubusercontent.com/RotBolt/lynx/main/install.sh | bash
```

Open a new terminal after installation, then verify:

```bash
command -v lynx
lynx --version
lynx doctor --json
lynx devices --json
```

To install a specific release instead of the latest one:

```bash
curl -fsSL https://raw.githubusercontent.com/RotBolt/lynx/main/install.sh | \
  bash -s -- v0.1.0-SNAPSHOT
```

The installer verifies a published SHA-256 checksum when the release provides
one. The `.kexe` filename is a build artifact; released users run the archive's
`lynx` command.

## Install the AI-agent skill 🤖

The archive installs vendor-neutral instructions at:

```text
$HOME/.local/bin/lynx-skill/SKILL.md
```

Configure the agent harness with this bundled skill. A portable setup that does
not assume a specific vendor is:

```bash
mkdir -p "$HOME/.config/lynx/skills"
ln -sfn "$HOME/.local/bin/lynx-skill/SKILL.md" \
  "$HOME/.config/lynx/skills/SKILL.md"
```

Or copy the file into the skill/rules directory used by your agent. Examples:

| Agent | Link target |
| --- | --- |
| Codex | `~/.codex/skills/lynx/SKILL.md` |
| Claude Code | `~/.claude/skills/lynx/SKILL.md` |
| Cursor | `<project>/.cursor/rules/lynx.mdc` |
| Other harness | Its documented project or global skill directory |

## First-run setup 🔧

Run these checks before attaching:

```bash
lynx doctor --json
lynx devices --json
```

`doctor` reports ADB, Xcode, proxy, CA, and runtime diagnostics. `devices`
reports Android devices and iOS Simulators together. Start or unlock the target
device before attaching it.

### Trust the Lynx CA for HTTPS

The native CA commands create or display the host CA. They do not install a
certificate on Android or Apple devices automatically yet 🚧:

```bash
lynx network ca show --json
lynx network ca install --json
```

#### Android emulator or device

```bash
lynx network ca show --json
openssl x509 -in <pemPath> -outform DER -out /tmp/Lynx-Local-CA.cer
adb -s <adb-serial> push /tmp/Lynx-Local-CA.cer \
  /sdcard/Download/Lynx-Local-CA.cer
```

On the target, open **Settings → Security & privacy → More security settings →
Encryption & credentials → Install a certificate → CA certificate**. Select
the copied file. Choose **CA certificate**, not VPN or app credentials. Verify
the fingerprint under **Trusted credentials → User**. Android 7/API 24+
debug apps must explicitly trust user CAs through Network Security Config.

#### iOS Simulator

```bash
xcrun simctl keychain <booted-simulator-udid> add-root-cert <pemPath>
```

Then enable full trust in **Settings → General → About → Certificate Trust
Settings** if requested. Physical iOS installation and capture remain under
construction 🚧; a real device requires a `.cer`/configuration profile and
explicit full-trust approval in Settings.

## Use Lynx with your own app 👩‍💻

The app must be debuggable and running on a supported target. Lynx does not
need application proxy code. Use the normal app networking stack.

### Attach

Android uses the device serial and application ID:

```bash
lynx attach emulator-5554 com.example.app --json
lynx status --json
```

iOS Simulator uses the simulator UDID prefixed with `ios-simulator:`:

```bash
lynx attach ios-simulator:<booted-simulator-udid> com.example.app --json
lynx status --json
```

Do not invent a PID. Lynx resolves the process from the target and package.

### Inspect network traffic 🌐

Start capture in one shell. Exercise the app in another shell or in its UI.
All commands share the active attachment and capture session:

```bash
lynx network start --json
lynx network doctor --json

# Trigger an HTTP/1.1, HTTP/2, or WebSocket request in the app.
lynx network snapshot --json
lynx network list --json
lynx network stop --json
```

`network snapshot` is the current capture view. `network list` without a
session is a compact session catalog; pass the returned session ID to retrieve
that session's app-scoped exchanges:

```bash
lynx network list --session <session_id> --json
lynx network get <request_id> --json
```

Results are scoped to the attached device and application. Unsupported or
bypassed traffic is reported explicitly: direct/native sockets, certificate
pinning, and QUIC/HTTP/3 do not become false captures.

### Inspect database 🗄️

Discover the exact app-relative database ID first:

```bash
lynx db list --platform android --device emulator-5554 \
  --package com.example.app --json
```

Snapshot using the full ID returned by `db list`:

```bash
lynx db snapshot databases/app.db \
  --platform android --device emulator-5554 \
  --package com.example.app --json
```

The response includes a local snapshot path. Use that path for read-only
inspection while the owning Lynx attachment remains active:

```bash
lynx db tables <snapshot_path> --json
lynx db schema <snapshot_path> --json
lynx db query <snapshot_path> \
  'SELECT * FROM messages ORDER BY id DESC' --json
```

For iOS Simulator, use the same commands with the simulator target:

```bash
lynx db list --platform ios --simulator <simulator-udid> \
  --bundle-id com.example.app --json
lynx db snapshot Documents/app.db --platform ios \
  --simulator <simulator-udid> --bundle-id com.example.app --json
```

Database commands are read-only. Snapshot IDs and paths are session-owned and
must not be reused after detach.

## Under construction 🚧

Target-specific CA installation commands are planned:

```text
lynx network ca install --android <adb-serial>       🚧
lynx network ca install --ios-simulator <simulator>  🚧
```

Physical iOS capture, Windows support, QUIC/HTTP/3, and continuous watch
streams are also under construction.

## Uninstall and clean up 🧹

Stop capture and detach before removing the executable:

```bash
lynx network stop --json || true
lynx detach --json || true
lynx network ca remove --json || true
rm -rf "$HOME/.local/bin/lynx" "$HOME/.local/bin/lynx-skill"
rm -rf "$HOME/.lynx"
```

Remove the Lynx PATH line added by the installer from your shell profile, then
open a new shell. Android and iOS device trust is separate: remove the
Lynx CA from Android Settings and the Simulator keychain if you no longer want
it trusted.

## Local and development installation 🛠️

Contributors can install the native executable from a checkout using the same
installer:

```bash
./install.sh --local
```

To test release installation behavior from a checkout, force the release path:

```bash
./install.sh --remote
```

The remote path requires a published release asset for the current host. Build
and sample-app validation instructions are in the contributor documentation.

## Build the sample app (contributors) 🛠️

Regular developers do not need this section. It is for validating Lynx against
the repository's sample app. The sample app has explicit HTTP/1.1, HTTP/2, and
WebSocket controls, uses real public endpoints, and contains no Lynx proxy
configuration or local fixture server.

See:

- [Android sample-app smoke test](lynx-spec/MANUAL_SMOKE_TEST.md)
- [iOS Simulator smoke test](docs/IOS_SMOKE_TEST.md)
- [Native distribution and contributor build](docs/distribution/native.md)

## Troubleshooting 🩺

| Symptom | Check |
| --- | --- |
| `lynx: command not found` | `command -v lynx`; add `$HOME/.local/bin` to the shell profile and start a new shell. |
| `ADB_NOT_FOUND` | Run `lynx doctor --json`; install Android SDK platform-tools and ensure `adb` is available to the shell. |
| Empty network snapshot | Confirm attach, CA trust, proxy state, and that traffic occurred after `network start`. |
| `HTTPS_MITM_ERROR` | Compare CA fingerprints; check debug user-CA trust and certificate pinning. |
| Only system traffic appears | Reattach the intended package and query the session returned by that capture. |
| Snapshot cannot be queried | Keep the owning attach session active; snapshots are not durable after detach. |

## FAQ

### How do I capture Ktor, OkHttp, Volley, or another client?

Keep the app's normal networking code. Lynx observes supported traffic at the
host proxy boundary, so capture is not coupled to one application library. The
app still must trust the Lynx CA for HTTPS, and certificate pinning or direct
native sockets can prevent decryption.

### Does Lynx require proxy code, a VPN, or a TUN service in the app?

No. Lynx is a host-side CLI. It configures the supported target's debug proxy
path and records traffic outside the app process. No Lynx dependency is added
to the app.

### How do I inspect an Android SQLite database?

Attach first, run `lynx db list` to obtain the exact app-relative database ID,
snapshot that ID, then pass the returned local snapshot path to `db tables`,
`db schema`, or `db query`. Keep the attachment active while querying.

### How do I inspect an iOS Simulator app?

Boot the Simulator, obtain its UDID from `lynx devices --json` or
`xcrun simctl list devices`, then attach with
`ios-simulator:<UDID>`. Install the host CA into that Simulator before an HTTPS
capture.

### Why is my capture empty?

Check, in order: target is booted, package/bundle ID is correct, attach
succeeded, `network start` completed, the request happened after start, the
session ID is the one being queried, and the app trusts the Lynx CA. Empty
output means no exchange was admitted to that session; it does not prove the
app made no request.

### What does `network snapshot` return?

It returns the current session view: `session_id`, `through_sequence`, captured
`exchanges`, and `in_flight_count`. Use it for the active debugging turn. Use
`network list` without a session to discover sessions, then
`network list --session <id>` for that session's app-scoped exchanges.

## Documentation

- [AI-readable documentation map](llms.txt)
- [One-command installer](install.sh)
- [Installation and native distribution](docs/distribution/native.md)
- [AI-agent skill](docs/agent-skill/SKILL.md)
- [Command reference](docs/COMMANDS.md)
- [Architecture](lynx-spec/ARCHITECTURE.md)
- [Implementation details](lynx-spec/IMPLEMENTATION.md)
- [Manual smoke test](lynx-spec/MANUAL_SMOKE_TEST.md)
- [iOS Simulator smoke test](docs/IOS_SMOKE_TEST.md)
- [Roadmap](lynx-spec/ROADMAP.md)
- [Contributing](CONTRIBUTING.md)
- [Support](SUPPORT.md)
- [Security](SECURITY.md)

## License

Lynx is released under the [Apache License 2.0](LICENSE).
