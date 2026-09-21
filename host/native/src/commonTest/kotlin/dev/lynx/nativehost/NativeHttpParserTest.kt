package dev.lynx.nativehost

import kotlin.test.Test
import kotlin.test.assertEquals

class NativeHttpParserTest {
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
