# iOS Simulator owner over-match regression

## Reported behavior

An iOS snapshot could include unrelated host-proxy requests (for example
ChatGPT/Google traffic) while the sample app was attached.

## Root cause

The libproc resolver checked only whether the attached process had any socket
whose remote endpoint was Lynx's proxy port. Once the sample app opened one
connection, every accepted host-proxy connection satisfied that port-only test.

## Fix

Capture both endpoints of each accepted proxy socket. Match the attached
process's exact local address/ephemeral port and remote proxy address/port in
libproc before admitting interception. Foreign or unknown tuples remain opaque
pass-through and never enter verified capture storage.

## Verification

Fresh committed-tree iOS capture `capture_mudvs10q` returned only the sample
endpoints:

- `http1.testserver.host` — HTTP/1.1;
- `jsonplaceholder.typicode.com` — HTTP/2;
- `ws.postman-echo.com` — WebSocket 101 with ping/echo frames.

The snapshot assertion reported `foreign: []`. WebSocket frames were visible
before Close; stop returned `network_stopped`; detach returned `OK DETACHED`.
