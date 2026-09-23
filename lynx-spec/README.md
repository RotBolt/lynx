# Lynx

> Agent-native runtime evidence for debuggable mobile applications.

## Product

Lynx is a standalone CLI that captures **network history** and **SQLite database
state/history** from debuggable Android applications and iOS Simulators and
exposes that evidence as structured output for developers and AI agents.

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

1. Debuggable Android apps and iOS Simulators; physical iOS remains planned.
2. **Network foundation = proxy capture.**
3. **Database foundation = SQLite snapshot/observer.**
4. No Android Studio runtime dependency.
5. No AndroidX App Inspection dependency in MVP.
6. No JVMTI in the foundational Network/DB path.
7. JVMTI may be added later only for attribution/call stacks.
8. Public CLI schemas belong to Lynx.
9. The Evidence Timeline is a first-class abstraction from day one.
10. Network/DB evidence remains queryable for the owning attachment session.

The foundational host layer is source-independent: network proxy events and
SQLite snapshots publish canonical evidence into a bounded Evidence Timeline.
Proxy and SQLite implementations are adapters behind collector interfaces;
JVMTI/ART is not required for either path.

## Core workflows

```bash
lynx attach emulator-5554 com.example.app --json

lynx network start --json
lynx network snapshot --json
lynx network list --session <session_id> --json
lynx network get <request_id> --json

lynx db list --platform android --device emulator-5554 --package com.example.app --json
lynx db snapshot databases/app.db --platform android --device emulator-5554 --package com.example.app --json
lynx db query <snapshot_path> "SELECT * FROM pending_actions" --json
```

For an iOS Simulator, attach with
`lynx attach ios-simulator:<simulator-udid> <bundle-id> --json`, then keep the
same network commands. For databases, select the target with
`--platform ios --simulator <simulator-udid> --bundle-id <bundle-id>`.

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
- HTTP/2 and WebSocket evidence;
- request/response headers;
- complete retrievable bodies within session retention;
- timings;
- filters;
- structured JSON/JSONL;
- explicit CA/pinning/proxy-bypass diagnostics;
- Android and iOS Simulator target attribution.

### Database
- discover SQLite DBs via ADB + `run-as`;
- acquire a point-in-time snapshot including WAL state;
- host-side SQLite query/schema inspection;
- read-only snapshot/table/schema/query inspection.

### Not yet supported
- JVMTI/call stacks;
- Android Studio inspectors;
- Layout Inspector;
- MCP;
- DB writes;
- request mocking.

## Public documentation

- [README](../README.md): installation, workflows, FAQ, and evidence examples.
- [AI-readable map](../llms.txt): concise links for documentation-aware agents.
- [Command reference](../docs/COMMANDS.md): JSON contracts and exact commands.
- [Agent skill](../docs/agent-skill/SKILL.md): vendor-neutral operating procedure.
- [Roadmap](ROADMAP.md): supported and under-construction capabilities.
