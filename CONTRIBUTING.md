# Contributing to Lynx 🤝

Lynx is designed for humans and AI agents from any vendor. Contributions
should preserve the vendor-neutral CLI contract and the read-only MVP boundary.

## Before changing code

1. Read `lynx-spec/README.md`, `FEATURE_REQUIREMENTS.md`, `ARCHITECTURE.md`,
   and `IMPLEMENTATION.md`.
2. Identify the smallest scoped ticket in
   `lynx-spec/IMPLEMENTATION_TICKETS.md`.
3. Add or update a focused test before changing behavior.

## Validation

```bash
./gradlew test :apps:cli:installJvmDist --no-daemon
```

For network/database changes, also run the emulator workflow in
[MANUAL_SMOKE_TEST.md](MANUAL_SMOKE_TEST.md).

## Design rules

- Keep public models and JSON schemas owned by Lynx.
- Keep proxy-library and AOSP types behind adapters.
- Preserve complete bodies and failures; never silently truncate evidence.
- Keep database operations read-only.
- Do not add Android Studio, VPN/TUN, companion-app, or application-source
  dependencies to the MVP.
- Document unsupported transports and trust requirements explicitly.

Commit messages should explain the behavior change, and every change should
leave the working tree reproducible from the Gradle wrapper.
