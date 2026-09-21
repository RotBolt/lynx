package dev.lynx.daemon

import java.io.BufferedReader
import java.io.BufferedWriter
import java.nio.channels.Channels
import java.nio.channels.ServerSocketChannel
import java.net.StandardProtocolFamily
import java.net.UnixDomainSocketAddress
import java.nio.file.Files
import java.nio.file.Path
import java.util.concurrent.ExecutorService
import java.util.concurrent.Executors

class LocalDaemonServer(
    private val socketPath: Path,
    private val service: DaemonService = DaemonService(),
) : AutoCloseable {
    private val executor: ExecutorService = Executors.newCachedThreadPool { runnable ->
        Thread(runnable, "lynx-daemon").apply { isDaemon = true }
    }
    private var server: ServerSocketChannel? = null

    fun start() {
        check(server == null) { "server already started" }
        Files.deleteIfExists(socketPath)
        Files.createDirectories(socketPath.parent)
        server = ServerSocketChannel.open(StandardProtocolFamily.UNIX).also {
            it.bind(UnixDomainSocketAddress.of(socketPath))
            executor.submit { acceptLoop(it) }
        }
    }

    override fun close() {
        server?.close()
        server = null
        executor.shutdownNow()
    }

    private fun acceptLoop(server: ServerSocketChannel) {
        try {
            while (server.isOpen) {
                val channel = server.accept()
                executor.submit {
                    channel.use {
                      runCatching {
                        val reader = BufferedReader(Channels.newReader(channel, Charsets.UTF_8))
                        val writer = BufferedWriter(Channels.newWriter(channel, Charsets.UTF_8))
                        val command = reader.readLine() ?: return@runCatching
                        val jsonRequest = command.trimStart().startsWith("{")
                        val response = try {
                            if (jsonRequest) DaemonProtocol.handle(command, service) else service.handle(command)
                        } catch (error: Exception) {
                            if (jsonRequest) DaemonProtocol.internalError(error) else throw error
                        }
                        writer.write(response)
                        writer.newLine()
                        writer.flush()
                      }.onFailure { error ->
                        val writer = BufferedWriter(Channels.newWriter(channel, Charsets.UTF_8))
                        writer.write("ERROR INTERNAL ${error.message ?: "command failed"}")
                        writer.newLine()
                        writer.flush()
                      }
                    }
                }
            }
        } catch (error: Exception) {
            if (server.isOpen) throw error
        }
    }
}
