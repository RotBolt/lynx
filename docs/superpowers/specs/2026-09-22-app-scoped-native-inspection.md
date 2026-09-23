# App-scoped native inspection contract

Status: implemented in native CLI commit `922b10a`; retained as the contract and
verification reference for the M1.1-09 checkpoint.
Date: 2026-09-22.
Applies to the native `lynx` executable. The JVM compatibility backend must keep passing its existing tests.

## Required outcome

`network snapshot` and `network list --session=<id>` return only traffic whose
origin has been verified as the application and device attached to that capture.
The attached PID, a hostname, a User-Agent, or a capture timestamp alone is not
proof of ownership. Independent CLI invocations share the same capture state.

The sample applications remain unchanged. Their HTTP/1.1, HTTP/2, and WebSocket
actions continue to call real public services using ordinary clients. No VPN,
device rooting, application proxy code, or runtime code injection is required.
Debug CA trust configuration remains a prerequisite for target HTTPS capture.

## Evidence and remaining feasibility work

Repository reference: `43b0f2d887c210fcd7f060f010c5a232a45e1792`.
This is the last recorded implementation commit, not a fresh baseline pass.

Read-only investigation and temporary probes established:

- On the connected Android 17/API 37 emulator, `ro.build.type=user`,
  `ro.debuggable=0`, and SELinux was enforcing. ADB shell could read TCP ownership
  tables; `run-as` could inspect the debuggable application's file descriptors.
- A controlled TCP client under sample-app UID 10229 owned inode 197659/PID
  24725; a shell-UID client to the same listener was distinguishable. This was a
  socket-ownership control, not an actual sample-app capture through a relay.
- The unchanged iOS Simulator sample app's real WebSocket connection resolved
  to PID 20639 and `127.0.0.1:59596 -> 127.0.0.1:62006`. A temporary pass-through
  proxy distinguished that socket from other host connections without TLS MITM.
- Temporary probe processes exited. No production ownership filtering exists yet.

The first implementation gate must prove the complete Android relay path with
the real sample application, plus iOS ownership before MITM. Permissions and
timing must be tested on additional supported Android versions/devices before
claiming broad compatibility. Physical iOS attribution is outside this method.

## Target and origin identities

An attachment identifies platform, device serial or simulator UDID, application
ID, and Android user/profile where applicable. A capture has a separate ID from
the attachment. Multiple sequential captures can belong to one attachment.

An origin records the verified device/application, PID, process start identity,
Android UID where applicable, and ownership method. PID/start identity prevents
PID reuse from assigning a new process to an old connection. Shared UIDs alone
are insufficient; shared processes or delegated system-service flows without
an application ownership proof remain unresolved.

The ownership decision is one of `verified_target`, `verified_other`, or
`unknown`. It is made before certificate generation or target request capture.
The capture context is immutable for the connection; every HTTP/2 stream and
WebSocket frame inherits it. Later attaches cannot relabel an existing flow.

- Verified target: intercept and persist complete exchanges, including failures.
- Verified other: forward normally, with HTTPS tunneled using the origin's TLS.
- Unknown: forward without interception or request/body retention, increment a
  diagnostic counter, and report that attribution coverage is incomplete/unknown.
- Resolver unavailable at startup: fail with `APP_ATTRIBUTION_UNAVAILABLE` and
  restore any partially acquired resources. Never fall back to broad capture.

Counters do not estimate how many unknown connections belonged to the target.
Only positive ownership evidence permits an exchange into application results.
Unrelated TLS traffic must not encounter Lynx-issued leaf certificates.

## Android mechanism

