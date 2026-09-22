package dev.lynx.nativehost

import kotlin.test.Test
import kotlin.test.assertEquals

class NativeHttpBodyDecoderTest {
    @Test
    fun decodesChunkedBodyAndIgnoresExtensionsAndTrailers() {
        val encoded = "4;trace=one\r\nWiki\r\n5\r\npedia\r\n0\r\nDigest: sha-256=abc\r\n\r\n"

        assertEquals(
            "Wikipedia",
            NativeHttpBodyDecoder.decode(mapOf("Transfer-Encoding" to listOf("chunked")), encoded),
        )
    }

    @Test
    fun leavesNonChunkedAndMalformedBodiesUntouched() {
        assertEquals(
            "plain body",
            NativeHttpBodyDecoder.decode(mapOf("Content-Length" to listOf("10")), "plain body"),
        )
        assertEquals(
            "not-a-size\r\nbody\r\n",
            NativeHttpBodyDecoder.decode(mapOf("Transfer-Encoding" to listOf("chunked")), "not-a-size\r\nbody\r\n"),
        )
    }
}
