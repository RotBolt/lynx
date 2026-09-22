package dev.lynx.nativehost

/** Removes HTTP/1 chunk transfer framing from captured bodies without changing forwarded bytes. */
internal object NativeHttpBodyDecoder {
    fun decode(headers: Map<String, List<String>>, body: String): String {
        val transferEncoding = headers.entries
            .firstOrNull { (name, _) -> name.equals("Transfer-Encoding", ignoreCase = true) }
            ?.value
            .orEmpty()
            .flatMap { it.split(',') }
            .map(String::trim)
        if (transferEncoding.none { it.equals("chunked", ignoreCase = true) }) return body

        return decodeChunked(body) ?: body
    }

    private fun decodeChunked(body: String): String? {
        val bytes = body.encodeToByteArray()
        val decoded = ByteArray(bytes.size)
        var decodedSize = 0
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
                    if (trailerEnd == cursor) return decoded.copyOf(decodedSize).decodeToString()
                    cursor = trailerEnd + CRLF_SIZE
                }
            }

            if (chunkSize > Int.MAX_VALUE) return null
            val payloadSize = chunkSize.toInt()
            if (payloadSize > bytes.size - cursor - CRLF_SIZE) return null
            val chunkEnd = cursor + payloadSize
            if (bytes[chunkEnd] != CR || bytes[chunkEnd + 1] != LF) return null
            bytes.copyInto(decoded, decodedSize, cursor, chunkEnd)
            decodedSize += payloadSize
            cursor = chunkEnd + CRLF_SIZE
        }
    }

    private fun ByteArray.indexOfCrlf(start: Int): Int {
        for (index in start until size - 1) {
            if (this[index] == CR && this[index + 1] == LF) return index
        }
        return -1
    }

    private const val CRLF_SIZE = 2
    private val CR = '\r'.code.toByte()
    private val LF = '\n'.code.toByte()
}
