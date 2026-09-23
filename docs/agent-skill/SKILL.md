---
name: lynx
description: Use the Lynx CLI to attach to a debuggable mobile app, inspect SQLite databases, and retrieve structured network evidence for debugging.
---

# Lynx agent skill 🐾

Use the released native `lynx` executable. Prefer JSON output, preserve IDs
exactly as returned, and keep database operations read-only.

## Operating contract

- Never assume a device, simulator, package, PID, database ID, session ID, or
  request ID from an earlier task.
- Run `doctor` and `devices` before choosing a target.
- Attach before network or database inspection.
- Keep the owning attachment active while using database snapshots.
- Treat `code` and `retryable` as structured error fields.
- An empty list is evidence to investigate, not proof that no traffic occurred.

## First run

```bash
lynx --version
lynx doctor --json
lynx devices --json
lynx network ca show --json
```

If ADB is missing, install Android SDK platform-tools and make the `adb`
command available to the shell, then rerun `doctor`. Lynx checks conventional
SDK locations; do not assume that an environment variable is required.

For HTTPS, create the host CA and read its path:

```bash
lynx network ca install --json
lynx network ca show --json
```

The native command does not install trust on a device. Copy `pemPath` from the
response and complete the platform flow:

- Android: convert the PEM to DER, push it with `adb`, then use Settings →
  Encryption & credentials → Install a certificate → **CA certificate**.
  Android 7/API 24+ debug apps must opt into user CAs with Network Security
  Configuration.
- iOS Simulator: `xcrun simctl keychain <simulator-udid> add-root-cert
  <pemPath>`, then enable full trust in Certificate Trust Settings if requested.
- Physical iOS: not supported by the current native Lynx attachment path 🚧.

Compare the platform fingerprint with `lynx network ca show --json`. Do not
invent target-specific `lynx network ca install --android` or
`--ios-simulator` flags; they are planned, not implemented.

## Attach

Android:

```bash
lynx attach <adb-serial> <application-id> --json
lynx status --json
```

iOS Simulator:

```bash
lynx attach ios-simulator:<simulator-udid> <bundle-id> --json
lynx status --json
```

Do not provide a fabricated PID. Lynx resolves the process for the selected
target and package.

## Network investigation

Start capture, exercise the app, then inspect the same capture from any shell:

```bash
lynx network start --json
lynx network doctor --json

# Trigger the app request here.
lynx network snapshot --json
lynx network list --json
```

`network list` without a session is a compact session catalog. Use the returned
session ID for app-scoped exchanges, then retrieve an individual exchange when
needed:

```bash
lynx network list --session <session_id> --json
lynx network get <request_id> --json
```

Supported evidence includes HTTP/1.1, HTTPS MITM, HTTP/2, and WebSocket-over-
TLS. Direct/native sockets, certificate pinning, and QUIC/HTTP/3 are reported as
limitations. Lynx does not require app proxy code or a VPN/TUN service.

Stop capture before switching targets:

```bash
lynx network stop --json
lynx detach --json
```

## Database investigation

Discover the exact app-relative ID, then snapshot it:

```bash
lynx db list --platform android --device <adb-serial> \
  --package <application-id> --json
lynx db snapshot databases/app.db --platform android \
  --device <adb-serial> --package <application-id> --json
```

Read the local `path` from the snapshot response and use it while the attach
session remains active:

```bash
lynx db tables <snapshot_path> --json
lynx db schema <snapshot_path> --json
lynx db query <snapshot_path> \
  'SELECT * FROM messages ORDER BY id DESC' --json
```

iOS Simulator uses the same command names:

```bash
lynx db list --platform ios --simulator <simulator-udid> \
  --bundle-id <bundle-id> --json
lynx db snapshot Documents/app.db --platform ios \
  --simulator <simulator-udid> --bundle-id <bundle-id> --json
```

Only read-only SQL is accepted. Preserve NULL, BLOB, and column values exactly
as returned. Do not query a snapshot after its owning attachment is detached.

## Evidence handling

- Save the complete JSON response before summarizing it.
- Preserve `session_id`, `request_id`, `snapshot_id`, and database paths.
- Redact secrets before sending evidence to another system.
- Report the target, protocol, status/failure, timing, and body availability.
- If capture is empty, check attachment, session, proxy state, CA trust, and
  whether the app generated traffic after capture started.
