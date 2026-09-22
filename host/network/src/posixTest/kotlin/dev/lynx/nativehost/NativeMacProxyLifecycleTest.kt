package dev.lynx.nativehost

import dev.lynx.model.NetworkCapabilities
import dev.lynx.model.NetworkCaptureSettings
import dev.lynx.model.NetworkCommand
import kotlin.test.Test
import kotlin.test.assertFailsWith
import kotlin.test.assertFalse
import kotlin.test.assertNotNull

class NativeMacProxyLifecycleTest {
    @Test
    fun startDoesNotMutateMacProxyUntilWorkerReadinessMatchesEndpoint() {
        val root = "/tmp/lynx-network-lifecycle-${kotlin.time.Clock.System.now().toEpochMilliseconds()}"
        val store = PosixNativeNetworkStateStore(root)
        val sessions = InMemoryNativeSessionStore().also {
            it.save(NativeSession("session-1", "ios-simulator:SIM-1", "dev.lynx.app", 1234))
        }
        val runner = StatefulNetworksetupRunner()
        val inspector = PosixNativeNetworkInspector(
            store = store,
            sessions = sessions,
            processes = runner,
            certificates = PosixNativeCertificateAuthority(
                runner = object : NativeProcessRunner {
                    override fun run(command: List<String>) = NativeCommandResult(0, "")
                },
                root = "$root/certs",
            ),
        )

        assertFailsWith<IllegalStateException> {
            inspector.execute(NetworkCommand.Start(NetworkCaptureSettings(listenHost = "127.0.0.1", listenPort = 62006)))
        }

        assertFalse(runner.commands.any { command -> command.any { it.startsWith("-set") } })
        assertNotNull(store.macProxyLease(), "prepared lease must be durable before any future proxy mutation")
    }
}
