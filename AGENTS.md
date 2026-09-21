# Lynx contribution guide

Lynx is an agent-native, headless Android Network and Database Inspector.
This file is a portable entrypoint for any coding agent or human contributor.

## Read first

1. `lynx-spec/README.md`
2. `lynx-spec/FEATURE_REQUIREMENTS.md`
3. `lynx-spec/ARCHITECTURE.md`
4. `lynx-spec/IMPLEMENTATION.md`
5. `lynx-spec/ROADMAP.md`
6. `lynx-spec/AGENT_EXECUTION.md`
7. `lynx-spec/UPSTREAM.md`

## Non-negotiable MVP boundaries

- Debuggable Android applications only.
- ADB and the injected runtime are the only attachment mechanism.
- macOS is the first host platform.
- The CLI and initial TUI are read-only for database operations.
- Network coverage matches the supported Android Studio collectors and is
  reported explicitly; do not claim arbitrary-library coverage.
- Independent CLI invocations share state through a local host service in MVP.
- AOSP protocol types remain behind adapters and never become public models.

## Working agreement

Investigate, record evidence and upstream provenance, write a focused failing
test, implement the smallest slice, run the relevant checks, and report exact
validation results. Do not combine roadmap phases in one patch.

The canonical agent workflow is `lynx-spec/AGENT_EXECUTION.md`; vendor-specific
editor or harness files, if added later, must point to that source rather than
duplicating policy.
