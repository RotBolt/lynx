package dev.lynx.nativehost

import dev.lynx.model.*
import kotlinx.cinterop.*
import platform.posix.*

/** Native, process-independent proxy. Protocol parsing stays in the shared POSIX layer while TLS
 * and HPACK are provided by the platform's native adapters. */
@OptIn(ExperimentalForeignApi::class)
class PosixNativeNetworkInspector(
    private val store: PosixNativeNetworkStateStore = PosixNativeNetworkStateStore(),
    private val sessions: NativeSessionStore = PosixNativeSessionStore(),
    private val processes: NativeProcessRunner = PosixProcessRunner(),
    private val certificates: PosixNativeCertificateAuthority = PosixNativeCertificateAuthority(processes),
) : NativeNetworkInspector {
    override fun execute(command: NetworkCommand): NetworkCommandResult = when (command) {
        is NetworkCommand.Start -> start(command.settings)
        NetworkCommand.Stop -> { restoreDeviceProxy(); store.clearRunning(); store.clearWorkerReady(); NetworkCommandResult.Stopped }
        is NetworkCommand.List -> NetworkCommandResult.Exchanges(store.list(command.filter))
        is NetworkCommand.Get -> store.get(command.requestId)?.let(NetworkCommandResult::Exchange)
            ?: error("network exchange not found: ${command.requestId.value}")
        NetworkCommand.Doctor -> NetworkCommandResult.Diagnostics(capabilities())
    }

    private fun capabilities() = NetworkCapabilities(
        httpsMitm = true,
        supportedProtocols = listOf("HTTP/1.1", "HTTPS", "HTTP/2", "WebSocket"),
        limitations = listOf("TLS WebSocket capture is not enabled"),
        proxyEndpoint = store.endpoint(),
        proxyStatus = if (store.isRunning()) "running" else "stopped",
        caCertificatePath = "${certificates.ensureCa()}",
        caFingerprint = certificates.fingerprint(),
        caTrustStatus = "user_installation_required",
    )

    private fun start(settings: NetworkCaptureSettings): NetworkCommandResult.Started {
        val port = if (settings.listenPort == 0) 62006 else settings.listenPort
        val endpoint = "${settings.listenHost}:$port"
        val caps = capabilities().copy(proxyEndpoint = endpoint, proxyStatus = "starting")
        val previousProxy = applyDeviceProxy(port)
        store.clearWorkerReady()
        store.setRunning(endpoint, caps, previousProxy)
        // Launch the same native executable as a detached worker. Installers put `lynx` on PATH;
        // tests and embedders can provide LYNX_EXECUTABLE explicitly.
        val executable = getenv("LYNX_EXECUTABLE")?.toKString()?.takeIf(String::isNotBlank)
            ?: nativeExecutablePath()
            ?: "lynx"
        PosixProcessRunner().run(listOf("sh", "-c", "(nohup '$executable' network worker $port >/dev/null 2>&1 </dev/null &)"))
        repeat(40) {
            if (store.workerReady()) {
                val running = caps.copy(proxyStatus = "running")
                store.setRunning(endpoint, running, previousProxy)
                return NetworkCommandResult.Started(endpoint, running)
            }
            usleep(50_000u)
        }
        store.clearRunning()
        throw IllegalStateException("native network worker did not become ready; install lynx on PATH or set LYNX_EXECUTABLE")
    }

    private fun applyDeviceProxy(port: Int): String? {
        val session = sessions.load() ?: return null
        val prefix = listOf("adb", "-s", session.deviceSerial, "shell", "settings")
        val previous = processes.run(prefix + listOf("get", "global", "http_proxy")).stdout.trim().takeIf { it.isNotBlank() && it != "null" }
        processes.run(prefix + listOf("put", "global", "http_proxy", "10.0.2.2:$port"))
        return previous
    }

    private fun restoreDeviceProxy() {
        val session = sessions.load() ?: return
        val value = store.previousProxy()?.takeIf { it.isNotBlank() } ?: ":0"
        processes.run(listOf("adb", "-s", session.deviceSerial, "shell", "settings", "put", "global", "http_proxy", value))
    }

    /** Worker entrypoint. It accepts cleartext HTTP proxy requests and appends complete exchanges. */
    fun worker(port: Int) {
        val server = socket(AF_INET, SOCK_STREAM, 0)
        require(server >= 0) { "unable to create native proxy socket" }
        memScoped {
            val address = alloc<sockaddr_in>()
            val reuse = alloc<IntVar>()
            reuse.value = 1
            setsockopt(server, SOL_SOCKET, SO_REUSEADDR, reuse.ptr, sizeOf<IntVar>().convert())
            address.sin_family = AF_INET.convert()
            address.sin_port = networkShort(port)
            address.sin_addr.s_addr = 0u
            require(bind(server, address.ptr.reinterpret(), sizeOf<sockaddr_in>().convert()) == 0) { "unable to bind native proxy" }
            require(listen(server, 64) == 0) { "unable to listen on native proxy" }
            fcntl(server, F_SETFL, fcntl(server, F_GETFL) or O_NONBLOCK)
        }
        store.markWorkerReady(port)
        try {
            while (store.isRunning()) {
                val client = accept(server, null, null)
                if (client >= 0) handle(client) else usleep(50_000u)
            }
        } finally {
            store.clearWorkerReady()
            close(server)
        }
    }

    private fun handle(client: Int) {
        // The listener is non-blocking so stop can observe state; accepted
        // sockets must be blocking for TLS handshakes and complete bodies.
        fcntl(client, F_SETFL, fcntl(client, F_GETFL) and O_NONBLOCK.inv())
        val started = getTimeMillis()
        try {
            val raw = readHeaders(client)
            val request = NativeHttpParser.parseRequest(raw)
            val requestId = RequestId("req_${randomId()}")
            if (request.method.equals("CONNECT", true)) {
                handleConnect(client, request.url, requestId, started)
                return
            }
            val target = parseTarget(request.url, request.headers["Host"] ?: request.headers["host"])
            val upstream = connect(target.first, target.second)
            try {
                // A forward proxy receives absolute-form targets, while the origin
                // server expects origin-form (path + query) request lines.
                sendBytes(upstream, NativeHttpParser.originFormRequest(raw, request).encodeToByteArray())
                val responseHead = readHeaders(upstream)
                val responseHeadValue = NativeHttpParser.parseResponse(responseHead)
                if (responseHeadValue.status == 101 && responseHeadValue.headers.keys.any { it.equals("Upgrade", true) }) {
                    sendBytes(client, responseHead.encodeToByteArray())
                    relayPlainWebSocket(client, upstream, request, requestId, responseHeadValue, started)
                    return
                }
                val responseBody = readUntilClose(upstream)
                val responseRaw = responseHead + responseBody
                sendBytes(client, responseRaw.encodeToByteArray())
                val response = responseHeadValue.copy(body = responseBody)
                store.append(exchange(request, requestId, response, null, started))
            } finally { close(upstream) }
        } catch (t: Throwable) {
            // Keep failures visible to an agent; malformed/failed requests are evidence too.
            runCatching { store.append(failureExchange(client, started, t.message ?: t::class.simpleName.orEmpty())) }
        } finally { close(client) }
    }

    private fun handleConnect(client: Int, authority: String, requestId: RequestId, started: Long) {
        val host = authority.substringBeforeLast(':').ifBlank { authority }
        val port = authority.substringAfterLast(':', "443").toIntOrNull() ?: 443
        try {
            sendBytes(client, "HTTP/1.1 200 Connection Established\r\nProxy-Agent: lynx\r\n\r\n".encodeToByteArray())
            val leaf = certificates.ensureLeaf(host)
            val upstreamFd = connect(host, port)
            val upstream = nativeTlsProvider().client(upstreamFd)
            val downstream = nativeTlsProvider().server(client, leaf.certificate, leaf.privateKey, enableHttp2 = upstream.applicationProtocol == "h2")
            try {
                if (downstream.applicationProtocol == "h2" && upstream.applicationProtocol == "h2") {
                    relayTlsHttp2(downstream, upstream, host, requestId, started)
                    return
                }
                val raw = readTlsHeaders(downstream)
                val request = NativeHttpParser.parseRequest(raw)
                val body = request.body
                val path = request.url
                val outbound = buildString {
                    append(request.method).append(' ').append(path).append(" HTTP/1.1\r\n")
                    request.headers.filterKeys { !it.equals("Proxy-Connection", true) && !it.equals("Connection", true) }.forEach { (k, v) -> append(k).append(": ").append(v).append("\r\n") }
                    append("Connection: close\r\n\r\n").append(body)
                }.encodeToByteArray()
                upstream.write(outbound)
                val responseRaw = readTlsUntilClose(upstream)
                downstream.write(responseRaw.encodeToByteArray())
                val response = NativeHttpParser.parseResponse(responseRaw)
                store.append(exchange(request, requestId, response, null, started))
            } finally { upstream.close(); downstream.close() }
        } catch (t: Throwable) {
            store.append(NetworkExchange(EvidenceMeta(EvidenceId("ev_${randomId()}"), SessionId("native"), kotlin.time.Clock.System.now(), EvidenceSource.NETWORK, "native", "unknown", null), requestId, NetworkRequest("CONNECT", "https://$host", emptyMap(), null), null, NetworkFailure("HTTPS_MITM_ERROR", t.message), NetworkTiming(started, getTimeMillis(), getTimeMillis() - started), NetworkCaptureMetadata(false, 0, false), protocol = "HTTPS"))
        }
    }

    /** Forward encrypted HTTP/2 application bytes unchanged while observing both directions with
     * native nghttp2. The relay never synthesizes protocol frames, so flow control, SETTINGS and
     * HPACK remain owned by the app and upstream peer exactly as they are on the wire. */
    private fun relayTlsHttp2(
        downstream: NativeTlsConnection,
        upstream: NativeTlsConnection,
        host: String,
        connectId: RequestId,
        started: Long,
    ) {
        val requests = mutableMapOf<Int, H2Request>()
        val responses = mutableMapOf<Int, NativeHttp2Decoder.Exchange>()
        val requestDecoder = NativeHttp2Decoder(requestSide = true)
        val responseDecoder = NativeHttp2Decoder(requestSide = false)
        var downstreamOpen = true
        var upstreamOpen = true
        try {
            memScoped {
                val pollers = allocArray<pollfd>(2)
                val deadline = getTimeMillis() + 15 * 60_000
                while ((downstreamOpen || upstreamOpen) && getTimeMillis() < deadline) {
                    pollers[0].fd = downstream.fileDescriptor
                    pollers[0].events = POLLIN.toShort()
                    pollers[0].revents = 0
                    pollers[1].fd = upstream.fileDescriptor
                    pollers[1].events = POLLIN.toShort()
                    pollers[1].revents = 0
                    if (poll(pollers, 2u, 250) < 0) break

                    if (downstreamOpen && pollers[0].revents.toInt() and (POLLIN or POLLHUP or POLLERR) != 0) {
                        val bytes = downstream.read()
                        if (bytes == null) {
                            downstreamOpen = false
                        } else {
                            upstream.write(bytes)
                            decodeHttp2(requestDecoder, bytes, host, connectId, started).forEach { decoded ->
                                requests[decoded.streamId] = H2Request(
                                    requestId = RequestId("req_${randomId()}_${decoded.streamId}"),
                                    exchange = decoded,
                                )
                            }
                        }
                    }
                    if (upstreamOpen && pollers[1].revents.toInt() and (POLLIN or POLLHUP or POLLERR) != 0) {
                        val bytes = upstream.read()
                        if (bytes == null) {
                            upstreamOpen = false
                        } else {
                            downstream.write(bytes)
                            decodeHttp2(responseDecoder, bytes, host, connectId, started).forEach { decoded ->
                                responses[decoded.streamId] = decoded
                            }
                        }
                    }
                    flushHttp2Exchanges(requests, responses, host, connectId, started)
                }
            }
        } finally {
            requestDecoder.close()
            responseDecoder.close()
            // Preserve one-sided failures instead of silently dropping streams when a peer closes.
            requests.values.forEach { pending ->
                if (responses.remove(pending.exchange.streamId) == null) {
                    store.append(http2Exchange(pending, null, host, connectId, started, NetworkFailure("HTTP2_INCOMPLETE", "response stream closed before END_STREAM")))
                }
            }
            responses.values.forEach { response ->
                store.append(http2Exchange(null, response, host, connectId, started, NetworkFailure("HTTP2_INCOMPLETE", "request stream closed before END_STREAM")))
            }
        }
    }

    private data class H2Request(val requestId: RequestId, val exchange: NativeHttp2Decoder.Exchange)

    private fun decodeHttp2(
        decoder: NativeHttp2Decoder,
        bytes: ByteArray,
        host: String,
        connectId: RequestId,
        started: Long,
    ): List<NativeHttp2Decoder.Exchange> = try {
        decoder.feed(bytes)
    } catch (t: Throwable) {
        store.append(http2Exchange(null, null, host, connectId, started, NetworkFailure("HTTP2_DECODER_ERROR", t.message ?: t::class.simpleName)))
        emptyList()
    }

    private fun flushHttp2Exchanges(
        requests: MutableMap<Int, H2Request>,
        responses: MutableMap<Int, NativeHttp2Decoder.Exchange>,
        host: String,
        connectId: RequestId,
        started: Long,
    ) {
        val complete = requests.keys.intersect(responses.keys).toList()
        complete.forEach { streamId ->
            val request = requests.remove(streamId) ?: return@forEach
            val response = responses.remove(streamId) ?: return@forEach
            store.append(http2Exchange(request, response, host, connectId, started, null))
        }
    }

    private fun http2Exchange(
        request: H2Request?,
        response: NativeHttp2Decoder.Exchange?,
        host: String,
        connectId: RequestId,
        started: Long,
        failure: NetworkFailure?,
    ): NetworkExchange {
        val requestHeaders = request?.exchange?.headers.orEmpty()
        val responseHeaders = response?.headers.orEmpty()
        val method = requestHeaders[":method"] ?: "UNKNOWN"
        val scheme = requestHeaders[":scheme"] ?: "https"
        val authority = requestHeaders[":authority"] ?: host
        val path = requestHeaders[":path"] ?: "/"
        val url = "$scheme://$authority$path"
        val requestBody = request?.exchange?.body?.takeIf { it.isNotEmpty() }?.decodeToString()
        val status = responseHeaders[":status"]?.toIntOrNull() ?: 0
        val responseBody = response?.body?.takeIf { it.isNotEmpty() }?.decodeToString()
        val completed = getTimeMillis()
        val id = request?.requestId ?: RequestId("${connectId.value}_${response?.streamId ?: "unknown"}")
        return NetworkExchange(
            meta = EvidenceMeta(EvidenceId("ev_${randomId()}"), SessionId("native"), kotlin.time.Clock.System.now(), EvidenceSource.NETWORK, "native", "unknown", null),
            requestId = id,
            request = NetworkRequest(method, url, requestHeaders, requestBody),
            response = response?.let { NetworkResponse(status, responseHeaders, responseBody) },
            failure = failure,
            timing = NetworkTiming(started, completed, (completed - started).coerceAtLeast(0)),
            capture = NetworkCaptureMetadata(false, request?.exchange?.body?.size?.toLong() ?: 0, false),
            protocol = "HTTP/2",
        )
    }

    private fun exchange(request: NativeHttpParser.Request, id: RequestId, response: NativeHttpParser.Response?, failure: NetworkFailure?, started: Long) = NetworkExchange(
        meta = EvidenceMeta(EvidenceId("ev_${randomId()}"), SessionId("native"), kotlin.time.Clock.System.now(), EvidenceSource.NETWORK, "native", "unknown", null),
        requestId = id,
        request = NetworkRequest(request.method, request.url, request.headers, request.body.takeIf { it.isNotEmpty() }),
        response = response?.let { NetworkResponse(it.status, it.headers.mapValues { v -> v.value.joinToString(", ") }, it.body.takeIf(String::isNotEmpty)) },
        failure = failure,
        timing = NetworkTiming(started, getTimeMillis(), (getTimeMillis() - started).coerceAtLeast(0)),
        capture = NetworkCaptureMetadata(false, request.body.encodeToByteArray().size.toLong(), false), protocol = "HTTP/1.1",
    )

    private fun failureExchange(client: Int, started: Long, message: String) = exchange(NativeHttpParser.Request("UNKNOWN", "http://unknown", "HTTP/1.1", emptyMap(), ""), RequestId("req_${randomId()}"), null, NetworkFailure("PROXY_ERROR", message), started)
    private fun parseTarget(url: String, host: String?): Pair<String, Int> { val value = if (url.startsWith("http://")) url.removePrefix("http://") else host ?: error("proxy request has no Host header"); val authority = value.substringBefore('/'); val parts = authority.split(':', limit = 2); return parts[0] to (parts.getOrNull(1)?.toIntOrNull() ?: 80) }
    private fun connect(host: String, port: Int): Int = memScoped { val hints = alloc<addrinfo>(); hints.ai_family = AF_UNSPEC; hints.ai_socktype = SOCK_STREAM; val result = allocPointerTo<addrinfo>(); require(getaddrinfo(host, port.toString(), hints.ptr, result.ptr) == 0); val info = result.value ?: error("unable to resolve $host"); val fd = socket(info.pointed.ai_family, info.pointed.ai_socktype, info.pointed.ai_protocol); require(fd >= 0); require(platform.posix.connect(fd, info.pointed.ai_addr, info.pointed.ai_addrlen) == 0); freeaddrinfo(info); fd }
    /** Read exactly through the header terminator. A bulk recv can consume the
     * beginning of a TLS ClientHello after CONNECT, so this intentionally reads
     * one byte at a time at the protocol boundary. */
    private fun readHeaders(fd: Int): String { val bytes = mutableListOf<Byte>(); while (bytes.size < 1024 * 1024) { memScoped { val native = allocArray<ByteVar>(1); val count = recv(fd, native, 1uL, 0); if (count <= 0) return bytes.toByteArray().decodeToString(); bytes += native[0] }; if (bytes.size >= 4 && bytes.takeLast(4).toByteArray().decodeToString() == "\r\n\r\n") break }; return bytes.toByteArray().decodeToString() }
    private fun readUntilClose(fd: Int): String { val bytes = mutableListOf<Byte>(); memScoped { val buffer = allocArray<ByteVar>(8192); while (true) { val count = recv(fd, buffer, 8192.convert(), 0); if (count <= 0) break; for (i in 0 until count) bytes += buffer[i] } }; return bytes.toByteArray().decodeToString() }
    private fun relayPlainWebSocket(client: Int, upstream: Int, request: NativeHttpParser.Request, id: RequestId, response: NativeHttpParser.Response, started: Long) {
        val frames = mutableListOf<NetworkFrame>()
        memScoped {
            val pollers = allocArray<pollfd>(2)
            var openClient = true; var openUpstream = true; val deadline = getTimeMillis() + 15 * 60_000
            while ((openClient || openUpstream) && getTimeMillis() < deadline) {
                pollers[0].fd = client; pollers[0].events = POLLIN.toShort(); pollers[0].revents = 0
                pollers[1].fd = upstream; pollers[1].events = POLLIN.toShort(); pollers[1].revents = 0
                if (poll(pollers, 2u, 250) < 0) break
                if (openClient && pollers[0].revents.toInt() and POLLIN != 0) {
                    val frame = readFrame(client) ?: run { openClient = false; continue }
                    sendBytes(upstream, frame.bytes); frames += frame.evidence("client_to_server")
                }
                if (openUpstream && pollers[1].revents.toInt() and POLLIN != 0) {
                    val frame = readFrame(upstream) ?: run { openUpstream = false; continue }
                    sendBytes(client, frame.bytes); frames += frame.evidence("server_to_client")
                }
            }
        }
        store.append(exchange(request, id, response, null, started).copy(protocol = "WebSocket", frames = frames))
    }

    private data class WsFrame(val bytes: ByteArray, val direction: String = "") {
        fun evidence(direction: String): NetworkFrame {
            val opcode = (bytes.firstOrNull()?.toInt()?.and(0x0f) ?: 0).toString()
            return NetworkFrame(direction, opcode, bytes.drop(2).toByteArray().decodeToString())
        }
    }
    private fun readFrame(fd: Int): WsFrame? {
        val header = readExactly(fd, 2) ?: return null
        val masked = header[1].toInt() and 0x80 != 0; var length = header[1].toInt() and 0x7f
        val extended = if (length == 126) readExactly(fd, 2) ?: return null else ByteArray(0)
        if (length == 126) length = (extended[0].toInt() and 0xff) * 256 + (extended[1].toInt() and 0xff)
        if (length == 127) return null // avoid unbounded native allocations
        val mask = if (masked) readExactly(fd, 4) ?: return null else ByteArray(0)
        val payload = readExactly(fd, length) ?: return null
        if (masked) for (i in payload.indices) payload[i] = (payload[i].toInt() xor mask[i % 4].toInt()).toByte()
        val frame = header + extended + mask + payload
        return WsFrame(frame)
    }
    private fun readExactly(fd: Int, length: Int): ByteArray? { if (length == 0) return ByteArray(0); val out = ByteArray(length); var offset = 0; while (offset < length) { val count = out.usePinned { recv(fd, it.addressOf(offset), (length - offset).toULong(), 0) }; if (count <= 0) return null; offset += count.toInt() }; return out }
    private fun readTlsHeaders(connection: NativeTlsConnection): String { val bytes = mutableListOf<Byte>(); while (bytes.size < 1024 * 1024) { val chunk = connection.read() ?: break; bytes += chunk.toList(); if (bytes.toByteArray().decodeToString().contains("\r\n\r\n")) break }; return bytes.toByteArray().decodeToString() }
    private fun readTlsUntilClose(connection: NativeTlsConnection): String { val bytes = mutableListOf<Byte>(); while (true) { val chunk = connection.read() ?: break; bytes += chunk.toList() }; return bytes.toByteArray().decodeToString() }
    private fun sendBytes(fd: Int, bytes: ByteArray) { bytes.usePinned { send(fd, it.addressOf(0), bytes.size.toULong(), 0) } }
    private fun networkShort(port: Int): UShort = (((port ushr 8) and 0xff) or ((port and 0xff) shl 8)).toUShort()
    private fun randomId() = getTimeMillis().toString(36)
    private fun getTimeMillis() = kotlin.time.Clock.System.now().toEpochMilliseconds()
}
