package dev.lynx.nativehost

import kotlin.test.Test
import kotlin.test.assertEquals

class NativeHttpParserTest {
    @Test
    fun rewritesAbsoluteProxyTargetToOriginForm() {
        val raw = "GET http://example.test/health?full=1 HTTP/1.1\r\nHost: example.test\r\n\r\n"
        val request = NativeHttpParser.parseRequest(raw)
        assertEquals(
            "GET /health?full=1 HTTP/1.1\r\nHost: example.test\r\nConnection: close\r\n\r\n",
            NativeHttpParser.originFormRequest(raw, request),
        )
    }

    @Test
    fun closesOrdinaryUpstreamRequestsButPreservesWebSocketUpgrade() {
        val keepAlive = "GET http://example.test/health HTTP/1.1\r\n" +
            "Host: example.test\r\nConnection: Keep-Alive\r\nProxy-Connection: Keep-Alive\r\n\r\n"
        val websocket = "GET http://example.test/socket HTTP/1.1\r\n" +
            "Host: example.test\r\nConnection: Upgrade\r\nUpgrade: websocket\r\n\r\n"

        assertEquals(
            "GET /health HTTP/1.1\r\nHost: example.test\r\nConnection: close\r\n\r\n",
            NativeHttpParser.originFormRequest(keepAlive, NativeHttpParser.parseRequest(keepAlive)),
        )
        assertEquals(
            "GET /socket HTTP/1.1\r\nHost: example.test\r\nConnection: Upgrade\r\nUpgrade: websocket\r\n\r\n",
            NativeHttpParser.originFormRequest(websocket, NativeHttpParser.parseRequest(websocket)),
        )
    }

    @Test
    fun rewritesWebSocketProxyTargetToOriginForm() {
        val raw = "GET ws://example.test/socket?q=1 HTTP/1.1\r\n" +
            "Host: example.test\r\nConnection: Upgrade\r\nUpgrade: websocket\r\n\r\n"
        val request = NativeHttpParser.parseRequest(raw)
        assertEquals(
            "GET /socket?q=1 HTTP/1.1\r\nHost: example.test\r\nConnection: Upgrade\r\nUpgrade: websocket\r\n\r\n",
            NativeHttpParser.originFormRequest(raw, request),
        )
    }

    @Test
    fun parsesAbsoluteFormRequestAndBody() {
        val request = NativeHttpParser.parseRequest(
            "POST http://example.test/health HTTP/1.1\r\n" +
                "Host: example.test\r\nContent-Length: 7\r\nX-Test: yes\r\n\r\n" +
                "payload",
        )

        assertEquals("POST", request.method)
        assertEquals("http://example.test/health", request.url)
        assertEquals("example.test", request.headers["Host"])
        assertEquals("payload", request.body)
    }

    @Test
    fun parsesResponseAndPreservesDuplicateHeaderValues() {
        val response = NativeHttpParser.parseResponse(
            "HTTP/1.1 204 No Content\r\nX-Test: one\r\nX-Test: two\r\n\r\n",
        )

        assertEquals(204, response.status)
        assertEquals(listOf("one", "two"), response.headers["X-Test"])
        assertEquals("", response.body)
    }
}
