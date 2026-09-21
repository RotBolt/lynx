package dev.lynx.daemon

import kotlin.test.Test
import kotlin.test.assertEquals
import java.nio.file.Path

class DaemonPathsTest {
    @Test
    fun defaultSocketLivesUnderTheProvidedHome() {
        assertEquals(Path.of("/tmp/example", ".lynx", "daemon.sock"), DaemonPaths.defaultSocket("/tmp/example"))
    }
}
