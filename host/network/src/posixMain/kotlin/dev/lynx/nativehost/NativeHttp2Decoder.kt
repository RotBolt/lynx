package dev.lynx.nativehost

import cnames.structs.nghttp2_hd_inflater
import dev.lynx.nativehost.nghttp2.*
import kotlinx.cinterop.*

/** Passive HTTP/2 frame observer. Bytes are forwarded by the relay unchanged; this class only
 * frames the stream and delegates HPACK decoding to the platform nghttp2 library. It does not
 * create a client/server session, so response streams and dynamically indexed headers are observed
 * without inventing protocol state that was not present on the wire. */
@OptIn(ExperimentalForeignApi::class)
class NativeHttp2Decoder(private val requestSide: Boolean) {
    data class Exchange(val streamId: Int, val headers: Map<String, String>, val body: ByteArray)

    private data class Stream(
        val headers: LinkedHashMap<String, String> = linkedMapOf(),
        val body: MutableList<Byte> = mutableListOf(),
    )

    private val inflater: CPointer<nghttp2_hd_inflater> = memScoped {
        val pointer = allocPointerTo<nghttp2_hd_inflater>()
        require(nghttp2_hd_inflate_new(pointer.ptr) == 0)
        pointer.value ?: error("nghttp2 HPACK inflater unavailable")
    }
    private val streams = mutableMapOf<Int, Stream>()
    private val headerBlocks = mutableMapOf<Int, ByteArray>()
    private val endedStreams = mutableSetOf<Int>()
    private var buffered = ByteArray(0)
    private var prefaceConsumed = !requestSide

    fun feed(bytes: ByteArray): List<Exchange> {
        if (bytes.isEmpty()) return emptyList()
        buffered += bytes
        val completed = mutableListOf<Exchange>()
        while (true) {
            if (!prefaceConsumed) {
                if (buffered.size < PREFACE.size) break
                require(buffered.copyOfRange(0, PREFACE.size).contentEquals(PREFACE)) { "invalid HTTP/2 client preface" }
                buffered = buffered.copyOfRange(PREFACE.size, buffered.size)
                prefaceConsumed = true
            }
            if (buffered.size < FRAME_HEADER_SIZE) break
            val length = ((buffered[0].toInt() and 0xff) shl 16) or
                ((buffered[1].toInt() and 0xff) shl 8) or (buffered[2].toInt() and 0xff)
            val frameSize = FRAME_HEADER_SIZE + length
            if (buffered.size < frameSize) break
            val type = buffered[3].toInt() and 0xff
            val flags = buffered[4].toInt() and 0xff
            val streamId = ((buffered[5].toInt() and 0x7f) shl 24) or
                ((buffered[6].toInt() and 0xff) shl 16) or
                ((buffered[7].toInt() and 0xff) shl 8) or (buffered[8].toInt() and 0xff)
            val payload = buffered.copyOfRange(FRAME_HEADER_SIZE, frameSize)
            buffered = buffered.copyOfRange(frameSize, buffered.size)
            when (type) {
                TYPE_HEADERS -> observeHeaders(streamId, flags, payload, completed)
                TYPE_CONTINUATION -> observeContinuation(streamId, flags, payload, completed)
                TYPE_DATA -> {
                    val body = dataPayload(flags, payload)
                    streams.getOrPut(streamId) { Stream() }.body.addAll(body.toList())
                    if (flags and FLAG_END_STREAM != 0) complete(streamId, completed)
                }
                TYPE_RST_STREAM -> complete(streamId, completed)
            }
        }
        return completed
    }

    fun close() {
        nghttp2_hd_inflate_del(inflater)
        streams.clear()
        headerBlocks.clear()
        endedStreams.clear()
        buffered = ByteArray(0)
    }

    private fun observeHeaders(streamId: Int, flags: Int, payload: ByteArray, completed: MutableList<Exchange>) {
        if (streamId == 0) return
        var offset = 0
        var end = payload.size
        if (flags and FLAG_PADDED != 0) {
            if (payload.isEmpty()) return
            val padding = payload[0].toInt() and 0xff
            offset = 1
            end -= padding
        }
        if (flags and FLAG_PRIORITY != 0) offset += 5
        if (offset > end) return
        val block = payload.copyOfRange(offset, end)
        if (flags and FLAG_END_HEADERS == 0) headerBlocks[streamId] = block else decodeHeaders(streamId, block)
        if (flags and FLAG_END_STREAM != 0) endedStreams += streamId
        if (streamId in endedStreams && !headerBlocks.containsKey(streamId)) complete(streamId, completed)
    }

    private fun observeContinuation(streamId: Int, flags: Int, payload: ByteArray, completed: MutableList<Exchange>) {
        if (streamId == 0) return
        val block = (headerBlocks[streamId] ?: ByteArray(0)) + payload
        if (flags and FLAG_END_HEADERS != 0) {
            headerBlocks.remove(streamId)
            decodeHeaders(streamId, block)
        } else headerBlocks[streamId] = block
        if (streamId in endedStreams && !headerBlocks.containsKey(streamId)) complete(streamId, completed)
    }

    private fun decodeHeaders(streamId: Int, block: ByteArray) {
        val stream = streams.getOrPut(streamId) { Stream() }
        memScoped {
            val output = allocArray<UByteVar>(MAX_HEADER_OUTPUT)
            val written = block.usePinned { lynx_h2_inflate_text(inflater, it.addressOf(0).reinterpret(), block.size.toULong(), output, MAX_HEADER_OUTPUT.toULong()) }
            require(written >= 0) { "invalid HPACK header block" }
            ByteArray(written.toInt()) { output[it].toByte() }.decodeToString().lineSequence().forEach { line ->
                val separator = line.indexOf('=')
                if (separator > 0) stream.headers[line.substring(0, separator)] = line.substring(separator + 1)
            }
        }
    }

    private fun complete(streamId: Int, completed: MutableList<Exchange>) {
        val stream = streams.remove(streamId) ?: return
        endedStreams.remove(streamId)
        completed += Exchange(streamId, stream.headers.toMap(), stream.body.toByteArray())
    }

    private fun dataPayload(flags: Int, payload: ByteArray): ByteArray {
        if (flags and FLAG_PADDED == 0) return payload
        if (payload.isEmpty()) return ByteArray(0)
        val padding = payload[0].toInt() and 0xff
        val end = (payload.size - padding).coerceAtLeast(1)
        return if (end <= 1) ByteArray(0) else payload.copyOfRange(1, end)
    }

    private companion object {
        val PREFACE = "PRI * HTTP/2.0\r\n\r\nSM\r\n\r\n".encodeToByteArray()
        const val FRAME_HEADER_SIZE = 9
        const val TYPE_DATA = 0
        const val TYPE_HEADERS = 1
        const val TYPE_RST_STREAM = 3
        const val TYPE_CONTINUATION = 9
        const val FLAG_END_STREAM = 0x1
        const val FLAG_END_HEADERS = 0x4
        const val FLAG_PADDED = 0x8
        const val FLAG_PRIORITY = 0x20
        const val MAX_HEADER_OUTPUT = 1024 * 1024
    }
}
