package dev.lynx.nativehost

import kotlin.test.Test
import kotlin.test.assertContentEquals
import kotlin.test.assertEquals
import kotlin.test.assertNotNull

class NativeWebSocketFrameCodecTest {
    @Test
    fun maskedClientFramePreservesWireBytesAndDecodesTextForEvidence() {
        val mask = byteArrayOf(0x11, 0x22, 0x33, 0x44)
        val text = "lynx".encodeToByteArray()
        val masked = text.mapIndexed { index, byte -> (byte.toInt() xor (mask[index % mask.size].toInt() and 0xff)).toByte() }.toByteArray()
        val wire = byteArrayOf(0x81.toByte(), (0x80 or text.size).toByte()) + mask + masked
        val input = ChunkedTlsConnection(wire)
        val reader = NativeTlsBufferedReader(input)

        val frame = assertNotNull(NativeWebSocketFrameCodec.read(reader::readExactly))

        assertContentEquals(wire, frame.wireBytes, "forwarded frame must retain original client masking")
        assertContentEquals(text, frame.payload)
        assertEquals("TEXT", frame.opcode)
        assertEquals("CLIENT_TO_SERVER", frame.evidence("CLIENT_TO_SERVER").direction)
        assertEquals("lynx", frame.evidence("CLIENT_TO_SERVER").payload)
    }

    @Test
    fun tlsHeaderReaderRetainsBytesFollowingUpgradeHeaders() {
        val frameWire = byteArrayOf(0x81.toByte(), 5) + "hello".encodeToByteArray()
        val allBytes = "HTTP/1.1 101 Switching Protocols\r\nUpgrade: websocket\r\n\r\n".encodeToByteArray() + frameWire
        val reader = NativeTlsBufferedReader(ChunkedTlsConnection(allBytes))

        val header = reader.readHeaders()
        val frame = assertNotNull(NativeWebSocketFrameCodec.read(reader::readExactly))

        assertEquals("HTTP/1.1 101 Switching Protocols\r\nUpgrade: websocket\r\n\r\n", header)
        assertContentEquals(frameWire, frame.wireBytes)
        assertEquals("hello", frame.payload.decodeToString())
    }

    @Test
    fun binaryPayloadIsEmittedAsBase64() {
        val frame = NativeWebSocketFrameCodec.read { requested ->
            when (requested) {
                2 -> byteArrayOf(0x82.toByte(), 3)
                3 -> byteArrayOf(0x00, 0xff.toByte(), 0x10)
                else -> error("unexpected read size $requested")
            }
        }

        assertNotNull(frame)
        assertEquals("base64:AP8Q", frame.evidence("SERVER_TO_CLIENT").payload)
    }
}

private class ChunkedTlsConnection(bytes: ByteArray) : NativeTlsConnection {
    private var remaining = bytes
    override val fileDescriptor: Int = -1
    override val applicationProtocol: String? = null

    override fun read(maxBytes: Int): ByteArray? {
        if (remaining.isEmpty()) return null
        val count = minOf(maxBytes, remaining.size)
        val chunk = remaining.copyOfRange(0, count)
        remaining = remaining.copyOfRange(count, remaining.size)
        return chunk
    }

    override fun write(bytes: ByteArray) = Unit
    override fun close() = Unit
}
