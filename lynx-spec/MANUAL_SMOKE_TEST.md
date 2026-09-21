# M1 Android emulator smoke test (JVM compatibility backend)

> This is the historical daemon-protocol smoke test. The supported developer
> distribution is the standalone native KMP executable documented in
> `docs/distribution/native.md`; this file remains for JVM regression coverage.

Validated on macOS with an Android emulator (`emulator-5554`) and the
debuggable package `ai.sarvam.prep.app`.

```bash
./gradlew test :apps:cli:installJvmDist --no-daemon
./apps/cli/build/install/cli-jvm/bin/cli daemon

./apps/cli/build/install/cli-jvm/bin/cli attach \
  --device emulator-5554 --package ai.sarvam.prep.app --json
./apps/cli/build/install/cli-jvm/bin/cli network start --json
./apps/cli/build/install/cli-jvm/bin/cli network doctor --json
sleep 18
./apps/cli/build/install/cli-jvm/bin/cli network list --json
./apps/cli/build/install/cli-jvm/bin/cli network get <request_id> --json

./apps/cli/build/install/cli-jvm/bin/cli db list --json
./apps/cli/build/install/cli-jvm/bin/cli db snapshot databases/conversation.db --json
./apps/cli/build/install/cli-jvm/bin/cli db tables --snapshot <snapshot_id> --json
./apps/cli/build/install/cli-jvm/bin/cli db schema --snapshot <snapshot_id> --json
./apps/cli/build/install/cli-jvm/bin/cli db query --snapshot <snapshot_id> \
  'SELECT id, role, text FROM messages LIMIT 2' --json
./apps/cli/build/install/cli-jvm/bin/cli network stop --json
```

Observed results:

- `network doctor` reported `httpsMitm:true`, `supportedProtocols` of
  `HTTP/1.1`, `HTTP/2`, and `WebSocket`, and the concrete emulator endpoint.
- `network list` captured the Ktor request to
  `https://jsonplaceholder.typicode.com/todos/1` as `HTTP/2`, including the
  response body; `network get` returned the same complete exchange.
- `db list` discovered `conversation.db`; snapshot, tables, schema, and
  read-only query returned structured JSON.
- Restarting the app changed the PID but reusing `attach` preserved the same
  `session_id`; starting capture again succeeded.
- After `detach`, querying a prior snapshot returned `SESSION_DETACHED`.
- The Android `http_proxy` value after `network stop`/`detach` matched its
  value before Lynx started.

The snapshot may report `consistent:false` when WAL/SHM coherence cannot be
guaranteed; this is surfaced in the snapshot response rather than hidden.
