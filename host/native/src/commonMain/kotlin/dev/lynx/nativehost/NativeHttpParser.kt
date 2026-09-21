package dev.lynx.nativehost

/** Small allocation-bounded HTTP/1 parser shared by native proxy adapters. */
object NativeHttpParser {
    data class Request(
        val method: String,
        val url: String,
        val version: String,
        val headers: Map<String, String>,
        val body: String,
    )

    data class Response(
        val version: String,
        val status: Int,
        val reason: String,
        val headers: Map<String, List<String>>,
        val body: String,
    )

    fun parseRequest(raw: String): Request {
        val (head, body) = split(raw)
        val lines = head.split("\r\n")
        val start = lines.firstOrNull()?.split(' ', limit = 3)
            ?: error("HTTP request is empty")
        require(start.size == 3) { "Malformed HTTP request line" }
        val headers = parseHeaders(lines.drop(1)).mapValues { it.value.last() }
        return Request(start[0], start[1], start[2], headers, body)
    }

    fun parseResponse(raw: String): Response {
        val (head, body) = split(raw)
        val lines = head.split("\r\n")
        val start = lines.firstOrNull()?.split(' ', limit = 3)
            ?: error("HTTP response is empty")
        require(start.size >= 2) { "Malformed HTTP response line" }
        return Response(
            version = start[0],
            status = start[1].toIntOrNull() ?: error("Malformed HTTP status"),
            reason = start.getOrNull(2).orEmpty(),
            headers = parseHeaders(lines.drop(1)),
            body = body,
        )
    }

    /** Converts a forward-proxy request line to the origin-form expected upstream. */
    fun originFormRequest(raw: String, request: Request): String {
        val lineEnd = raw.indexOf("\r\n")
        if (lineEnd < 0 || (!request.url.startsWith("http://") && !request.url.startsWith("https://"))) return raw
        val withoutScheme = request.url.substringAfter("://")
        val slash = withoutScheme.indexOf('/')
        val path = if (slash >= 0) withoutScheme.substring(slash) else "/"
        return "${request.method} $path ${request.version}" + raw.substring(lineEnd)
    }

    private fun split(raw: String): Pair<String, String> {
        val separator = raw.indexOf("\r\n\r\n")
        require(separator >= 0) { "HTTP headers are incomplete" }
        return raw.substring(0, separator) to raw.substring(separator + 4)
    }

    private fun parseHeaders(lines: List<String>): Map<String, List<String>> = buildMap {
        lines.filter { it.isNotEmpty() }.forEach { line ->
            val separator = line.indexOf(':')
            require(separator > 0) { "Malformed HTTP header" }
            val name = line.substring(0, separator)
            val value = line.substring(separator + 1).trim()
            val existing = this[name].orEmpty()
            this[name] = existing + value
        }
    }
}
