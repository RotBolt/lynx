# Plan: KMP Sample App

> **Execution rule:** implement each task in order, run its focused checks, and
> keep Android and iOS verification evidence separate.

## Task 1: Establish the sample-app build

- Add an isolated `dummyapp/` Gradle build with KMP shared code and an Android
  application target.
- Add the minimal iOS host/framework project and document required Xcode tools.
- Add a focused build/structure check before adding runtime behavior.

## Task 2: Implement the common scenario and database contract

- Define common transport/result models and the SQLite schema.
- Implement platform database adapters and deterministic seed/result writes.
- Add unit tests proving all fields, including errors and bodies, persist.

## Task 3: Implement HTTP/1.1, HTTP/2, and WebSocket API calls

- Add platform Ktor clients for public API endpoints. Keep all mocked
  responses inside unit tests only.
- Run each request only from its button and persist the real result.
- Add tests for success and failure records.

## Task 4: Verify the Android sample app through Lynx

- Install/launch the debuggable sample app on the emulator.
- Attach Lynx and start capture without application proxy code.
- Verify all three transports through `network list/get`.
- Verify database discovery, snapshot, schema, and read-only query.
- Commit a checkpoint only after the full Android verification is green.

## Task 5: Add simulator inspection adapters

- Add target resolution and lifecycle routing for iOS simulator UDIDs via
  `xcrun simctl`; preserve the existing ADB path for Android.
- Add simulator database extraction and proxy configuration diagnostics.
- Add focused tests for target selection and error contracts.

## Task 6: Verify the iOS sample app

- Build/install/launch the iOS sample app on the booted simulator.
- Verify transport execution and database persistence with `simctl`.
- Verify Lynx network and database inspection through the simulator adapter.
- Commit a final checkpoint only after evidence covers HTTP/1.1, HTTP/2,
  WebSocket, and database query output.
