package dev.lynx.nativehost

import kotlin.concurrent.atomics.*
import kotlin.test.Test
import kotlin.test.assertEquals
import platform.posix.usleep

@OptIn(ExperimentalAtomicApi::class)
class PosixNativeConnectionDispatcherTest {
    @Test
    fun longLivedClientDoesNotBlockLaterClients() {
        val firstStarted = AtomicInt(0)
        val releaseFirst = AtomicInt(0)
        val secondFinished = AtomicInt(0)
        val dispatcher = PosixNativeConnectionDispatcher()

        dispatcher.dispatch {
            firstStarted.store(1)
            while (releaseFirst.load() == 0) usleep(1_000u)
        }

        try {
            assertTrueEventually { firstStarted.load() == 1 }
            dispatcher.dispatch { secondFinished.store(1) }
            assertTrueEventually { secondFinished.load() == 1 }
        } finally {
            releaseFirst.store(1)
        }

        assertEquals(1, secondFinished.load())
    }

    private fun assertTrueEventually(condition: () -> Boolean) {
        repeat(2_000) {
            if (condition()) return
            usleep(1_000u)
        }
        assertEquals(true, condition(), "background client work did not finish within two seconds")
    }
}
