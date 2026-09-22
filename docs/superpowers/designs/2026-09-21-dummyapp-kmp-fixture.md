# Lynx KMP Sample App Design

## Goal

Provide a small KMP sample application under `dummyapp/` that exercises
HTTP/1.1, HTTPS/HTTP/2, and WebSocket traffic, persists each exchange to
SQLite, and can be inspected first on Android and then on an iOS simulator.

## Boundaries

- The sample app is isolated from Lynx production modules.
- The Android target is debuggable and stores its database under the normal
  app-private `databases/` directory.
- The iOS target is a simulator-only sample app in this phase.
- No application-level proxy code is added. Network capture remains entirely
  host/proxy controlled.
- The sample app calls public HTTP and WebSocket APIs directly. Mock responses
  belong only in unit tests; no local network fixture server is part of the app.

## Structure

```text
dummyapp/
  settings.gradle.kts
  shared/                 # KMP common models and scenario orchestration
  androidApp/             # debuggable Android application
  iosApp/                 # small Xcode host linked to shared framework
  README.md
```

`shared` owns the common scenario and persistence contract. Platform adapters
provide the HTTP client, WebSocket client, and SQLite implementation. The
Android app uses a Ktor/OkHttp engine and Android SQLite; the iOS host uses the
Darwin client and SQLite3 through the KMP platform adapter.

## Scenario contract

The app performs no network requests on launch, on a timer, or as a combined
scenario. The UI exposes independent controls so each transport can be tested
without producing unrelated traffic:

- **HTTP/1.1 Call** makes one HTTPS request to a server that negotiates
  HTTP/1.1 via ALPN, exercising TLS interception and HTTP/1.1 capture together.
- **HTTP/2 Call** makes one HTTPS request intended to negotiate HTTP/2 through
  ALPN.
- **WebSocket Start** opens a TLS WebSocket and sends one echo message;
  **WebSocket Close** closes the active connection.

Only the selected button's request or connection lifecycle runs. Each result is
stored with `kind`, method, URL, status, request and response bodies, timing,
and error text. The database schema is identical on Android and iOS so Lynx
queries have the same shape.

## Verification contract

Verify direct API requests while Lynx is stopped before running Lynx capture.
The capture phase must prove all of the following from Lynx output:

- attach succeeds against the sample-app package;
- `network list` contains successful HTTP/1.1, HTTP/2, and WebSocket records;
- `db list`, snapshot, tables/schema, and a read-only query expose persisted
  rows written by actual button-triggered API calls.

iOS verification must first prove the app executes and writes its database with
`xcrun simctl`. Lynx simulator inspection requires a separate `simctl` adapter;
the existing ADB-only attachment path must not be used for an iOS UDID.

## Failure policy

The sample app records failures rather than hiding them. Verification reports
which transport was unavailable and preserves the database row describing the
failure. Lynx must not claim iOS inspection support until simulator routing and
database extraction are implemented and exercised.
