# External Technology Research

The foundational Lynx architecture no longer depends on Android Studio Network Inspector or Database Inspector.

## Android
Use official ADB/platform behavior for:
- device discovery;
- `run-as`;
- proxy configuration;
- emulator/physical routing;
- package/PID discovery.

## SQLite
Research and pin the consistency strategy using official SQLite documentation.

Focus:
- WAL;
- backup API;
- `VACUUM INTO`;
- DB/WAL/SHM snapshot semantics;
- `sqlite3_rsync` where applicable.

Never assume copying only `.db` is safe.

## Proxy engine
Evaluate mature embeddable libraries.

Record:
- library/version;
- license;
- HTTP/TLS capabilities;
- callback API;
- body/timing support;
- known limitations.

Current MVP implementation uses a Lynx-owned HTTP/1 proxy and Bouncy Castle
(`org.bouncycastle:bcprov-jdk18on` and `bcpkix-jdk18on`, 1.78.1) for ephemeral
session CA and per-host leaf certificate generation. This keeps protocol models
vendor-neutral and avoids exposing third-party proxy types. HTTPS CONNECT is
intercepted when the debug client trusts the session CA; certificate pinning,
HTTP/2, WebSocket, and QUIC remain explicit limitations.

## Runtime attribution
JVMTI/AOSP App Inspection research is retained only for a later optional attribution layer.
