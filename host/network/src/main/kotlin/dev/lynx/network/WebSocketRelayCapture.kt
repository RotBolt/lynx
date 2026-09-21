package dev.lynx.network

import dev.lynx.model.NetworkFrame
import java.io.ByteArrayOutputStream
import java.io.InputStream
import java.io.OutputStream
import java.util.Base64
import java.util.concurrent.CountDownLatch
import java.util.concurrent.ExecutorService

/** Relays RFC 6455 frames unchanged while producing an agent-readable trace. */
internal class WebSocketRelayCapture(
    private val executor: ExecutorService,
) {
    fun relay(
        clientInput: InputStream,
        clientOutput: OutputStream,
        upstreamInput: InputStream,
        upstreamOutput: OutputStream,
    ): List<NetworkFrame> {
        val frames = java.util.Collections.synchronizedList(mutableListOf<NetworkFrame>())
        val done = CountDownLatch(2)
        fun copy(input: InputStream, output: OutputStream, direction: String) {
            try {
                while (true) {
                    val frame = readFrame(input) ?: break
                    output.write(frame.raw)
                    output.flush()
                    frames += NetworkFrame(direction, frame.opcode, payload(frame.opcode, frame.payload))
                    if (frame.opcode == "CLOSE") break
                }
            } finally { done.countDown() }
        }
        executor.submit { copy(clientInput, upstreamOutput, "CLIENT_TO_SERVER") }
        executor.submit { copy(upstreamInput, clientOutput, "SERVER_TO_CLIENT") }
        done.await()
        return frames.toList()
    }

    private data class Frame(val raw: ByteArray, val opcode: String, val payload: ByteArray)

    private fun readFrame(input: InputStream): Frame? {
        val first = input.read()
        if (first < 0) return null
        val second = input.read()
        if (second < 0) return null
        val opcode = when (first and 0x0f) {
            0x0 -> "CONTINUATION"; 0x1 -> "TEXT"; 0x2 -> "BINARY"
            0x8 -> "CLOSE"; 0x9 -> "PING"; 0xA -> "PONG"; else -> "OPCODE_${first and 0x0f}"
        }
        val masked = (second and 0x80) != 0
        var length = (second and 0x7f).toLong()
        val raw = ByteArrayOutputStream().apply { write(first); write(second) }
        if (length == 126L) {
            val ext = readExactly(input, 2) ?: return null; raw.write(ext); length = ((ext[0].toInt() and 0xff) shl 8 or (ext[1].toInt() and 0xff)).toLong()
        } else if (length == 127L) {
            val ext = readExactly(input, 8) ?: return null; raw.write(ext); length = ext.fold(0L) { acc, b -> (acc shl 8) or (b.toInt() and 0xff).toLong() }
        }
        require(length <= Int.MAX_VALUE) { "WebSocket frame too large" }
        val mask = if (masked) readExactly(input, 4).also { if (it != null) raw.write(it) } else null
        val payload = readExactly(input, length.toInt()) ?: return null
        raw.write(payload)
        if (mask != null) for (i in payload.indices) payload[i] = (payload[i].toInt() xor (mask[i % 4].toInt() and 0xff)).toByte()
        return Frame(raw.toByteArray(), opcode, payload)
    }

    private fun readExactly(input: InputStream, count: Int): ByteArray? {
        val out = ByteArray(count); var offset = 0
        while (offset < count) { val n = input.read(out, offset, count - offset); if (n < 0) return null; offset += n }
        return out
    }

    private fun payload(opcode: String, bytes: ByteArray): String? = if (bytes.isEmpty()) null else when (opcode) {
        "TEXT", "CONTINUATION" -> bytes.toString(Charsets.UTF_8)
        else -> "base64:" + Base64.getEncoder().encodeToString(bytes)
    }
}
