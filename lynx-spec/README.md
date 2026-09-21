# Lynx

> Agent-native runtime evidence for debuggable mobile applications.

## Product

Lynx is a standalone CLI and optional TUI that captures **network history** and **SQLite database state/history** from debuggable Android applications and exposes that evidence as structured output for developers and AI agents.

```text
running debuggable Android app
        │
        ├── HTTP/HTTPS ──> Lynx Proxy ──> Network Evidence
        │
        └── private DB ──> SQLite Observer ──> Database Evidence
                                      │
                                      ▼
                               Evidence Timeline
                                      │
                          CLI / JSON / JSONL / TUI

                         optional later enrichment
                                      │
                                      ▼
                           Runtime Attribution
                           JVMTI / call stacks
```

## Foundational decisions

1. Debuggable Android apps only.
2. **Network foundation = proxy capture.**
3. **Database foundation = SQLite snapshot/observer.**
4. No Android Studio runtime dependency.
5. No AndroidX App Inspection dependency in MVP.
6. No JVMTI in the foundational Network/DB path.
7. JVMTI may be added later only for attribution/call stacks.
8. Public CLI schemas belong to Lynx.
9. The Evidence Timeline is a first-class abstraction from day one.
10. Network/DB history must remain queryable after reproduction within retention limits.

The foundational host layer is source-independent: network proxy events and
SQLite snapshots publish canonical evidence into a bounded Evidence Timeline.
Proxy and SQLite implementations are adapters behind collector interfaces;
JVMTI/ART is not required for either path.

## Core workflows

```bash
lynx attach com.example.app

lynx network start
lynx network list --since 2m --status 400..599 --json
lynx network get req_42 --json

lynx db list --json
lynx db snapshot app.db
lynx db query app.db "SELECT * FROM pending_actions" --json
lynx db diff snap_10 snap_11 --json

lynx timeline --since 30s --json
```

The agent should be able to reason:

```text
POST /users -> 200
response contains userId=42
DB snapshot after request has no userId=42
=> likely persistence/application-side failure
```

## MVP

### Network
- start/stop proxy capture;
- HTTP/HTTPS request history;
- request/response headers;
- complete retrievable bodies within session retention;
- timings;
- filters;
- structured JSON/JSONL;
- explicit CA/pinning/proxy-bypass diagnostics.

### Database
- discover SQLite DBs via ADB + `run-as`;
- acquire a point-in-time snapshot including WAL state;
- host-side SQLite query/schema inspection;
- snapshot history;
- DB watch;
- snapshot/table/query diff.

### Not MVP
- JVMTI/call stacks;
- Android Studio inspectors;
- Layout Inspector;
- iOS;
- MCP;
- DB writes;
- request mocking.
