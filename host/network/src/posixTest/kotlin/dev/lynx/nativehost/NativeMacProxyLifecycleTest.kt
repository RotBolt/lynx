package dev.lynx.nativehost

import dev.lynx.model.NetworkCapabilities
import dev.lynx.model.NetworkCaptureSettings
import dev.lynx.model.NetworkCommand
import dev.lynx.model.CaptureState
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFailsWith
import kotlin.test.assertFalse
import kotlin.test.assertNotNull
import kotlin.test.assertTrue

class NativeMacProxyLifecycleTest {
    @Test
    fun failedStartInterruptsItsCaptureLease() {
        val root = "/tmp/lynx-network-failed-capture-${kotlin.time.Clock.System.now().toEpochMilliseconds()}"
        val repository = PosixCaptureRepository("$root/captures")
        val sessions = InMemoryNativeSessionStore().also {
            it.save(NativeSession("session-1", "ios-simulator:SIM-1", "dev.lynx.app", 1234))
        }

        assertFailsWith<IllegalStateException> {
            inspector(PosixNativeNetworkStateStore(root), sessions, StatefulNetworksetupRunner(), root, repository)
                .execute(NetworkCommand.Start(NetworkCaptureSettings(listenHost = "127.0.0.1", listenPort = 62006)))
        }

        assertEquals(CaptureState.INTERRUPTED, repository.sessions().single().state)
    }

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

    @Test
    fun startDoesNotTreatStaleSameEndpointRuntimeStateAsHealthy() {
        val root = "/tmp/lynx-network-stale-${kotlin.time.Clock.System.now().toEpochMilliseconds()}"
        val store = PosixNativeNetworkStateStore(root)
        store.setRunning("127.0.0.1:62006", NetworkCapabilities(httpsMitm = true, limitations = emptyList()))
        store.save(activeLease())
        val sessions = InMemoryNativeSessionStore().also {
            it.save(NativeSession("session-1", "ios-simulator:SIM-1", "dev.lynx.app", 1234))
        }
        val runner = StatefulNetworksetupRunner(
            web = ProxyFixture(enabled = true, server = "127.0.0.1", port = "62006"),
            secure = ProxyFixture(enabled = true, server = "127.0.0.1", port = "62006"),
        )
        val inspector = inspector(store, sessions, runner, root)

        assertFailsWith<IllegalStateException> {
            inspector.execute(NetworkCommand.Start(NetworkCaptureSettings(listenHost = "127.0.0.1", listenPort = 62006)))
        }

        assertEquals(false, runner.web.enabled)
        assertEquals(false, runner.secure.enabled)
    }

    @Test
    fun startIgnoresStaleReadinessWithoutMatchingCaptureToken() {
        val root = "/tmp/lynx-network-ready-${kotlin.time.Clock.System.now().toEpochMilliseconds()}"
        val store = PosixNativeNetworkStateStore(root)
        store.markWorkerReady(62006)
        val sessions = InMemoryNativeSessionStore().also {
            it.save(NativeSession("session-1", "ios-simulator:SIM-1", "dev.lynx.app", 1234))
        }
        val runner = StatefulNetworksetupRunner()
        val inspector = inspector(store, sessions, runner, root)

        assertFailsWith<IllegalStateException> {
            inspector.execute(NetworkCommand.Start(NetworkCaptureSettings(listenHost = "127.0.0.1", listenPort = 62006)))
        }

        assertFalse(runner.commands.any { command -> command.any { it.startsWith("-set") } })
    }

    @Test
    fun stopRestoresLegacyMacProxyStateInsteadOfClearingTheOnlyRecoveryRecord() {
        val root = "/tmp/lynx-network-legacy-${kotlin.time.Clock.System.now().toEpochMilliseconds()}"
        val store = PosixNativeNetworkStateStore(root)
        store.setRunning(
            "127.0.0.1:62006",
            NetworkCapabilities(httpsMitm = true, limitations = emptyList()),
            previousProxy = mapOf(
                "controller" to "macos",
                "service" to "Wi-Fi",
                "webEnabled" to "No",
                "webServer" to null,
                "webPort" to null,
                "secureEnabled" to "No",
                "secureServer" to null,
                "securePort" to null,
            ),
        )
        val runner = StatefulNetworksetupRunner(
            web = ProxyFixture(enabled = true, server = "127.0.0.1", port = "62006"),
            secure = ProxyFixture(enabled = true, server = "127.0.0.1", port = "62006"),
        )
        val inspector = inspector(store, InMemoryNativeSessionStore(), runner, root)

        inspector.execute(NetworkCommand.Stop)

        assertEquals(false, runner.web.enabled)
        assertEquals(false, runner.secure.enabled)
        assertFalse(store.isRunning())
    }

    private fun inspector(
        store: PosixNativeNetworkStateStore,
        sessions: NativeSessionStore,
        runner: StatefulNetworksetupRunner,
        root: String,
        captureRepository: CaptureRepository = PosixCaptureRepository("$root/captures"),
    ) = PosixNativeNetworkInspector(
        store = store,
        sessions = sessions,
        processes = runner,
        certificates = PosixNativeCertificateAuthority(
            runner = object : NativeProcessRunner {
                override fun run(command: List<String>) = NativeCommandResult(0, "")
            },
            root = "$root/certs",
        ),
        captureRepository = captureRepository,
    )

    private fun activeLease() = NativeMacProxyLease(
        leaseId = "lease-1",
        service = "Wi-Fi",
        endpoint = "127.0.0.1:62006",
        webOriginal = NativeMacProxySetting(enabled = false),
        secureOriginal = NativeMacProxySetting(enabled = false),
        webInstalled = NativeMacProxySetting(enabled = true, server = "127.0.0.1", port = "62006"),
        secureInstalled = NativeMacProxySetting(enabled = true, server = "127.0.0.1", port = "62006"),
        phase = NativeMacProxyLeasePhase.ACTIVE,
        webApplied = true,
        secureApplied = true,
        workerPid = 4242,
        captureToken = "token-1",
    )
}
