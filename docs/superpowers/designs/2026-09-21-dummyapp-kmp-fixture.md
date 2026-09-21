# Lynx KMP Dummy-App Fixture Design

## Goal

Provide a small, deterministic KMP application under `dummyapp/` that exercises
HTTP/1.1, HTTPS/HTTP/2, and WebSocket traffic, persists each exchange to
SQLite, and can be inspected first on Android and then on an iOS simulator.

## Boundaries

- The fixture is isolated from Lynx production modules.
- The Android target is debuggable and stores its database under the normal
  app-private `databases/` directory.
- The iOS target is a simulator-only debug fixture in this phase.
- No application-level proxy code is added. Network capture remains entirely
  host/proxy controlled.
- The test server is local and deterministic so protocol and persistence
  checks do not depend on public services.

## Structure

```text
dummyapp/
  settings.gradle.kts
  shared/                 # KMP common models and scenario orchestration
  androidApp/             # debuggable Android application
  iosApp/                 # small Xcode host linked to shared framework
  test-server/            # deterministic HTTP/1, HTTP/2, WebSocket server
  README.md
```

`shared` owns the common scenario and persistence contract. Platform adapters
provide the HTTP client, WebSocket client, and SQLite implementation. The
Android app uses a Ktor/OkHttp engine and Android SQLite; the iOS host uses the
Darwin client and SQLite3 through the KMP platform adapter.

## Scenario contract

On launch, and on an explicit repeat action, the fixture performs:

1. an HTTP/1.1 request;
2. an HTTPS request whose server negotiates HTTP/2 through ALPN;
3. a WebSocket echo exchange.

Each result is stored with `kind`, `protocol`, method, URL, status, request and
response bodies, timing, and error text. The database schema is identical on
Android and iOS so Lynx queries have the same shape.

## Verification contract

Android verification must prove all of the following from Lynx output:

- attach succeeds against the fixture package;
- `network list` contains successful HTTP/1.1, HTTP/2, and WebSocket records;
- `db list`, snapshot, tables/schema, and a read-only query expose persisted
  fixture rows.

iOS verification must first prove the app executes and writes its database with
`xcrun simctl`. Lynx simulator inspection requires a separate `simctl` adapter;
the existing ADB-only attachment path must not be used for an iOS UDID.

## Failure policy

The fixture records failures rather than hiding them. Verification reports
which transport was unavailable and preserves the database row describing the
failure. Lynx must not claim iOS inspection support until simulator routing and
database extraction are implemented and exercised.