Lynx ships a native command-line relay, installed under an owned, session-specific
directory beneath `/data/local/tmp`, running as ADB shell. It is not installed in
the application or packaged as an APK. Initial helper ABIs are `arm64-v8a` and
`x86_64`, built against Android API 26 (the sample app's current minimum).
Unsupported ABIs or unavailable ownership permissions fail preflight explicitly.

The global device proxy points to a loopback listener in this relay. For every
accepted connection, the relay resolves the client's exact TCP tuple to UID and
socket inode, then verifies PID/application membership through `run-as` access.
The lookup occurs on the device before NAT or ADB forwarding loses the peer tuple.
Verified application subprocesses may be included; ambiguous shared/isolated
service ownership must not be guessed.

A dedicated ADB reverse mapping connects the relay to the host. A versioned,
length-prefixed metadata preface carries the connection ID, capture ID, decision,
and origin over that channel, followed by the unchanged proxy byte stream.
The host validates the per-capture token, expected device/channel, and target.
An app-supplied HTTP header can never override attribution. Frame-size bounds
apply to this control preface only, not request/response body retention.

## iOS Simulator mechanism

The host matches the accepted peer tuple against process sockets using
`proc_pidinfo(PROC_PIDLISTFDS)` and `proc_pidfdinfo(PROC_PIDFDSOCKETINFO)`.
Verify executable/container and simulator association as well as PID/start
identity. Normalize IPv4 and IPv6 addresses before tuple comparisons.

The existing host proxy scope can carry other macOS application traffic; the
ownership gate forwards that traffic without MITM or evidence retention. Select
the actual network service, rather than assuming it is always named `Wi-Fi`.
Restore the exact service settings acquired by the capture's lease.

Background URLSession tasks can execute in a system process. Without a separate
delegation proof they are unknown, not target traffic. Physical iOS must report
`APP_ATTRIBUTION_UNSUPPORTED` until a separate verified adapter exists.

## Capture and command lifecycle

One active capture per local Lynx instance in this increment; multiple stored
captures are supported. Concurrent multi-target capture is a separate extension.

| Command | Required behavior |
|---|---|
| `network start` | Require an attached supported target; preflight ownership and tools; create a new capture on a stopped-to-running transition; return `session_id`, `attachment_id`, target, scope, endpoint, and capabilities. |
| Repeated `network start` | If the same target is already running, return the same ID with `already_running=true`; never overwrite the original proxy lease or spawn another worker. |
| `network snapshot` | Return a finite view of the currently running capture, through a reported committed sequence watermark; keep capturing. No new persistent snapshot resource or destructive reset. |
| `network list` | Return capture-session summaries only: IDs, target, state, times, and exchange/failure/in-flight counts. No request bodies. |
| `network list --session=<id>` | Return that capture's verified target exchanges; support both `--session=id` and `--session id`. History remains readable after stop/detach. |
| `network get <request-id>` | Return one complete verified exchange and its capture ID; request IDs are globally unique. |
| `network stop` | Drain briefly, finalize interrupted target work with explicit incomplete/failure state, stop owned workers/relay, restore exact proxy/mappings, retain captured evidence. Idempotent. |
| `detach` | Stop capture and restore its resources before clearing attachment; retain network history. Do not broaden database snapshot access semantics. |

`network snapshot` without a running capture returns `NO_ACTIVE_CAPTURE`.
Unknown session IDs return `CAPTURE_SESSION_NOT_FOUND`, not an empty success.
Changing targets while capturing returns `CAPTURE_ACTIVE`; stop before attaching
another target. An app restart preserves attachment/capture IDs and verifies the
replacement PID; old flows keep the old identity. Worker death marks the capture
`interrupted` and triggers lease recovery rather than reporting `running` forever.

Snapshot/list exchange responses preserve existing request, response, timing,
failure, protocol, and frame fields, and add capture ID, sequence, and verified
attribution. Complete bodies remain available; there is no default result/body
limit. Explicit `--limit` reports returned/matched counts without deleting data.
In-flight target requests are explicitly counted/described; an open WebSocket
must not be represented as a completed empty exchange. Publish handshake and
frames while the connection is open, including ping/pong control frames; closing
the connection is not a prerequisite for snapshot/list/get visibility. Reads
remain finite watermark views. M1.1-WS implements this separate live-capture fix.

The changed native network commands use `schema_version: "lynx.v2"` and stable
types `network_started`, `network_snapshot`, `network_sessions`, `network_list`,
`network_get`, `network_stopped`, `network_doctor`, and `error`. Database command
syntax/output is preserved in this work. Existing unscoped logs are preserved
but cannot be upgraded into verified history or exposed by the new scoped APIs.
Update consumers and the bundled skill in the same CLI-cutover commit.

## Discovery and onboarding

Resolve tools to absolute executables, validate usability, and use that resolver
consistently for devices, attach, network, and database paths. Search explicit
Lynx configuration, SDK environment variables, PATH, and conventional install
locations; handle spaces and invalid candidates. On macOS inspect the selected
Xcode with `xcode-select`/`xcrun --find simctl`; merely finding `/usr/bin/xcrun`
does not prove an iOS runtime or full Xcode installation is available.

`devices` aggregates Android devices and available iOS simulators on macOS.
Return stable IDs, name, platform, kind, availability/boot state, and current
attachment/capture status. Include unavailable/offline/unauthorized states.
One provider failing must not hide devices from the other provider. Physical
iOS discovery may be reported as unsupported; it must not appear attachable.

`doctor` exposes tool paths, versions, and actionable diagnostics. First-run
interactive output displays ticks/warnings on stderr, keeping stdout clean.
`--json` is noninteractive and produces only the command's JSON on stdout;
discovery diagnostics are machine-readable through `doctor`/`devices`.
Revalidate paths on each invocation; cache only first-run display state.
Discovering a standard SDK path must not require editing shell profiles.
When environment setup is necessary, print the matching zsh/bash profile lines;
never edit the user's shell profile automatically. Xcode is not applicable on
Linux, rather than an installation failure there.

## Source references

- Android shell socket access: https://android.googlesource.com/platform/system/sepolicy/+/refs/heads/main/private/shell.te
- Android debuggable-process inspection: https://android.googlesource.com/platform/system/sepolicy/+/refs/heads/main/private/runas_app.te
- macOS socket inspection: https://github.com/apple-oss-distributions/xnu/blob/main/bsd/sys/proc_info.h
- Delegated iOS transfers: https://developer.apple.com/documentation/foundation/urlsessionconfiguration/background(withidentifier:)

These are research references fetched on 2026-09-22. They are not reproducible
toolchain pins; record NDK/compiler versions and tested OS builds in execution evidence.
