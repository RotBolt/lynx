package dev.lynx.network

import com.sun.net.httpserver.HttpServer
import dev.lynx.model.InMemoryEvidenceTimeline
import dev.lynx.model.RequestId
import dev.lynx.model.SessionId
import java.net.InetSocketAddress
import java.net.Socket
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
}
