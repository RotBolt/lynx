package dev.lynx.nativehost

import dev.lynx.model.NetworkCapabilities
import dev.lynx.model.EvidenceId
import dev.lynx.model.EvidenceMeta
import dev.lynx.model.EvidenceSource
import dev.lynx.model.NetworkCaptureMetadata
import dev.lynx.model.NetworkExchange
import dev.lynx.model.NetworkFrame
import dev.lynx.model.NetworkRequest
import dev.lynx.model.NetworkResponse
import dev.lynx.model.NetworkTiming
import dev.lynx.model.RequestId
import dev.lynx.model.SessionId
import kotlinx.datetime.Instant
import kotlin.concurrent.atomics.AtomicInt
import kotlin.concurrent.atomics.ExperimentalAtomicApi
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFalse
import platform.posix.usleep

@OptIn(ExperimentalAtomicApi::class)
class PosixNativeNetworkStateStoreTest {
    @Test
    fun incrementalWebSocketPrefixesMaterializeAsOneLatestExchange() {
        val root = "/tmp/lynx-network-live-ws-${kotlin.time.Clock.System.now().toEpochMilliseconds()}"
        val store = PosixNativeNetworkStateStore(root)
        val meta = EvidenceMeta(EvidenceId("ev"), SessionId("session"), Instant.fromEpochMilliseconds(1), EvidenceSource.NETWORK, "emulator-5554", "dev.lynx.dummyapp", 42)
        val requestId = RequestId("ws-live")
        fun exchange(frames: List<NetworkFrame>) = NetworkExchange(
            meta, requestId, NetworkRequest("GET", "wss://example.test/raw", emptyMap(), null),
            NetworkResponse(101, emptyMap(), null), null, NetworkTiming(1, null, null),
            NetworkCaptureMetadata(false, 0, false), "WebSocket", frames,
        )

        store.append(exchange(emptyList()))
        store.append(exchange(listOf(NetworkFrame("CLIENT_TO_SERVER", "TEXT", "ping"))))

        val values = store.list(dev.lynx.model.NetworkFilter())
        assertEquals(1, values.size)
        assertEquals("ping", values.single().frames.single().payload)
    }

    @Test
    fun legacyReadinessAckWithoutCaptureIdentityIsRejected() {
        val store = PosixNativeNetworkStateStore("/tmp/lynx-network-state-legacy-ready-${kotlin.time.Clock.System.now().toEpochMilliseconds()}")

        store.markWorkerReady(62006, "capture-token", 4812)

        assertFalse(store.workerReady(62006, "capture-token", 4812))
    }

    @Test
    fun runningStateReadersNeverObserveAnInProgressRewriteAsStopped() {
        val root = "/tmp/lynx-network-state-test-${kotlin.time.Clock.System.now().toEpochMilliseconds()}"
        val store = PosixNativeNetworkStateStore(root)
        val payload = "x".repeat(256 * 1024)
        val capabilities = NetworkCapabilities(httpsMitm = true, limitations = listOf(payload))
        store.setRunning("0.0.0.0:62006", capabilities)

        val readerReady = AtomicInt(0)
        val continueReading = AtomicInt(1)
        val observedStoppedState = AtomicInt(0)
        PosixNativeConnectionDispatcher().dispatch {
            val reader = PosixNativeNetworkStateStore(root)
            readerReady.store(1)
            while (continueReading.load() == 1) {
                if (!reader.isRunning()) observedStoppedState.store(1)
            }
        }

        try {
            repeat(2_000) {
                if (readerReady.load() == 1) return@repeat
                usleep(1_000u)
            }
            assertEquals(1, readerReady.load(), "background state reader did not start")
            repeat(12) { store.setRunning("0.0.0.0:62006", capabilities) }
        } finally {
            continueReading.store(0)
            store.clearRunning()
            PosixProcessRunner().run(listOf("rmdir", root))
        }

        assertEquals(0, observedStoppedState.load(), "reader observed the state file while it was being rewritten")
    }
}
