# M1.1 verification checkpoints

This file contains sanitized checkpoint summaries. Raw logs, copied databases,
certificates and traffic remain under ignored `build/verification/` directories.

| Ticket | Commit | Android | iOS Simulator | Database | HTTP/1 | HTTP/2 | WebSocket | Cleanup | Status |
|---|---|---|---|---|---|---|---|---|---|
| M1.1-P0 | `2a333e8` / `checkpoint/m1-1/P0-proxy-recovery` | pass | pass | pass | pass | pass | pass | start/stop, forced worker death, detach passed | pass |
| M1.1-00 | `0e800e8` | pass | pass | pass | pass | pass | pass | restored | pass; committed harness run `committed-20260922-174017` |
| M1.1-01 | pending | pass: API 17 emulator, shell relay and `run-as` FD/inode proof | pass: booted iOS 26.4 Simulator, libproc tuple/process proof | pass | pass | pass | pass | Android proxy/reverse/helper and macOS proxy restored | pass for recorded emulator/simulator scope; API 26, physical Android, PID-race and IPv6 device rows pending |
| M1.1-02 | pending | pass: API 17 emulator database snapshot | pass: booted iOS 26.4 Simulator database snapshot | source/snapshot rows matched on both platforms | pass | pass | pass | Android proxy `:0`, no reverse mappings, macOS web and secure-web proxies restored | pass; tool resolution and subprocess diagnostics covered by native tests |
| M1.1-03 | pending | pass: API 17 emulator listed as attachable | pass: booted iOS 26.4 Simulator listed as attachable; shutdown simulators retained but not attachable | source/snapshot rows matched on both platforms | pass | pass | pass | Android proxy `:0`, no reverse mappings, macOS web and secure-web proxies restored | pass for macOS discovery; Linux iOS is explicitly not applicable |
| M1.1-04 | pending | pass: legacy DB/protocol regression | pass: legacy DB/protocol regression | source/snapshot rows matched on both platforms | pass | pass | pass | Android proxy `:0`, no reverse mappings, macOS web and secure-web proxies restored | pass; versioned verified-capture repository internal until lifecycle/cutover |

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

## M1.1-02 pre-commit evidence

- Tool-resolution and process tests passed on 2026-09-22, including SDK, PATH,
  platform-default and Homebrew candidate ordering; explicit invalid overrides;
  literal argument preservation; separated stderr; and exit code `7`.
- Full legacy V0 regression evidence is in
  `build/verification/m1-1/02/20260922-225241`. Android and iOS source/snapshot
  database query rows matched. Both Android and iOS WebSocket smoke runs emitted
  HTTP 101 and echoed `lynx-sample-ping`; raw capture material stays ignored.
- After the live run, Android proxy was `:0`, no ADB reverse mappings remained,
  and macOS web and secure-web proxy before/after files matched.

## M1.1-03 pre-commit evidence

- Native inventory tests cover Android online/unauthorized/offline rows,
  independent provider diagnostics, exact attachment joins, and clean JSON
  device/doctor responses.
- Live `devices --json` and `doctor --json` passed with the Android 17 emulator,
  booted iOS 26.4 Simulator, shutdown simulator rows, and resolved ADB/Xcode/
  simctl/sqlite3/OpenSSL paths. Evidence is in
  `build/verification/m1-1/03/20260922-230110`.
- Full V0 again matched Android/iOS source and snapshot query rows, completed
  both WebSocket smokes, restored macOS web/secure-web proxies, left Android at
  proxy `:0`, and left no ADB reverse mappings.

## M1.1-04 pre-commit evidence

- Repository tests cover A/B isolation, immutable origin/target validation,
  monotonic per-session watermarks, a post-snapshot completion, a 2 MiB body,
  explicit complete-record corruption, and serialized append sequencing.
- Full V0 evidence is in `build/verification/m1-1/04/20260922-230857`.
  Android/iOS source and snapshot query rows matched; both WebSocket smokes
  passed. Android finished at proxy `:0` with no reverse mappings; macOS web
  and secure-web proxy snapshots matched before/after.
