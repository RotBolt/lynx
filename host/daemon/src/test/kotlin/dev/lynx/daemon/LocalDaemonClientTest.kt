package dev.lynx.daemon

import java.nio.file.Files
import kotlin.test.Test
import kotlin.test.assertEquals

class LocalDaemonClientTest {
    @Test
    fun sendsCommandsToTheLocalServer() {
        val directory = Files.createTempDirectory("lynx-client-test")
        val socketPath = directory.resolve("daemon.sock")
        val server = LocalDaemonServer(socketPath)
        server.start()

        try {
            assertEquals("OK PONG", LocalDaemonClient(socketPath).execute("PING"))
        } finally {
            server.close()
            Files.deleteIfExists(socketPath)
            Files.deleteIfExists(directory)
        }
    }
}
