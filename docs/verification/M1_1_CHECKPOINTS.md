# M1.1 verification checkpoints

This file contains sanitized checkpoint summaries. Raw logs, copied databases,
certificates and traffic remain under ignored `build/verification/` directories.

| Ticket | Commit | Android | iOS Simulator | Database | HTTP/1 | HTTP/2 | WebSocket | Cleanup | Status |
|---|---|---|---|---|---|---|---|---|---|
| M1.1-P0 | `2a333e8` / `checkpoint/m1-1/P0-proxy-recovery` | pass | pass | pass | pass | pass | pass | start/stop, forced worker death, detach passed | pass |
| M1.1-00 | pending harness commit | pass | pass | pass | pass | pass | pass | restored | pending committed-tree run |

Use `pass`, `fail`, `blocked`, or `not_run`; never replace a missing live row
with an old result or a local fixture.
