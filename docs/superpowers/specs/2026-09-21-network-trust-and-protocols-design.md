# Lynx Network Trust and Protocol Support Design

## Goal

Make first-run HTTPS setup understandable and repeatable, automate certificate
installation where the host platform permits it, and extend capture coverage to
HTTP/1.1, HTTP/2, and WebSocket traffic.

## Trust lifecycle

Lynx creates one host-managed CA on first network setup and stores it under the
Lynx configuration directory. A CA is never silently installed or trusted.
`network ca show` prints its fingerprint, PEM path, and platform-specific
installation instructions; `network ca install` performs only the automatable
portion and reports any user confirmation still required. `network ca remove`
removes Lynx-managed trust material where the platform allows it.

Android adapters stage the certificate and launch the user certificate flow.
Debuggable applications must opt into user CAs through Network Security Config.
System-store installation is optional and only attempted when the emulator or
managed device explicitly permits it.

iOS Simulator adapters use `xcrun simctl keychain add-root-cert`; physical iOS
devices receive a configuration profile or install URL and require user trust
confirmation. Lynx reports these states instead of claiming silent trust.

## Proxy protocols

The proxy boundary remains Lynx-owned. The engine adapter must support HTTP/1.1,
TLS MITM, HTTP/2 over TLS, and WebSocket upgrade/frame forwarding. QUIC/HTTP3
is explicitly reported unsupported because it does not traverse the ordinary
HTTP system proxy path. Engine-specific request and frame models remain private
to the adapter; public evidence models use versioned protocol fields.

MVP capture uses the same terminal-controlled system-proxy model as Proxyman and
Charles: Lynx configures the target's Android proxy settings and restores them
on stop/detach. No VPN service, companion app, or application source change is
part of the MVP. A client that deliberately opens a direct socket and ignores
the Android proxy is outside this capture boundary and must be reported by
`network doctor`; it is not silently presented as captured.

## Onboarding and diagnostics

The first `network start` for a host prints a short onboarding message and the
CA fingerprint. `network doctor` returns proxy state, CA state, app trust
guidance, protocol capabilities, and actionable failure reasons. All setup and
cleanup operations are idempotent and restore the prior proxy configuration.

## Acceptance

- A new host can display CA instructions without starting capture.
- Android emulator and iOS Simulator installation paths are automated where
  supported and expose user-confirmation steps otherwise.
- HTTP/1.1, HTTP/2, and WebSocket requests are captured with failures retained.
- HTTPS trust failures identify the missing trust/configuration rather than
  appearing as an empty capture.
- QUIC/HTTP3 is reported as unsupported.
