package dev.lynx.daemon

import kotlin.test.Test
import kotlin.test.assertContains
import kotlin.test.assertEquals
import dev.lynx.adb.ResolvedTarget
import dev.lynx.model.EvidenceFilter
import dev.lynx.adb.AdbException
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.emptyFlow

class DaemonServiceTest {
    @Test
    fun exposesTimelineThroughReadOnlyEvidenceQuery() {
        val service = DaemonService()
        assertEquals(emptyList(), service.evidence(EvidenceFilter()))
    }

    @Test
    fun attachAndStatusExposeTheSameSession() {
        val service = DaemonService { _, _ -> ResolvedTarget("emulator-5554", "com.example.app", 1234) }

        val attached = service.handle("ATTACH emulator-5554 com.example.app")
        val sessionId = attached.substringAfter("id=").substringBefore(" ").trim()

        assertContains(attached, "OK ATTACHED")
        assertContains(service.handle("STATUS"), "OK ACTIVE")
        assertContains(service.handle("STATUS $sessionId"), "OK ACTIVE")
    }

    @Test
    fun detachRemovesTheSession() {
        val service = DaemonService { _, _ -> ResolvedTarget("emulator-5554", "com.example.app", 1234) }
        val attached = service.handle("ATTACH emulator-5554 com.example.app")
        val sessionId = attached.substringAfter("id=").substringBefore(" ").trim()

        assertEquals("OK DETACHED", service.handle("DETACH"))
        assertEquals("ERROR NOT_FOUND", service.handle("STATUS"))
    }

    @Test
    fun detachKeepsSessionWhenNetworkCleanupFails() {
        val source = FailingStopNetworkSource()
        val service = DaemonService(
            targetResolver = TargetResolver { _, _ -> ResolvedTarget("emulator-5554", "com.example.app", 1234) },
            networkFactory = NetworkSourceFactory { _, _ -> source },
        )
        val attached = service.handle("ATTACH emulator-5554 com.example.app")
        val sessionId = attached.substringAfter("id=").substringBefore(" ").trim()
        assertContains(service.handle("NETWORK_START"), "OK NETWORK_STARTED")

        val detached = service.handle("DETACH")

        assertContains(detached, "ERROR NETWORK_CLEANUP_FAILED")
        assertContains(service.handle("STATUS $sessionId"), "OK ACTIVE")
    }

    @Test
    fun statusRebindsARestartedProcessWithoutChangingSessionId() {
        var pid = 1234
        val service = DaemonService { _, _ -> ResolvedTarget("emulator-5554", "com.example.app", pid) }
        val attached = service.handle("ATTACH emulator-5554 com.example.app")
        val sessionId = attached.substringAfter("id=").substringBefore(" ").trim()

        pid = 5678
        val status = service.handle("STATUS")

        assertContains(status, "OK ACTIVE id=$sessionId")
        assertContains(status, "pid=5678")
        assertContains(service.handle("STATUS $sessionId"), "pid=5678")
    }

    @Test
    fun statusReportsProcessLostWhenRestartedProcessCannotBeResolved() {
        var running = true
        val service = DaemonService { _, _ ->
            if (running) ResolvedTarget("emulator-5554", "com.example.app", 1234)
            else throw AdbException("PROCESS_NOT_RUNNING", "Package has no running process")
        }
        service.handle("ATTACH emulator-5554 com.example.app")
        running = false

        assertContains(service.handle("STATUS"), "ERROR PROCESS_LOST")
    }

    @Test
    fun reattachingAfterProcessRestartKeepsTheLogicalSessionId() {
        var pid = 1234
        val service = DaemonService { _, _ -> ResolvedTarget("emulator-5554", "com.example.app", pid) }

        val first = service.handle("ATTACH emulator-5554 com.example.app")
        val sessionId = first.substringAfter("id=").substringBefore(" ").trim()
        pid = 5678

        val second = service.handle("ATTACH emulator-5554 com.example.app")

        assertContains(second, "OK ATTACHED id=$sessionId")
        assertContains(second, "pid=5678")
        assertContains(service.handle("STATUS"), "OK ACTIVE id=$sessionId")
        assertContains(service.handle("STATUS"), "pid=5678")
    }

}

private class FailingStopNetworkSource : NetworkCaptureSource {
    override val endpoint: String = "127.0.0.1:62006"
    override suspend fun start(config: NetworkCaptureConfig) = Unit
    override suspend fun stop() {
        error("restore failed")
    }
    override fun events(): Flow<NetworkDomainEvent> = emptyFlow()
    override suspend fun capabilities(): NetworkCapabilities = NetworkCapabilities(
        httpsMitm = true,
        maxBodyBytes = 0,
        limitations = emptyList(),
    )
}
