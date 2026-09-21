package dev.lynx.daemon

import java.nio.channels.Channels
import java.nio.channels.SocketChannel
import java.nio.file.Files
import java.net.StandardProtocolFamily
import java.net.UnixDomainSocketAddress
import java.io.BufferedReader
import kotlin.test.Test
import kotlin.test.assertEquals

class LocalDaemonServerTest {
    @Test
    fun acceptsACommandOverUnixDomainSocket() {
        val directory = Files.createTempDirectory("lynx-daemon-test")
        val socketPath = directory.resolve("daemon.sock")
        val server = LocalDaemonServer(socketPath)
        server.start()

        try {
            SocketChannel.open(UnixDomainSocketAddress.of(socketPath)).use { channel ->
                val writer = Channels.newWriter(channel, Charsets.UTF_8)
                val reader = BufferedReader(Channels.newReader(channel, Charsets.UTF_8))
                writer.write("PING\n")
                writer.flush()
                assertEquals("OK PONG", reader.readLine())
            }
        } finally {
            server.close()
            Files.deleteIfExists(socketPath)
            Files.deleteIfExists(directory)
        }
    }
}
