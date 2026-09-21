# Lynx Milestones

## M1 — Agent-Debuggable Network and Database MVP

**Goal:** An AI agent can attach to a debuggable Android app, observe routed
HTTP/HTTPS traffic, inspect complete request/response data, discover SQLite
databases, create validated snapshots, and run read-only SQL through stable
JSON/JSONL APIs.

**Target outcome:**

```text
attach → network start → reproduce → network get/list
attach → db list → snapshot → tables/schema/query
```

**In scope:**

- macOS host;
- ADB device/package/PID resolution;
- session continuity across app PID restarts;
- Android System Proxy apply/restore;
- transparent Android capture for clients that bypass the system proxy;
- HTTP/1.1 capture;
- HTTPS CONNECT/TLS MITM with first-run CA onboarding;
- HTTP/2 capture;
- WebSocket handshake and frame capture;
- Android emulator/device and iOS Simulator/device trust guidance;
- complete request/response retrieval;
- SQLite discovery and validated snapshots;
- snapshot-addressed read-only SQL;
- multiple read-only statements;
- 15-minute query deadline;
- JSON and JSONL agent contracts;
- session-owned retention without semantic truncation.

**Out of scope:**

- JVMTI/ART attribution;
- Android Studio/App Inspection dependencies;
- database writes;
- request mocking;
- iOS;
- MCP;
- security hardening beyond local functional behavior;
- offline snapshot queries after detach;
- QUIC/HTTP3 capture.

**Exit criteria:**

1. A real debuggable app can be attached without a supplied/fake PID.
2. App process restart preserves `session_id` and rebinds the new PID.
3. A routed HTTP request is stored and retrievable as structured JSON.
4. HTTPS support reports explicit trust/pinning/unsupported outcomes and exposes CA onboarding.
5. HTTP/2 and WebSocket exchanges are retrievable as structured evidence.
6. Database discovery returns stable `database_id` values.
7. A validated snapshot returns a stable `snapshot_id`.
8. Tables, schema, and complete query results are retrievable by snapshot ID.
9. Mutating SQL is rejected and read-only statements can run for up to 15 minutes.
10. Detach stops collectors, restores proxy state, and invalidates snapshot access.
11. Full automated tests and a manual emulator/app smoke test pass.

## M2 — Durable Evidence and Advanced Inspection

- persistent evidence/session storage;
- explicit retention configuration and export/import;
- database watch and diffs;
- merged timeline queries;
- richer network filters and body artifact streaming;
- TUI views.

## M3 — Optional Runtime Attribution

- JVMTI/call-stack enrichment;
- network/DB call-site correlation;
- thread/source metadata;
- no changes to foundational Network/DB public schemas.
