package dev.lynx.network

import io.netty.channel.embedded.EmbeddedChannel
import io.netty.handler.codec.http2.Http2DataFrame
import io.netty.handler.codec.http2.Http2Frame
import io.netty.handler.codec.http2.Http2FrameCodecBuilder
import io.netty.handler.codec.http2.Http2HeadersFrame
import io.netty.handler.codec.http2.Http2ResetFrame
import java.io.ByteArrayOutputStream

/** Decodes HTTP/2 frames while the proxy relays the original bytes unchanged. */
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
    private val requestDecoder = Decoder(server = true)
    private val responseDecoder = Decoder(server = false)

    fun acceptRequest(bytes: ByteArray): List<Exchange> = requestDecoder.accept(bytes)
    fun acceptResponse(bytes: ByteArray): List<Exchange> = responseDecoder.accept(bytes)
    fun close() { requestDecoder.close(); responseDecoder.close() }

    private inner class Decoder(private val server: Boolean) {
        private val channel = EmbeddedChannel(
            if (server) Http2FrameCodecBuilder.forServer().build()
            else Http2FrameCodecBuilder.forClient().build(),
        )

        fun accept(bytes: ByteArray): List<Exchange> {
            if (bytes.isEmpty()) return emptyList()
            channel.writeInbound(io.netty.buffer.Unpooled.wrappedBuffer(bytes))
            val completed = mutableListOf<Exchange>()
            while (true) {
                val frame = channel.readInbound<Http2Frame>() ?: break
                when (frame) {
                    is Http2HeadersFrame -> headers(frame, completed)
                    is Http2DataFrame -> data(frame, completed)
                    is Http2ResetFrame -> streams.remove(frame.stream().id())
                }
            }
            return completed
        }

        fun close() { channel.finishAndReleaseAll() }

        private fun headers(frame: Http2HeadersFrame, completed: MutableList<Exchange>) {
            val id = frame.stream().id()
            val values: Map<String, String> = frame.headers().asSequence().associate { entry ->
                entry.key.toString() to entry.value.toString()
            }
            val stream = streams.getOrPut(id) { Stream() }
            if (server) {
                stream.method = values[":method"] ?: stream.method
                val authority = values[":authority"] ?: ""
                val scheme = values[":scheme"] ?: "https"
                val path = values[":path"] ?: "/"
                stream.url = "$scheme://$authority$path"
                stream.requestHeaders = values
                if (frame.isEndStream) completed.add(exchange(id, stream))
            } else {
                stream.status = values[":status"]?.toIntOrNull() ?: stream.status
                stream.responseHeaders = values
                if (frame.isEndStream) completed.add(exchange(id, stream))
            }
        }

        private fun data(frame: Http2DataFrame, completed: MutableList<Exchange>) {
            val id = frame.stream().id()
            val stream = streams.getOrPut(id) { Stream() }
            val bytes = ByteArray(frame.content().readableBytes())
            frame.content().getBytes(frame.content().readerIndex(), bytes)
            if (server) stream.requestBody.write(bytes) else stream.responseBody.write(bytes)
            if (frame.isEndStream) completed.add(exchange(id, stream))
        }

        private fun exchange(id: Int, stream: Stream) = Exchange(
            id, stream.method, stream.url, stream.requestHeaders,
            stream.requestBody.toByteArray(), stream.status, stream.responseHeaders,
            stream.responseBody.toByteArray(),
        )
    }
}
