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
| M1.1-06 | pending | pass: real sample H1/H2/WSS and DB validation | pass: real sample H1/H2/WSS and DB validation | source/snapshot rows matched on both platforms | pass | pass | pass | Android proxy `:0`; macOS web and secure-web proxies restored | candidate verification recorded; checkpoint follows committed-tree gate |
| M1.1-07 | `8ed413c` / `checkpoint/m1-1/07-ios-scope` | pass: legacy Android regression | pass: scoped iOS H1/H2/WSS and foreign pass-through | source/snapshot rows matched on both platforms | pass | pass | pass | Android proxy `:0`; macOS web and secure-web proxies restored | pass; Android ownership remains M1.1-08 |
| M1.1-08 / WS | `08eb040` | pass: relay transport and live WSS frames | pass: relay-independent iOS scope | source/snapshot rows matched on both platforms | pass | pass | pass | Android proxy `:0`, no reverse mappings; macOS lease restored | pass; manual Android live WSS evidence |
| M1.1-09 | `922b10a` / `checkpoint/m1-1/09-scoped-cli` | pass: session-scoped H1/H2/WSS (manual live relay evidence) | pass: committed-tree session-scoped H1/H2/WSS | source/snapshot rows matched on both platforms | pass | pass | pass | Android proxy `:0`, no reverse mappings; macOS lease restored | pass; v2 CLI cutover and repository wiring verified |
| M1.1-10 | `339a616` / `checkpoint/m1-1/10-macos-release-ready` | pass: extracted archive H1/H2/WSS + DB | pass: extracted archive H1/H2/WSS + DB | Android 9,413 rows; iOS 249 rows | pass | pass | pass | Android proxy `:0`, no reverse mappings; simulator proxy restored | pass; macOS archive verified outside repo |

Use `pass`, `fail`, `blocked`, or `not_run`; never replace a missing live row
with an old result or a local fixture.

## M1.1-09 committed-tree evidence

- Commit `922b10a` passed the full committed-tree gate: model, native host,
  network, CLI tests, and macOS release executable link.
- The native CLI now emits `lynx.v2`: `network list` returns session catalog;
  `network snapshot` and `network list --session <id>` return finite,
  app/device-attributed exchanges; missing/invalid sessions return structured
  errors. Filtered list requires an explicit session.
- Android live relay evidence captured HTTP/1.1, HTTP/2, and an open WebSocket
  (HTTP 101 plus client/server ping frames) before close. Records carried the
  capture session ID and `attribution.status=verified`.
- Post-commit iOS smoke captured HTTP/1.1 200, HTTP/2 200, and WebSocket 101
  with echoed ping and live frame visibility. Evidence:
  `build/verification/m1-1/09/ios-live-20260923-0314/summary.txt`.
- Android cleanup was verified after the post-commit run: global proxy `:0`
  and no reverse mappings. A later Android harness retry was blocked by the
  currently installed `dev.lynx.dummyapp` trust state, not by CLI/session
  assertions; existing manual relay evidence remains the network proof.

## M1.1-10 committed archive evidence

- Commit `339a616` passed the full native/JVM/macOS release-link gate. Fresh
  relay helpers rebuilt with NDK `30.0.16248370`.
- Archive:
  `build/verification/m1-1/10/lynx-macos-arm64-committed.tar.gz`
  SHA-256: `b319a948a5a700915409d172365b2d25d6ee7c83207dcf289ac5f6d0d0ed223d`.
- Archive extracted under `/tmp`, then invoked from `/tmp`; `lynx --version`,
  `doctor --json`, `devices --json`, manifest verification, and bundled relay
  auto-discovery passed with `LYNX_ANDROID_RELAY_BINARY` unset.
- Committed archive Android smoke: real sample-app HTTP/1.1 200, HTTP/2 200,
  and WebSocket 101 with echoed ping and verified attribution. One WSS attempt
  timed out at the public endpoint; immediate retry passed.
- Committed archive iOS Simulator smoke: real sample-app HTTP/1.1 200, HTTP/2
  200, and WebSocket 101 with echoed ping and verified attribution.
- Committed archive database parity: Android `databases/dummyapp.db` source vs
  snapshot matched at 9,413 rows; iOS `Documents/dummyapp.db` matched at 249
  rows; tables and read-only query checks passed.
- Package verifier rejected mutated archives for missing skill, relay hash, and
  relay protocol. Android finished at proxy `:0` with no reverse mappings.

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

## M1.1-06 pre-commit evidence

- Native certificate tests include 32 concurrent leaf issuances, certificate
  parse validation, and actionable failure-stage mapping. The full native/JVM
  build and macOS executable link gate passed on 2026-09-23.
- Full V0 evidence is in `build/verification/m1-1/06/precommit-final-20260923-062936`.
  The unchanged Android and iOS sample applications each completed real HTTPS
  HTTP/1.1, HTTP/2, and WSS exchanges; database source and snapshot query rows
  matched. Android ended at proxy `:0`; macOS web and secure-web proxy settings
  matched before and after. The harness received the Android SDK platform-tools
  directory only as a temporary PATH prefix; no shell configuration changed.

## M1.1-07 pre-commit evidence

- The macOS libproc bridge resolves the current simulator app container, PID
  start identity, and exact proxy tuple before TLS interception. A live probe
  independently returned `verified_target` for the unchanged sample socket.
- Scoped iOS smoke passed real HTTP/1.1, HTTP/2, and WebSocket exchanges with
  readable bodies/frames. A host curl through the same proxy completed without
  the Lynx CA and produced zero retained exchanges for its unique URL. Foreign
  and unknown iOS admission failures are not retained as synthetic exchanges.
- The V0 Android+iOS network and database gate passed at
  `build/verification/m1-1/07/precommit-final-20260923-0745`; Android uses its
  existing broad path until the dedicated relay ticket M1.1-08.
