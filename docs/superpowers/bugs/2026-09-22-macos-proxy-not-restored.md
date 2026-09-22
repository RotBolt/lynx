# Bug: macOS system proxy remains enabled after Lynx capture stops

**Status:** Open

**Implementation tracking:** [M1.1-P0 priority recovery plan](../plans/2026-09-22-macos-proxy-recovery.md).
Source investigation confirmed activation-before-readiness, rollback persistence
after mutation, missing worker-exit restoration, discarded state on failed
rollback, and native detach bypassing network cleanup. Runtime fix and live
acceptance verification remain pending.

**Severity:** Critical — host-wide internet outage

## Impact

Lynx can leave the active macOS network service configured to route both HTTP
and HTTPS through a loopback proxy after the corresponding Lynx listener has
stopped. All browser, application, and command-line web traffic then fails
until the user manually disables those proxy settings.

An installed Lynx CA certificate is not the cause: certificate trust affects
intercepted TLS validation, while the stale proxy redirects all traffic before
that validation can occur.

## Observed state

On 2026-09-22, the active Wi-Fi service reported:

```text
Web Proxy:        Enabled, 127.0.0.1:62006
Secure Web Proxy: Enabled, 127.0.0.1:62006
```

No process was listening on TCP port `62006`. Disabling those two macOS proxy
settings restores direct connectivity. Several stale Lynx launch shells were
also present, so process presence must not be treated as proxy-worker
readiness.

## Reproduction

1. Start a Lynx native network capture that configures the macOS system proxy.
2. End or interrupt the proxy worker, or let it fail after proxy configuration.
3. Do not run a successful explicit cleanup path.
4. Inspect the active service:

   ```bash
   networksetup -getwebproxy "Wi-Fi"
   networksetup -getsecurewebproxy "Wi-Fi"
   lsof -nP -iTCP:62006 -sTCP:LISTEN
   ```

5. Observe that both proxies remain enabled while no listener exists.

## Expected behavior

- Lynx must configure a system proxy only after its worker has bound and
  positively acknowledged the exact endpoint.
- `network stop`, `detach`, normal process exit, interrupted startup, worker
  crash recovery, and failed proxy configuration must restore the previous
  proxy state exactly once.
- Cleanup must restore the prior setting only when it is still the setting
  installed by that capture; it must not overwrite a user change made during
  capture.
- A stale worker/readiness file or a shell process is not sufficient proof that
  the proxy is usable.
- The CLI must report an actionable failure if a prior Lynx-managed proxy is
  detected but its owned listener is absent.

## Temporary recovery

For the affected Wi-Fi service:

```bash
networksetup -setwebproxystate "Wi-Fi" off
networksetup -setsecurewebproxystate "Wi-Fi" off
```

This recovery does not require removing the Lynx CA certificate.

## Fix scope

Treat this as a lifecycle/lease issue, not a CA-generation issue. Persist the
previous proxy configuration and worker identity before applying the proxy;
require worker readiness before activation; make cleanup idempotent; and add
startup recovery for stale owned leases.

## Acceptance verification

- Start capture, verify a live listener at the configured endpoint, then stop:
  the original web and secure-web proxy settings are restored.
- Repeat for failed startup, forced worker termination, `detach`, and CLI
  interruption.
- Change one proxy setting manually while capture is active: cleanup preserves
  that user change and reports the ownership conflict.
- Confirm direct HTTP and HTTPS requests work after each path.
- Confirm a pre-existing user proxy configuration is restored byte-for-byte,
  including host, port, enablement, auto-proxy configuration, and bypass list.
