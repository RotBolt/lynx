package dev.lynx.daemon

import java.io.BufferedReader
import java.io.BufferedWriter
import java.nio.channels.Channels
import java.nio.channels.SocketChannel
import java.net.UnixDomainSocketAddress
import java.nio.file.Path

class LocalDaemonClient(
    private val socketPath: Path,
) {
    fun execute(command: String): String {
        SocketChannel.open(UnixDomainSocketAddress.of(socketPath)).use { channel ->
            val reader = BufferedReader(Channels.newReader(channel, Charsets.UTF_8))
            val writer = BufferedWriter(Channels.newWriter(channel, Charsets.UTF_8))
            writer.write(command)
            writer.newLine()
            writer.flush()
            return reader.readLine() ?: error("daemon closed the connection without a response")
        }
    }

    fun executeJson(request: String): String = execute(request)
}
