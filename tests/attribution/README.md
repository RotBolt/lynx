# Ownership probe artifacts

These probes support M1.1-01, the feasibility gate before production app
attribution adapters are introduced. They are diagnostic-only and never read,
retain, decrypt, or forward application request or response bodies.

`macos-owner-probe.c` accepts an explicit PID and numeric remote tuple. It uses
`PROC_PIDLISTFDS` and `PROC_PIDFDSOCKETINFO` to report a versioned JSON ownership
observation containing the process start identity, UID, peer tuple, match result,
and lookup duration. A match means only that the specified process currently
owns the TCP tuple; simulator container and executable validation remain required
before any production decision can be made.

`android-owner-probe.c` is the bounded relay probe entrypoint. The relay must
run as Android's ADB `shell` user, not in the app context: the shell user can
read `/proc/net/tcp*`, while `run-as <attached-package>` can enumerate only the
attached app process's socket inodes. The probe joins those two metadata sources
using a validated package name, PID/start identity, inode, and normalized peer
tuple. Neither source is sufficient alone. This is intentionally diagnostic
until it has passed device-backed positive, negative, restart, race, IPv4 and
IPv6 controls through the real sample app.

The host verifier searches the standard macOS SDK location or
`ANDROID_NDK_HOME`; it never downloads an NDK. The NDK is contributor-only: a
release would bundle the prebuilt ABI-specific relay instead.

Run `scripts/verify-attribution-probes.sh <output-directory>` to compile and
self-check the macOS probe. Its Android result is explicitly `blocked` when the
NDK is unavailable, rather than treating a host-only check as Android evidence.
