package dev.lynx.nativehost

import kotlin.test.Test
import kotlin.test.assertEquals

class NativeHttpBodyDecoderTest {
    @Test
    fun decodesChunkedBodyAndIgnoresExtensionsAndTrailers() {
        val encoded = "4;trace=one\r\nWiki\r\n5\r\npedia\r\n0\r\nDigest: sha-256=abc\r\n\r\n"

        assertEquals(
            "Wikipedia",
            NativeHttpBodyDecoder.decode(mapOf("Transfer-Encoding" to "chunked"), encoded.encodeToByteArray()).decodeToString(),
        )
    }

    @Test
    fun leavesNonChunkedAndMalformedBodiesUntouched() {
        assertEquals(
            "plain body",
            NativeHttpBodyDecoder.decode(mapOf("Content-Length" to "10"), "plain body".encodeToByteArray()).decodeToString(),
        )
        assertEquals(
            "not-a-size\r\nbody\r\n",
            NativeHttpBodyDecoder.decode(mapOf("Transfer-Encoding" to "chunked"), "not-a-size\r\nbody\r\n".encodeToByteArray()).decodeToString(),
        )
    }

    @Test
    fun decompressesGzipBodyForReadableNetworkEvidence() {
        val compressed = byteArrayOf(
            0x1f, 0x8b.toByte(), 0x08, 0x00, 0x00, 0x00, 0x00, 0x00, 0x00, 0x03,
            0xab.toByte(), 0x56, 0xca.toByte(), 0x4d, 0x2d, 0x2e, 0x4e, 0x4c, 0x4f, 0x55,
            0xb2.toByte(), 0x52, 0x4a, 0x49, 0x4d, 0xce.toByte(), 0x4f, 0x49, 0x4d, 0x51,
            0x48, 0xaf.toByte(), 0xca.toByte(), 0x2c, 0x50, 0x48, 0xca.toByte(), 0x4f, 0xa9.toByte(),
            0x54, 0xaa.toByte(), 0x05, 0x00, 0x46, 0x48, 0xa5.toByte(), 0x77, 0x1f, 0x00, 0x00, 0x00,
        )
        assertEquals(
            "{\"message\":\"decoded gzip body\"}",
            NativeHttpBodyDecoder.decode(mapOf("Content-Encoding" to "gzip"), compressed).decodeToString(),
        )
    }

    @Test
    fun decompressesBrotliBodyForReadableNetworkEvidence() {
        val compressed = byteArrayOf(
            0x0f, 0x10, 0x80.toByte(), 0x7b, 0x22, 0x6d, 0x65, 0x73, 0x73, 0x61,
            0x67, 0x65, 0x22, 0x3a, 0x22, 0x64, 0x65, 0x63, 0x6f, 0x64, 0x65,
            0x64, 0x20, 0x62, 0x72, 0x6f, 0x74, 0x6c, 0x69, 0x20, 0x62, 0x6f,
            0x64, 0x79, 0x22, 0x7d, 0x03,
        )
        assertEquals(
            "{\"message\":\"decoded brotli body\"}",
            NativeHttpBodyDecoder.decode(mapOf("Content-Encoding" to "br"), compressed).decodeToString(),
        )
    }
}
