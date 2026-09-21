# Codex Execution Guide

## Non-negotiable architecture

1. Debuggable Android apps only.
2. Network foundation is proxy capture.
3. Database foundation is SQLite snapshot/observer.
4. No Android Studio/App Inspection runtime in foundational phases.
5. No JVMTI in foundational phases.
6. JVMTI/call stacks are optional later enrichment.
7. Evidence Timeline is shared foundation.
8. CLI JSON/JSONL is the primary public API.
9. TUI uses shared services.
10. Network/DB public schemas are source-independent.

## Task order

1. Repository/session/timeline skeleton.
2. Embedded proxy HTTP spike.
3. Android proxy configure/restore.
4. HTTPS CA + `network doctor`.
5. Network history/list/get/filter.
6. ADB + `run-as` DB discovery.
7. Consistent SQLite snapshot spike under concurrent writes.
8. DB query/schema/history.
9. DB watch/diff.
10. Merged timeline.
11. TUI.
12. Optional JVMTI attribution.

## Hard escalation triggers

Stop and report before changing architecture if:
- proxy capture is impractical for intended debug workflow;
- HTTPS trust/pinning makes the default design unacceptable;
- safe SQLite snapshots cannot be acquired under concurrent writes;
- a foundational feature appears to require Android Studio App Inspection;
- JVMTI appears necessary for basic evidence rather than optional attribution.

Do not silently switch architecture.

## Avoid premature abstractions

Do not build early:
- generic inspector plugins;
- universal mobile debugger;
- JVMTI runtime;
- MCP;
- Layout/iOS.
