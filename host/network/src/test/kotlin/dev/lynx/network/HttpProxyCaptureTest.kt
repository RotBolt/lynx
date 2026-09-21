package dev.lynx.network

import com.sun.net.httpserver.HttpServer
import dev.lynx.model.InMemoryEvidenceTimeline
import dev.lynx.model.RequestId
import dev.lynx.model.SessionId
import java.net.InetSocketAddress
import java.net.Socket
import java.net.ServerSocket
import java.io.ByteArrayOutputStream
import java.util.zip.GZIPOutputStream
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertNotNull
import kotlin.test.assertTrue
import kotlinx.coroutines.runBlocking

class HttpProxyCaptureTest {
    @Test
    fun capturesResponseHeadersBodyAndProvidesStableLookup() {
        val upstream = HttpServer.create(InetSocketAddress("127.0.0.1", 0), 0)
        upstream.createContext("/hello") { exchange ->
            val bytes = "world".toByteArray()
            exchange.responseHeaders.add("X-Test", "yes")
            exchange.sendResponseHeaders(200, bytes.size.toLong())
            exchange.responseBody.use { it.write(bytes) }
        }
        upstream.start()
        val timeline = InMemoryEvidenceTimeline()
        val proxy = HttpProxyCapture(timeline, SessionId("s"), "device", "pkg", 1)
        try {
            runBlocking { proxy.start(dev.lynx.daemon.NetworkCaptureConfig(listenHost = "127.0.0.1")) }
            Socket("127.0.0.1", proxy.port).use { socket ->
                val input = socket.getInputStream().buffered()
                socket.getOutputStream().bufferedWriter().use { out ->
                    out.write("GET http://127.0.0.1:${upstream.address.port}/hello HTTP/1.1\r\nHost: 127.0.0.1\r\nConnection: close\r\n\r\n")
                    out.flush()
                    while (input.read() >= 0) { /* drain */ }
                }
            }
            val exchange = proxy.list().single()
            assertNotNull(proxy.get(exchange.requestId))
            assertEquals(200, exchange.response?.status)
            assertEquals("yes", exchange.response?.headers?.entries?.first { it.key.equals("X-Test", true) }?.value)
            assertEquals("world", exchange.response?.body)
            assertEquals(exchange, timeline.network().single())
        } finally {
            runBlocking { proxy.stop() }
            upstream.stop(0)
        }
    }

    @Test
    fun mapsAndroidEmulatorHostAliasToTheHostLoopbackForUpstreamRequests() {
        val upstream = HttpServer.create(InetSocketAddress("127.0.0.1", 0), 0)
        upstream.createContext("/health") { exchange ->
            val bytes = "healthy".toByteArray()
            exchange.sendResponseHeaders(200, bytes.size.toLong())
            exchange.responseBody.use { it.write(bytes) }
        }
        upstream.start()
        val proxy = HttpProxyCapture(InMemoryEvidenceTimeline(), SessionId("s"), "emulator-5554", "pkg", 1)
        try {
            runBlocking { proxy.start(dev.lynx.daemon.NetworkCaptureConfig(listenHost = "127.0.0.1")) }
            Socket("127.0.0.1", proxy.port).use { socket ->
                socket.getOutputStream().bufferedWriter().use { out ->
                    out.write("GET http://10.0.2.2:${upstream.address.port}/health HTTP/1.1\r\nHost: 10.0.2.2\r\nConnection: close\r\n\r\n")
                    out.flush()
                    socket.getInputStream().readBytes()
                }
            }
            assertEquals("healthy", proxy.list().single().response?.body)
        } finally {
            runBlocking { proxy.stop() }
            upstream.stop(0)
        }
    }

    @Test
    fun reframesGzipResponseAndStoresDecodedBody() {
        val upstream = HttpServer.create(InetSocketAddress("127.0.0.1", 0), 0)
        upstream.createContext("/gzip") { exchange ->
            val compressed = ByteArrayOutputStream().also { GZIPOutputStream(it).use { gzip -> gzip.write("decoded".toByteArray()) } }.toByteArray()
            exchange.responseHeaders.add("Content-Encoding", "gzip")
            exchange.sendResponseHeaders(200, compressed.size.toLong())
            exchange.responseBody.use { it.write(compressed) }
        }
        upstream.start()
        val proxy = HttpProxyCapture(InMemoryEvidenceTimeline(), SessionId("s"), "device", "pkg", 1)
        try {
            runBlocking { proxy.start(dev.lynx.daemon.NetworkCaptureConfig(listenHost = "127.0.0.1")) }
            Socket("127.0.0.1", proxy.port).use { socket ->
                val out = socket.getOutputStream().bufferedWriter()
                out.write("GET http://127.0.0.1:${upstream.address.port}/gzip HTTP/1.1\r\nHost: 127.0.0.1\r\nConnection: close\r\n\r\n")
                out.flush()
                socket.getInputStream().readBytes()
            }
            assertEquals("decoded", proxy.list().single().response?.body)
        } finally {
            runBlocking { proxy.stop() }
            upstream.stop(0)
        }
    }

