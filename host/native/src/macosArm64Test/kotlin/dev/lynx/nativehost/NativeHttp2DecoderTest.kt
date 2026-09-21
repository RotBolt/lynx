package dev.lynx.nativehost

import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertTrue

class NativeHttp2DecoderTest {
    @Test
    fun decodesHpackHeadersAndEndStream() {
        val decoder = NativeHttp2Decoder(requestSide = true)
        try {
            val preface = "PRI * HTTP/2.0\r\n\r\nSM\r\n\r\n".encodeToByteArray()
            val settings = frame(4, 0, 0, byteArrayOf())
            // HPACK: :method GET (2), :scheme https (7), literal :authority,
            // :path / (4), then END_STREAM + END_HEADERS.
            val authority = "example.com".encodeToByteArray()
            val headers = byteArrayOf(0x82.toByte(), 0x87.toByte(), 0x01, authority.size.toByte()) + authority + byteArrayOf(0x84.toByte())
            val exchanges = decoder.feed(preface + settings + frame(1, 0x5, 1, headers))
            assertEquals(1, exchanges.size)
            assertEquals(1, exchanges.single().streamId)
            assertEquals("GET", exchanges.single().headers[":method"])
            assertEquals("https", exchanges.single().headers[":scheme"])
            assertEquals("example.com", exchanges.single().headers[":authority"])
            assertTrue(exchanges.single().body.isEmpty())
        } finally {
            decoder.close()
        }
    }

    @Test
    fun decodesServerResponseWithClientSession() {
        val decoder = NativeHttp2Decoder(requestSide = false)
        try {
            // The passive response observer receives SETTINGS, a HPACK :status 200 header block,
            // then DATA with END_STREAM.
            val response = frame(4, 0, 0, byteArrayOf()) + frame(1, 0x4, 1, byteArrayOf(0x88.toByte())) + frame(0, 0x1, 1, "ok".encodeToByteArray())
            val exchanges = decoder.feed(response.copyOfRange(0, 9)) +
                decoder.feed(response.copyOfRange(9, 18)) +
                decoder.feed(response.copyOfRange(18, response.size))
            assertEquals(1, exchanges.size)
            assertEquals(1, exchanges.single().streamId)
            assertEquals("200", exchanges.single().headers[":status"])
            assertEquals("ok", exchanges.single().body.decodeToString())
        } finally {
            decoder.close()
        }
    }

    private fun frame(type: Int, flags: Int, stream: Int, payload: ByteArray): ByteArray = byteArrayOf(
        ((payload.size ushr 16) and 0xff).toByte(), ((payload.size ushr 8) and 0xff).toByte(), (payload.size and 0xff).toByte(),
        type.toByte(), flags.toByte(), 0, 0, 0, stream.toByte(),
    ) + payload
}
