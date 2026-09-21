# iOS Simulator smoke test (JVM compatibility backend) 🧪

> This document exercises the legacy daemon protocol. The supported developer
> distribution is the native KMP executable; use `docs/distribution/native.md`
> for its installation and native command surface.

This is the reproducible macOS smoke test for the currently supported iOS
target. It verifies that Lynx can attach to the fixture, capture HTTP/1.1,
HTTP/2, and WebSocket traffic, and inspect the fixture's SQLite database.

## Prerequisites

- macOS with Xcode command-line tools and a booted iOS Simulator.
- JDK 21 and the Lynx distribution built with `installDist`.
- A running Lynx daemon in a separate terminal.

Build Lynx and start the daemon:

```bash
./gradlew test :apps:cli:installDist --no-daemon
./apps/cli/build/install/lynx/bin/lynx daemon
```

Select a booted Simulator UDID:

```bash
xcrun simctl list devices | grep Booted
UDID=<booted-simulator-udid>
LYNX=./apps/cli/build/install/lynx/bin/lynx
```

## Build, install, and attach

```bash
dummyapp/iosApp/build-simulator.sh
xcrun simctl install "$UDID" \
  dummyapp/iosApp/build/Debug-iphonesimulator/LynxDummyApp.app

$LYNX attach --device ios-simulator:"$UDID" \
  --package dev.lynx.dummyapp --json
```

The attach response must report `type: "attached"`,
`device: "ios-simulator:<UDID>"`, package `dev.lynx.dummyapp`, and a host PID.
Lynx launches the fixture as part of iOS Simulator target resolution.

## Trust and network capture

Install/trust the current Lynx CA, start the host proxy, and relaunch the app
so all three fixture calls occur after capture has started:

```bash
$LYNX network ca show --json
$LYNX network ca install --ios-simulator "$UDID" --json
$LYNX network start --json

xcrun simctl terminate "$UDID" dev.lynx.dummyapp || true
xcrun simctl launch "$UDID" dev.lynx.dummyapp
sleep 10

$LYNX network doctor --json
$LYNX network list --json
```

The verified run produced these exchange classes:

```text
GET https://ws.postman-echo.com/raw             101  WebSocket
GET http://httpbin.org/get                     200  HTTP/1.1
GET https://jsonplaceholder.typicode.com/...   304  HTTP/2
```

The exact HTTP status can vary with the public endpoints; the protocol and
request/response evidence are the assertions. Use `network get <request_id>`
to retrieve one complete exchange.

## Database inspection

```bash
$LYNX db list --json
$LYNX db snapshot Documents/dummyapp.db --json

# Copy the snapshot_id from the snapshot response.
$LYNX db tables --snapshot <snapshot_id> --json
$LYNX db schema --snapshot <snapshot_id> --json
$LYNX db query --snapshot <snapshot_id> \
  'SELECT transport, status, response_body, error FROM network_events' --json
```

The query should return rows for `HTTP_1_1`, `HTTP_2`, and `WEBSOCKET`, with
the recorded response body or error. Snapshot queries are valid only while the
owning Lynx session remains attached.

## Cleanup

```bash
$LYNX network stop --json
$LYNX detach --json
```

If a prior run was interrupted, verify the host proxy state and disable only
the Lynx-applied proxy settings before continuing. Do not leave the macOS
system proxy enabled after the smoke test.

## Scope and 🚧 under construction

This smoke test covers the iOS Simulator adapter only. The following are not
part of the current verified deliverable:

- physical iOS device attachment, capture, or database access 🚧
- localhost/private-address bypass parity with Android 🚧
- QUIC/HTTP3 capture 🚧
- continuous `network watch --jsonl` streaming 🚧
- durable snapshot history and timeline commands 🚧
