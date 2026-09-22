package dev.lynx.nativehost

/** Normalizes captured payloads for readable evidence without changing forwarded wire bytes. */
internal object NativeHttpBodyDecoder {
    fun decode(headers: Map<String, String>, body: ByteArray): ByteArray {
        val transferEncoding = headers.value("Transfer-Encoding")
            ?.split(',')?.map(String::trim).orEmpty()
        var decoded = if (transferEncoding.any { it.equals("chunked", ignoreCase = true) }) {
            decodeChunked(body) ?: body
        } else {
            body
        }

        val contentEncoding = headers.value("Content-Encoding")
            ?.split(',')?.map(String::trim).orEmpty()
        for (encoding in contentEncoding.asReversed()) {
            decoded = when {
                encoding.equals("gzip", ignoreCase = true) -> NativeGzipDecoder.decode(decoded) ?: decoded
                encoding.equals("br", ignoreCase = true) -> NativeBrotliDecoder.decode(decoded) ?: decoded
                else -> decoded
            }
        }
        return decoded
    }

    private fun decodeChunked(bytes: ByteArray): ByteArray? {
        val decoded = mutableListOf<Byte>()
        var cursor = 0

        while (true) {
            val lineEnd = bytes.indexOfCrlf(cursor)
            if (lineEnd < 0) return null
            val sizeLine = bytes.copyOfRange(cursor, lineEnd).decodeToString()
            val chunkSize = sizeLine.substringBefore(';').trim().toLongOrNull(16) ?: return null
            cursor = lineEnd + CRLF_SIZE

            if (chunkSize == 0L) {
                while (true) {
                    val trailerEnd = bytes.indexOfCrlf(cursor)
                    if (trailerEnd < 0) return null
                    if (trailerEnd == cursor) return decoded.toByteArray()
                    cursor = trailerEnd + CRLF_SIZE
                }
            }

            if (chunkSize > Int.MAX_VALUE) return null
            val payloadSize = chunkSize.toInt()
            if (payloadSize > bytes.size - cursor - CRLF_SIZE) return null
            val chunkEnd = cursor + payloadSize
            if (bytes[chunkEnd] != CR || bytes[chunkEnd + 1] != LF) return null
            for (index in cursor until chunkEnd) decoded += bytes[index]
            cursor = chunkEnd + CRLF_SIZE
        }
    }

    private fun ByteArray.indexOfCrlf(start: Int): Int {
        for (index in start until size - 1) {
            if (this[index] == CR && this[index + 1] == LF) return index
        }
        return -1
    }

    private fun Map<String, String>.value(name: String): String? =
        entries.firstOrNull { it.key.equals(name, ignoreCase = true) }?.value

    private const val CRLF_SIZE = 2
    private val CR = '\r'.code.toByte()
    private val LF = '\n'.code.toByte()
}
