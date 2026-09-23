# iOS snapshot and cleanup regressions

## Reported behavior

- `lynx network snapshot --json` on an attached iOS Simulator returned a valid
  `network_snapshot` envelope with no exchanges, even while the sample app was
  expected to be producing traffic.
- `lynx network stop --json` on an iOS attachment failed with an ADB host
  service error containing the simulator UDID.
- `lynx detach --json` failed with the same cleanup error because detach stops
  the active network capture before clearing the attachment.

## Contract

- Snapshot is an active-capture read. It must return the capture session and
  committed exchanges for the attached app; an empty result is valid only when
  no target exchange has been observed and must not be used as evidence that
  the proxy is capturing.
- iOS cleanup is host-proxy cleanup. It must never call ADB, Android relay
  teardown, or an ADB global-proxy restore for an iOS capture, even if a stale
  persisted proxy record says `android-relay`.
- `detach` must be idempotent after network cleanup and must leave the
  simulator's host proxy state restored.

## Verification

1. Start a fresh iOS Simulator attachment and capture.
2. Generate HTTP/1.1, HTTP/2, and WebSocket traffic before reading snapshot.
3. Assert snapshot has the active session ID, verified target attribution, and
   non-empty exchanges; also test the no-traffic case separately.
4. Stop and detach with the iOS attachment still selected. Assert no `adb`
   invocation and successful cleanup.
5. Repeat Android stop/detach and database checks to protect the existing
   checkpoint.

## Status

The ADB-routing cleanup fix is implemented in the isolated worktree with a
regression test. Live iOS snapshot verification remains a required gate before
the ticket is checkpointed.
