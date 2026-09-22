# M1.1 verification checkpoints

This file contains sanitized checkpoint summaries. Raw logs, copied databases,
certificates and traffic remain under ignored `build/verification/` directories.

| Ticket | Commit | Android | iOS Simulator | Database | HTTP/1 | HTTP/2 | WebSocket | Cleanup | Status |
|---|---|---|---|---|---|---|---|---|---|
| M1.1-P0 | `2a333e8` / `checkpoint/m1-1/P0-proxy-recovery` | pass | pass | pass | pass | pass | pass | start/stop, forced worker death, detach passed | pass |
| M1.1-00 | `0e800e8` | pass | pass | pass | pass | pass | pass | restored | pass; committed harness run `committed-20260922-174017` |
| M1.1-01 | pending | pass: API 17 emulator, shell relay and `run-as` FD/inode proof | pass: booted iOS 26.4 Simulator, libproc tuple/process proof | pass | pass | pass | pass | Android proxy/reverse/helper and macOS proxy restored | pass for recorded emulator/simulator scope; API 26, physical Android, PID-race and IPv6 device rows pending |

Use `pass`, `fail`, `blocked`, or `not_run`; never replace a missing live row
with an old result or a local fixture.

## M1.1-01 pre-commit evidence

- Native/JVM gate passed on 2026-09-22:
  `:core:model:macosArm64Test`, `:host:native:macosArm64Test`,
  `:host:network:macosArm64Test`, `:apps:cli:macosArm64Test`, and native
  executable linking.
- Android 17 emulator: unchanged sample WSS completed with HTTP 101 and echoed
  `lynx-sample-ping` through a temporary shell-owned relay. Before forwarding,
  the relay joined the loopback tuple to the attached app's `run-as` socket inode
  and emitted `verified_target`. A shell-UID connection to the same remote
  endpoint resolved `unknown`; it neither read nor retained payload bytes.
- iOS 26.4 Simulator: the unchanged SampleApp WSS peer tuple resolved through
  `proc_pidinfo`/`proc_pidfdinfo` to the simulator-container executable. A host
  curl control against that same remote endpoint did not change the SampleApp
  ownership result. Neither probe read application payloads.
- Full legacy V0 regression passed in
  `build/verification/m1-1/01/precommit-20260922-223841`; raw traffic and copied
  databases remain ignored. The run restored Android proxy `:0`, removed the
  owned reverse mapping/helper, and restored macOS web and secure-web proxies.
