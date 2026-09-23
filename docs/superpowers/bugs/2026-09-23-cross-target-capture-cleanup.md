# Bug: iOS capture commands reuse an Android capture target

**Status:** Fixed in checkpoint `4d77d95` (`checkpoint/m1-1/09a-cross-target-cleanup`).

**Severity:** High — iOS snapshot returned stale/empty Android data and stop/detach
attempted to call ADB with an iOS simulator UDID.

## Symptoms

- `lynx network snapshot --json` while attached to iOS could return an older
  active capture created for Android, including the wrong session target.
- `lynx network stop --json` failed with `adb: unknown host service
  '<simulator-udid>:features'`.
- `lynx detach --json` surfaced the same cleanup failure because detach stops the
  network capture before clearing the attachment.

## Cause

The persisted capture and the current attachment are separate pieces of state.
Cleanup selected the current attachment's device rather than the immutable
device recorded in the active capture. When the current attachment was an iOS
simulator, the Android cleanup path received `ios-simulator:<UDID>` and invoked
ADB. Snapshot also lacked a target-consistency check, so stale capture state was
reported as if it belonged to the current attachment.

## Fix contract

- A capture target is immutable for its lifetime.
- `network snapshot` and repeated `network start` reject a different attached
  device/application with `CAPTURE_TARGET_MISMATCH`/`CAPTURE_ACTIVE`.
- `network stop` and `detach` resolve cleanup from persisted capture metadata.
  Android relay/ADB cleanup uses the recorded Android serial; iOS cleanup uses
  the macOS proxy lease and never invokes ADB for an iOS UDID.
- Capture evidence remains readable after stop, but an active snapshot is only
  valid for its matching attachment.

## Verification

- Focused native lifecycle tests cover an Android capture followed by an iOS
  attachment switch; they assert correct ADB target selection and mismatch
  rejection.
- Real iOS Simulator smoke captured HTTP/1.1, HTTP/2, and WebSocket traffic,
  then completed `network stop` and `detach` without the ADB unknown-host error.
