package dev.lynx.network

import dev.lynx.daemon.*
import dev.lynx.model.*
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.MutableSharedFlow
import java.io.BufferedInputStream
import java.io.ByteArrayOutputStream
import java.net.ServerSocket
import java.net.Socket
import java.nio.file.Files
import java.nio.file.Path
import javax.net.ssl.SSLSocket
import javax.net.ssl.SSLContext
import java.time.Instant
import java.util.UUID
import java.util.concurrent.ConcurrentHashMap
import java.util.concurrent.ExecutorService
import java.util.concurrent.Executors
import java.util.zip.GZIPInputStream
import java.io.ByteArrayInputStream

/** HTTP/1 forward proxy with ephemeral, session-scoped TLS MITM for CONNECT. */
class HttpProxyCapture(
    private val timeline: EvidenceTimeline,
    private val sessionId: SessionId,
    private val deviceSerial: String,
    private val packageName: String,
    private val processId: Int?,
) : NetworkCaptureSource {
    private val eventsFlow = MutableSharedFlow<NetworkDomainEvent>(extraBufferCapacity = 256)
    private val exchanges = ConcurrentHashMap<RequestId, NetworkExchange>()
    private var executor: ExecutorService = Executors.newCachedThreadPool()
    private var server: ServerSocket? = null
    private val tlsMitm = TlsMitmContextFactory()
    /** CA certificate to install only in an explicitly opted-in debuggable client. */
    val caCertificatePem: String get() = tlsMitm.caCertificatePem()
    /** Host path written for explicit trust configuration of a debug client. */
    private val caPath: Path? = runCatching {
        val dir = Path.of(System.getProperty("user.home"), ".lynx", "certs")
        Files.createDirectories(dir)
        val path = dir.resolve("${sessionId.value}.pem")
        Files.writeString(path, caCertificatePem)
        path
    }.getOrNull()
    override val caCertificatePath: String? get() = caPath?.toString()
    var port: Int = 0; private set
    override val endpoint: String? get() = if (port == 0) null else "${server?.inetAddress?.hostAddress}:$port"

    /** Point-in-time exchange list for `network list`. */
    fun list(): List<NetworkExchange> = exchanges.values.sortedByDescending { it.meta.observedAt }
    /** Complete exchange lookup, including failed exchanges. */
    fun get(requestId: RequestId): NetworkExchange? = exchanges[requestId]
    override fun exchanges(): List<NetworkExchange> = list()
    override fun exchange(requestId: RequestId): NetworkExchange? = get(requestId)

    override suspend fun start(config: NetworkCaptureConfig) {
        check(server == null) { "proxy already running" }
        if (executor.isShutdown) executor = Executors.newCachedThreadPool()
        server = ServerSocket(config.listenPort, 50, java.net.InetAddress.getByName(config.listenHost)).also {
            port = it.localPort; executor.submit { acceptLoop(it, config) }
        }
    }
    override suspend fun stop() {
        server?.close()
        server = null
        port = 0
        // Do not leak the accept loop or per-request workers between sessions.
        executor.shutdownNow()
    }
    override fun events(): Flow<NetworkDomainEvent> = eventsFlow
    override suspend fun capabilities() = NetworkCapabilities(
        httpsMitm = true, maxBodyBytes = 0,
        caFingerprint = tlsMitm.caManager().show().fingerprint,
        caCertificatePath = caPath?.toString(),
        caTrustStatus = tlsMitm.caManager().show().trustStatus,
        limitations = listOfNotNull("HTTP/1 forwarding only", "Android apps must trust the Lynx session CA; certificate pinning may fail", caPath?.let { "session CA PEM: $it" }, "HTTP/2 and WebSocket capture are not enabled", "Android system proxy configuration is managed by a separate adapter"),
    )

    private fun acceptLoop(listener: ServerSocket, config: NetworkCaptureConfig) {
        try { while (!listener.isClosed) { val client = listener.accept(); executor.submit { handle(client, config) } } } catch (_: Exception) { }
    }

    private fun handle(client: Socket, config: NetworkCaptureConfig) {
        client.use { socket ->
            val input = socket.getInputStream().buffered(); val output = socket.getOutputStream().buffered()
            val raw = readHeaders(input) ?: return
            val lines = raw.toString(Charsets.ISO_8859_1).split("\r\n")
            val parts = lines.firstOrNull()?.split(" ", limit = 3) ?: return
            if (parts.size < 3) return
            val method = parts[0]; val url = parts[1]; val headers = parseHeaders(lines.drop(1))
            val id = RequestId("req_${UUID.randomUUID()}"); val started = System.currentTimeMillis()
            val body = readBody(input, headers.value("Content-Length")?.toIntOrNull(), headers.value("Transfer-Encoding")?.contains("chunked", true) == true, config.maxBodyBytes)
            val request = NetworkRequest(method, url, headers, body.text())
            eventsFlow.tryEmit(NetworkDomainEvent.Started(id.value))
            if (method.equals("CONNECT", true)) {
                handleConnect(client, output, url, id, started, config); return
            }
            val uri = runCatching { java.net.URI(url) }.getOrNull()
            val host = uri?.host ?: headers.entries.firstOrNull { it.key.equals("Host", true) }?.value?.substringBefore(":")
            if (host == null) { writeError(output, 400, "absolute URL or Host header required"); fail(id, request, started, "INVALID_REQUEST", "absolute URL or Host header required"); return }
            val targetPort = uri?.port?.takeIf { it > 0 } ?: 80
            runCatching {
                Socket(host, targetPort).use { upstream ->
                    val up = upstream.getOutputStream().buffered(); val down = upstream.getInputStream().buffered()
                    val path = ((uri?.rawPath ?: "/").ifEmpty { "/" }) + (uri?.rawQuery?.let { "?$it" } ?: "")
                    up.write("$method $path HTTP/1.1\r\n".toByteArray())
                    headers.filterKeys { !it.equals("Proxy-Connection", true) && !it.equals("Connection", true) }.forEach { (k, v) -> up.write("$k: $v\r\n".toByteArray()) }
                    up.write("Connection: close\r\n\r\n".toByteArray()); up.write(body); up.flush()
                    val responseRaw = readHeaders(down) ?: error("upstream closed before response headers")
                    val responseLines = responseRaw.toString(Charsets.ISO_8859_1).split("\r\n")
                    val statusLine = responseLines.firstOrNull() ?: "HTTP/1.1 502 Bad Gateway"
                    val status = responseLines.firstOrNull()?.split(" ")?.getOrNull(1)?.toIntOrNull() ?: 0
                    val responseHeaders = parseHeaders(responseLines.drop(1))
                    val responseBody = readResponseBody(
                        down,
                        responseHeaders.value("Content-Length")?.toIntOrNull(),
                        responseHeaders.value("Transfer-Encoding")?.contains("chunked", true) == true,
                        config.maxBodyBytes,
                    )
                    writeResponse(output, statusLine, responseHeaders, responseBody)
                    val capturedBody = decodeBody(responseHeaders, responseBody)
                    record(id, NetworkExchange(meta(), id, request, NetworkResponse(status, responseHeaders, capturedBody.text()), null, timing(started), NetworkCaptureMetadata(false, body.size.toLong(), false)))
                    eventsFlow.tryEmit(NetworkDomainEvent.Completed(id.value))
                }
            }.onFailure { fail(id, request, started, "UPSTREAM_ERROR", it.message ?: "proxy failure") }
        }
    }

    private fun handleConnect(client: Socket, output: java.io.BufferedOutputStream, authority: String, id: RequestId, started: Long, config: NetworkCaptureConfig) {
        val host = authority.substringBeforeLast(':').trim().ifBlank { authority }
        val targetPort = authority.substringAfterLast(':', "443").toIntOrNull() ?: 443
        val requestHeaders = try {
            output.write("HTTP/1.1 200 Connection Established\r\nProxy-Agent: lynx\r\n\r\n".toByteArray()); output.flush()
            client.soTimeout = 5_000
            val serverTls = tlsMitm.serverContext(host).socketFactory.createSocket(client, host, targetPort, false) as SSLSocket
            serverTls.use { downstream ->
                downstream.useClientMode = false
                downstream.startHandshake()
                val input = downstream.inputStream.buffered(); val innerOutput = downstream.outputStream.buffered()
                val raw = readHeaders(input) ?: error("TLS client closed before request")
                val lines = raw.toString(Charsets.ISO_8859_1).split("\r\n")
                val parts = lines.firstOrNull()?.split(" ", limit = 3) ?: error("invalid TLS request")
                if (parts.size < 3) error("invalid TLS request line")
                val method = parts[0]; val path = parts[1]; val headers = parseHeaders(lines.drop(1))
                val body = readBody(input, headers.value("Content-Length")?.toIntOrNull(), headers.value("Transfer-Encoding")?.contains("chunked", true) == true, config.maxBodyBytes)
                val request = NetworkRequest(method, "https://$host$path", headers, body.text())
                val upstream = (SSLContext.getDefault().socketFactory.createSocket(host, targetPort) as SSLSocket)
                upstream.use { secure ->
                    secure.startHandshake()
                    val up = secure.outputStream.buffered(); val down = secure.inputStream.buffered()
                    up.write("$method $path HTTP/1.1\r\n".toByteArray())
                    headers.filterKeys { !it.equals("Proxy-Connection", true) && !it.equals("Connection", true) }.forEach { (k, v) -> up.write("$k: $v\r\n".toByteArray()) }
                    up.write("Connection: close\r\n\r\n".toByteArray()); up.write(body); up.flush()
                    val responseRaw = readHeaders(down) ?: error("upstream closed before response headers")
                    val responseLines = responseRaw.toString(Charsets.ISO_8859_1).split("\r\n")
                    val statusLine = responseLines.firstOrNull() ?: "HTTP/1.1 502 Bad Gateway"
                    val status = responseLines.firstOrNull()?.split(" ")?.getOrNull(1)?.toIntOrNull() ?: 0
                    val responseHeaders = parseHeaders(responseLines.drop(1))
                    val responseBody = readResponseBody(down, responseHeaders.value("Content-Length")?.toIntOrNull(), responseHeaders.value("Transfer-Encoding")?.contains("chunked", true) == true, config.maxBodyBytes)
                    writeResponse(innerOutput, statusLine, responseHeaders, responseBody)
                    val capturedBody = decodeBody(responseHeaders, responseBody)
                    record(id, NetworkExchange(meta(), id, request, NetworkResponse(status, responseHeaders, capturedBody.text()), null, timing(started), NetworkCaptureMetadata(true, body.size.toLong(), false)))
                    eventsFlow.tryEmit(NetworkDomainEvent.Completed(id.value))
                }
            }
            Unit
        } catch (error: Throwable) {
            fail(id, NetworkRequest("CONNECT", "https://$host", emptyMap(), null), started, "HTTPS_MITM_ERROR", error.message ?: "HTTPS interception failed")
        }
        requestHeaders
    }

    private fun record(id: RequestId, exchange: NetworkExchange) { exchanges[id] = exchange; timeline.append(exchange) }
    private fun fail(id: RequestId, request: NetworkRequest, started: Long, kind: String, message: String) {
        record(id, NetworkExchange(meta(), id, request, null, NetworkFailure(kind, message), timing(started), NetworkCaptureMetadata(false, request.body?.toByteArray()?.size?.toLong() ?: 0, null)))
        eventsFlow.tryEmit(NetworkDomainEvent.Failed(id.value, message))
    }
    private fun meta() = EvidenceMeta(EvidenceId("ev_${UUID.randomUUID()}"), sessionId, Instant.now(), EvidenceSource.NETWORK, deviceSerial, packageName, processId)
    private fun timing(started: Long): NetworkTiming { val now = System.currentTimeMillis(); return NetworkTiming(started, now, now - started) }
    private fun parseHeaders(lines: List<String>) = lines.filter { it.contains(":") }.associate { it.substringBefore(":").trim() to it.substringAfter(":").trim() }
    private fun Map<String, String>.value(name: String): String? = entries.firstOrNull { it.key.equals(name, true) }?.value
    /** Reframe the dechunked body so clients do not parse its first byte as a chunk size. */
    private fun writeResponse(output: java.io.BufferedOutputStream, statusLine: String, headers: Map<String, String>, body: ByteArray) {
        output.write("$statusLine\r\n".toByteArray())
        headers.filterKeys { !it.equals("Transfer-Encoding", true) && !it.equals("Content-Length", true) && !it.equals("Connection", true) }
            .forEach { (k, v) -> output.write("$k: $v\r\n".toByteArray()) }
        output.write("Content-Length: ${body.size}\r\nConnection: close\r\n\r\n".toByteArray())
        output.write(body); output.flush()
    }
    /** Decode content for the structured event while preserving encoding on the wire. */
    private fun decodeBody(headers: Map<String, String>, body: ByteArray): ByteArray =
        if (headers.value("Content-Encoding")?.contains("gzip", true) == true) {
            runCatching { GZIPInputStream(ByteArrayInputStream(body)).readBytes() }.getOrDefault(body)
        } else body
    private fun writeError(output: java.io.BufferedOutputStream, status: Int, message: String) { output.write("HTTP/1.1 $status $message\r\nConnection: close\r\nContent-Length: 0\r\n\r\n".toByteArray()); output.flush() }

    private fun readHeaders(input: BufferedInputStream): ByteArray? {
        val out = ByteArrayOutputStream(); var matched = 0
        while (out.size() < 64 * 1024) { val v = input.read(); if (v < 0) return null; out.write(v); matched = when { matched == 0 && v == 13 -> 1; matched == 1 && v == 10 -> 2; matched == 2 && v == 13 -> 3; matched == 3 && v == 10 -> 4; else -> 0 }; if (matched == 4) return out.toByteArray() }
        return null
    }
    private fun readBody(input: BufferedInputStream, expected: Int?, chunked: Boolean, configuredLimit: Long): ByteArray {
        val limit = if (configuredLimit <= 0) Int.MAX_VALUE else configuredLimit.coerceAtMost(Int.MAX_VALUE.toLong()).toInt()
        if (chunked) return readChunked(input, limit); if (expected != null) return input.readNBytes(expected.coerceAtMost(limit)); return byteArrayOf()
    }

    /** The upstream socket is deliberately Connection: close, so an absent length is EOF-delimited. */
    private fun readResponseBody(input: BufferedInputStream, expected: Int?, chunked: Boolean, configuredLimit: Long): ByteArray {
        if (expected != null || chunked) return readBody(input, expected, chunked, configuredLimit)
        val limit = if (configuredLimit <= 0) Int.MAX_VALUE else configuredLimit.coerceAtMost(Int.MAX_VALUE.toLong()).toInt()
        return input.readNBytes(limit)
    }
    private fun readChunked(input: BufferedInputStream, limit: Int): ByteArray {
        val out = ByteArrayOutputStream()
        while (out.size() < limit) { val line = readLine(input) ?: break; val size = line.substringBefore(';').trim().toIntOrNull(16) ?: break; if (size == 0) { readLine(input); break }; val bytes = input.readNBytes(size.coerceAtMost(limit - out.size())); out.write(bytes); input.read(); input.read(); if (bytes.size < size) break }
        return out.toByteArray()
    }
    private fun readLine(input: BufferedInputStream): String? { val out = ByteArrayOutputStream(); var prev = -1; while (true) { val v = input.read(); if (v < 0) return null; if (prev == 13 && v == 10) return out.toString(Charsets.ISO_8859_1); if (prev >= 0) out.write(prev); prev = v } }
    private fun ByteArray.text(): String? = takeIf { it.isNotEmpty() }?.toString(Charsets.UTF_8)
}
