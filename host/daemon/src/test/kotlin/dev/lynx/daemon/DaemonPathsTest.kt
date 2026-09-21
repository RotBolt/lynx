package dev.lynx.daemon

import kotlin.test.Test
import kotlin.test.assertEquals

class DaemonPathsTest {
    @Test
    fun defaultSocketLivesUnderTheProvidedHome() {
        assertEquals("/tmp/example/.lynx/daemon.sock", DaemonPaths.defaultSocket("/tmp/example").toString())
    }
}