    @Test
    fun failedConnectIsRetainedAsStructuredExchange() {
        val timeline = InMemoryEvidenceTimeline()
        val proxy = HttpProxyCapture(timeline, SessionId("s"), "device", "pkg", null)
        try {
            runBlocking { proxy.start(dev.lynx.daemon.NetworkCaptureConfig(listenHost = "127.0.0.1")) }
            Socket("127.0.0.1", proxy.port).use { socket ->
                socket.getOutputStream().bufferedWriter().use { out ->
                    out.write("CONNECT example.test:443 HTTP/1.1\r\nHost: example.test\r\n\r\n")
                    out.flush()
                    socket.getInputStream().readBytes()
                }
            }
            val exchange = proxy.list().single()
            assertTrue(exchange.requestId.value.startsWith("req_"))
            assertEquals("HTTPS_MITM_ERROR", exchange.failure?.kind)
        } finally { runBlocking { proxy.stop() } }
    }

    @Test
    fun stopReleasesWorkersAndTheCaptureCanBeStartedAgain() {
        val timeline = InMemoryEvidenceTimeline()
        val proxy = HttpProxyCapture(timeline, SessionId("s"), "device", "pkg", null)
        runBlocking { proxy.start(dev.lynx.daemon.NetworkCaptureConfig(listenHost = "127.0.0.1")) }
        runBlocking { proxy.stop() }
        assertEquals(0, proxy.port)

        try {
            runBlocking { proxy.start(dev.lynx.daemon.NetworkCaptureConfig(listenHost = "127.0.0.1")) }
            assertTrue(proxy.port > 0)
        } finally {
            runBlocking { proxy.stop() }
        }
    }

    @Test
    fun capturesWebSocketUpgradeAndFrames() {
        val upstream = ServerSocket(0)
        val worker = Thread {
            upstream.accept().use { socket ->
                val input = socket.getInputStream()
                readUntil(input, "\r\n\r\n".toByteArray())
                socket.getOutputStream().apply {
                    write("HTTP/1.1 101 Switching Protocols\r\nUpgrade: websocket\r\nConnection: Upgrade\r\n\r\n".toByteArray())
                    flush()
                }
                readWebSocketFrame(input)
                socket.getOutputStream().apply { write(byteArrayOf(0x81.toByte(), 0x05, *"hello".toByteArray())); flush() }
            }
        }.apply { start() }
        val proxy = HttpProxyCapture(InMemoryEvidenceTimeline(), SessionId("s"), "device", "pkg", null)
        try {
            runBlocking { proxy.start(dev.lynx.daemon.NetworkCaptureConfig(listenHost = "127.0.0.1")) }
            Socket("127.0.0.1", proxy.port).use { socket ->
                val input = socket.getInputStream()
                socket.getOutputStream().apply {
                    write("GET http://127.0.0.1:${upstream.localPort}/socket HTTP/1.1\r\nHost: 127.0.0.1\r\nUpgrade: websocket\r\nConnection: Upgrade\r\nSec-WebSocket-Key: abc\r\nSec-WebSocket-Version: 13\r\n\r\n".toByteArray()); flush()
                    readUntil(input, "\r\n\r\n".toByteArray())
                    write(byteArrayOf(0x81.toByte(), 0x83.toByte(), 1, 2, 3, 4, (104 xor 1).toByte(), (101 xor 2).toByte(), (108 xor 3).toByte())); flush()
                }
                input.readNBytes(7)
            }
            worker.join(2000)
            repeat(20) { if (proxy.list().isNotEmpty()) return@repeat; Thread.sleep(50) }
            val exchange = proxy.list().single()
            assertEquals("WebSocket", exchange.protocol)
            assertEquals(2, exchange.frames.count { it.opcode == "TEXT" })
            assertEquals(setOf("hello", "hel"), exchange.frames.mapNotNull { it.payload }.toSet())
        } finally {
            runBlocking { proxy.stop() }
            upstream.close()
            worker.join(2000)
        }
    }

    private fun readUntil(input: java.io.InputStream, marker: ByteArray): ByteArray {
        val out = ByteArrayOutputStream()
        while (out.size() < marker.size || !out.toByteArray().copyOfRange(out.size() - marker.size, out.size()).contentEquals(marker)) { val value = input.read(); if (value < 0) break; out.write(value) }
        return out.toByteArray()
    }

    private fun readWebSocketFrame(input: java.io.InputStream) {
        val header = input.readNBytes(2); val length = header[1].toInt() and 0x7f
        val mask = input.readNBytes(4); val payload = input.readNBytes(length)
        for (i in payload.indices) payload[i] = (payload[i].toInt() xor (mask[i % 4].toInt() and 0xff)).toByte()
    }
}
