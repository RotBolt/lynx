# Lynx manual smoke test

This validates the real ADB boundary and the MVP host service. It does not
attach the JVMTI runtime yet.

## Build the command

```bash
gradle test :apps:cli:installDist --no-daemon
```

The executable is:

```bash
./apps/cli/build/install/lynx/bin/lynx
```

The current foundation also includes canonical evidence models, a bounded
timeline, and replaceable network/database source contracts. The proxy and
SQLite collectors are the next vertical slices and are not yet active CLI
commands.

## Check ADB

```bash
./apps/cli/build/install/lynx/bin/lynx doctor
./apps/cli/build/install/lynx/bin/lynx devices
```

`doctor` shows the exact executable path Lynx resolved. It checks
`ANDROID_HOME`, `ANDROID_SDK_ROOT`, the standard macOS SDK location, and PATH
entries. Shell aliases and functions are not executable paths for Lynx; add
the SDK `platform-tools` directory to PATH or set one of those SDK variables.

Expected outcomes:

- online devices are listed; or
- `ERROR ADB_NOT_FOUND` if the Android SDK platform tools are unavailable; or
- `No online Android devices found` if ADB is installed but no device/emulator is connected.

## Test a real attach attempt

In terminal 1:

```bash
./apps/cli/build/install/lynx/bin/lynx daemon
```

In terminal 2:

```bash
./apps/cli/build/install/lynx/bin/lynx attach --package com.example.app
```

Add `--device <serial>` when more than one device is online. Lynx now resolves
the PID through ADB and rejects missing, stopped, or non-debuggable apps.

After attaching, start the HTTP collector:

```bash
./apps/cli/build/install/lynx/bin/lynx network start
./apps/cli/build/install/lynx/bin/lynx network list
```

The start response includes the host endpoint. Configure the Android app/device
to use that endpoint as its HTTP proxy, reproduce a request, then run
`network list` again. The current collector reports HTTP traffic; HTTPS MITM
and Android proxy configuration are the next network slice.
