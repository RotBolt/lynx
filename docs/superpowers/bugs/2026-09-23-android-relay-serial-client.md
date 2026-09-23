# Android relay serialized-client regression

## Reported behavior

After HTTP/1.1 and HTTP/2 actions, the unchanged Android sample's WebSocket
button timed out and `network snapshot --json` lacked the WebSocket exchange.
WebSocket worked when it was the only action in a fresh capture.

## Root cause

The device-local relay accepted one client and ran `pump()` synchronously. A
long-lived HTTP/2 CONNECT therefore blocked its `accept()` loop. Later
WebSocket CONNECT traffic never reached the relay. Android's resolver also
needed an IPv4-only, `AI_ADDRCONFIG` lookup on this emulator image; the prior
dual-stack lookup selected an unusable path for some direct upstream connects.

## Fix

- fork one relay worker per accepted client; parent continues accepting;
- constrain device-side upstream resolution to IPv4 with `AI_ADDRCONFIG`;
- keep capture ownership, metadata preface, and pass-through behavior unchanged.

No sample-app proxy or VPN configuration was added.

## Verification

Committed tree: `2ce7db1`; relay built with NDK `30.0.16248370`.

Fresh Android capture `capture_mudveyee` produced, before WebSocket Close:

- HTTP/1.1 `200` from `http1.testserver.host`;
- HTTP/2 `200` from `jsonplaceholder.typicode.com`;
- WebSocket `101` from `ws.postman-echo.com`, with client/server
  `lynx-sample-ping` frames.

Native network/CLI tests passed. Database parity passed: Android 9,483 rows;
iOS 274 rows.
