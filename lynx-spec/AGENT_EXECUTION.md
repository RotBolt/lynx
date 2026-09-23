# Lynx Agent Execution Guide

This is the vendor-neutral execution guide for any coding agent or harness.

## Architecture boundaries

1. Debuggable Android apps and iOS Simulators from a macOS host; physical iOS
   remains under construction.
2. Network evidence is proxy-captured and database evidence is SQLite-snapshot based.
3. Android Studio/App Inspection and JVMTI are not foundational dependencies.
4. The Evidence Timeline is shared by all collectors.
5. CLI JSON/JSONL is the machine-facing API; schemas are source-independent.

## Delivery order

1. Session, timeline, and versioned daemon protocol.
2. HTTP proxy capture and Android proxy restoration.
3. ADB/run-as database discovery and WAL-aware snapshots.
4. Read-only database query/schema APIs.
5. Network/database JSON and JSONL retrieval.
6. Session restart rebinding and end-to-end lifecycle cleanup.
7. HTTPS diagnostics and later transport coverage.

## Agent workflow

Investigate the relevant spec and existing tests, add a focused failing test,
implement the smallest slice, run targeted and full checks, and report exact
validation results. Preserve vendor-neutral interfaces and explicit capability
limitations. For public usage, start with `README.md`, `llms.txt`, and
`docs/agent-skill/SKILL.md`; do not infer support from historical plans.
