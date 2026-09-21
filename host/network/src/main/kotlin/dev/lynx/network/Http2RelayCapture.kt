package dev.lynx.network

import io.netty.buffer.ByteBuf
import io.netty.buffer.Unpooled
import io.netty.channel.ChannelHandlerContext
import io.netty.channel.embedded.EmbeddedChannel
import io.netty.handler.codec.http2.DefaultHttp2FrameReader
import io.netty.handler.codec.http2.Http2Exception
import io.netty.handler.codec.http2.Http2Flags
import io.netty.handler.codec.http2.Http2FrameAdapter
import io.netty.handler.codec.http2.Http2FrameListener
import io.netty.handler.codec.http2.Http2Headers
import io.netty.handler.codec.http2.Http2Settings
import java.io.ByteArrayOutputStream

/**
 * HPACK/frame decoder used only for observation. It intentionally does not
 * attach a Http2Connection, so the two independent HPACK directions cannot
 * reject a valid tunneled stream because of local stream bookkeeping.
 */
internal class Http2RelayCapture {
    data class Exchange(
        val streamId: Int,
        val method: String,
        val url: String,
        val requestHeaders: Map<String, String>,
        val requestBody: ByteArray,
        val status: Int,
        val responseHeaders: Map<String, String>,
        val responseBody: ByteArray,
    )

    private data class Stream(
        var method: String = "UNKNOWN",
        var url: String = "https:///",
        var requestHeaders: Map<String, String> = emptyMap(),
        val requestBody: ByteArrayOutputStream = ByteArrayOutputStream(),
        var status: Int = 0,
        var responseHeaders: Map<String, String> = emptyMap(),
        val responseBody: ByteArrayOutputStream = ByteArrayOutputStream(),
    )

    private val streams = linkedMapOf<Int, Stream>()
    private val requestDecoder = Decoder(true)
    private val responseDecoder = Decoder(false)
    private val requestPreface = ByteArrayOutputStream()
    private var requestPrefaceConsumed = false

    fun acceptRequest(bytes: ByteArray): List<Exchange> {
        if (requestPrefaceConsumed) return requestDecoder.accept(bytes)
        requestPreface.write(bytes)
        if (requestPreface.size() < CLIENT_PREFACE.size) return emptyList()
        val all = requestPreface.toByteArray()
        requestPrefaceConsumed = true
        require(all.copyOfRange(0, CLIENT_PREFACE.size).contentEquals(CLIENT_PREFACE)) { "HTTP/2 client preface missing or corrupt" }
        return requestDecoder.accept(all.copyOfRange(CLIENT_PREFACE.size, all.size))
    }

    fun acceptResponse(bytes: ByteArray): List<Exchange> = responseDecoder.accept(bytes)
    fun close() { requestDecoder.close(); responseDecoder.close() }

    private inner class Decoder(private val requestSide: Boolean) {
        private var context: ChannelHandlerContext? = null
        private val reader = DefaultHttp2FrameReader()
        private val channel = EmbeddedChannel(object : io.netty.channel.ChannelInboundHandlerAdapter() {
            override fun handlerAdded(ctx: ChannelHandlerContext) { context = ctx }
        })
        private val listener: Http2FrameListener = object : Http2FrameAdapter() {
            override fun onHeadersRead(ctx: ChannelHandlerContext, streamId: Int, headers: Http2Headers, padding: Int, endOfStream: Boolean) {
                val values = headers.asSequence().associate { it.key.toString() to it.value.toString() }
                val stream = streams.getOrPut(streamId) { Stream() }
                if (requestSide) {
                    stream.method = values[":method"] ?: stream.method
                    val scheme = values[":scheme"] ?: "https"
                    val authority = values[":authority"] ?: ""
                    val path = values[":path"] ?: "/"
                    stream.url = "$scheme://$authority$path"
                    stream.requestHeaders = values
                } else {
                    stream.status = values[":status"]?.toIntOrNull() ?: stream.status
                    stream.responseHeaders = values
                    if (endOfStream) completed.add(exchange(streamId, stream))
                }
            }

            override fun onDataRead(ctx: ChannelHandlerContext, streamId: Int, data: ByteBuf, padding: Int, endOfStream: Boolean): Int {
                val bytes = ByteArray(data.readableBytes())
                data.getBytes(data.readerIndex(), bytes)
                val stream = streams.getOrPut(streamId) { Stream() }
                if (requestSide) stream.requestBody.write(bytes) else stream.responseBody.write(bytes)
                if (endOfStream && !requestSide) completed.add(exchange(streamId, stream))
                return data.readableBytes() + padding
            }
        }
        private var completed = mutableListOf<Exchange>()

        fun accept(bytes: ByteArray): List<Exchange> {
            if (bytes.isEmpty()) return emptyList()
            completed = mutableListOf()
            val input = Unpooled.wrappedBuffer(bytes)
            try {
                reader.readFrame(context ?: error("HTTP/2 decoder context unavailable"), input, listener)
            } finally { input.release() }
            return completed
        }

        fun close() { reader.close(); channel.finishAndReleaseAll() }
        private fun exchange(id: Int, stream: Stream) = Exchange(
            id, stream.method, stream.url, stream.requestHeaders,
            stream.requestBody.toByteArray(), stream.status, stream.responseHeaders,
            stream.responseBody.toByteArray(),
        )
    }

    private companion object {
        val CLIENT_PREFACE = "PRI * HTTP/2.0\r\n\r\nSM\r\n\r\n".toByteArray(Charsets.ISO_8859_1)
    }
}
