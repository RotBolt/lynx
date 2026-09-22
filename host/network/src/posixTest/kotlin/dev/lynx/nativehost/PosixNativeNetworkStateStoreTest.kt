package dev.lynx.nativehost

import dev.lynx.model.NetworkCapabilities
import kotlin.concurrent.atomics.AtomicInt
import kotlin.concurrent.atomics.ExperimentalAtomicApi
import kotlin.test.Test
import kotlin.test.assertEquals
import platform.posix.usleep

@OptIn(ExperimentalAtomicApi::class)
class PosixNativeNetworkStateStoreTest {
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
