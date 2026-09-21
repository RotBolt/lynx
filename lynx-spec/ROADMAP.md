# Build Roadmap

## Phase 0 — Foundation
- CLI skeleton;
- session model;
- Evidence Timeline;
- ADB abstraction;
- session-owned local retention with explicit storage-exhaustion errors.

## Phase 1 — HTTP proxy vertical slice
Capture complete request/response exchanges (including failures) and print JSONL.

Gate:
- success + failure request;
- stable ID;
- proxy restored.

## Phase 2 — HTTPS + diagnostics
- CA lifecycle;
- HTTPS capture;
- `lynx network doctor`;
- explicit trust/pinning/proxy-bypass failures.

## Phase 3 — Network MVP
- start/stop/watch;
- list/get;
- filters;
- headers;
- complete retrievable bodies;
- timings;
- history.

Product gate: agent can inspect a complete failed past request after
reproduction, and can distinguish unsupported/bypassed traffic from an empty
capture.

## Phase 4 — DB discovery
- ADB + `run-as`;
- discover Room/direct SQLite DBs.

## Phase 5 — Consistent SQLite snapshot spike
Evaluate point-in-time snapshot methods under concurrent writes.

Hard gate:
- snapshot opens reliably;
- committed rows correct;
- no malformed/inconsistent snapshots.

## Phase 6 — DB MVP
- snapshot;
- tables;
- schema;
- query;
- snapshot history.

## Phase 7 — DB watch
- fingerprint;
- safe snapshot on change;
- bounded query/watch stream.

## Phase 8 — DB diff
- schema diff;
- keyed table/query diff.

## Phase 9 — Merged timeline
```bash
lynx timeline --since 30s --json
```

Example:
```text
12:00:01.010 req_42 started
12:00:01.140 req_42 completed 200
12:00:01.380 app.db changed -> snap_13
```

## Phase 10 — TUI
Network + DB + merged timeline views.

## Phase 11 — Runtime attribution
Optional JVMTI call stacks/source/thread enrichment.

Success criterion:
No foundational Network/DB public API changes.

## Phase 12 — iOS/other sources
Reuse proxy core, SQLite engine, and timeline.

## Do not build early
- Android Studio inspectors;
- JVMTI;
- MCP;
- Layout Inspector;
- request mocking;
- DB writes;
- generic plugin system.
