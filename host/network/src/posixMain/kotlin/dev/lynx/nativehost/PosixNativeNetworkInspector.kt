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
    private val androidProxy = NativeAndroidProxyController(processes)
    private val macProxy = NativeMacSystemProxyController(processes)
    private val clientDispatcher = PosixNativeConnectionDispatcher()

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
        limitations = listOf("WebSocket over HTTP/2 (RFC 8441) is not enabled; TLS WebSocket capture requires HTTP/1.1 Upgrade"),
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
        val previousProxy = applyDeviceProxy(port, settings.listenHost)
        store.clearWorkerReady()
        store.setRunning(endpoint, caps, previousProxy)
        // Launch the same native executable as a detached worker. Installers put `lynx` on PATH;
        // tests and embedders can provide LYNX_EXECUTABLE explicitly.
        val executable = getenv("LYNX_EXECUTABLE")?.toKString()?.takeIf(String::isNotBlank)
            ?: nativeExecutablePath()
            ?: "lynx"
        PosixProcessRunner().run(listOf("sh", "-c", "(nohup '$executable' network worker $port >/dev/null 2>&1 </dev/null &)"))
        try {
            repeat(40) {
                if (store.workerReady()) {
                    val running = caps.copy(proxyStatus = "running")
                    store.setRunning(endpoint, running, previousProxy)
                    return NetworkCommandResult.Started(endpoint, running)
                }
                usleep(50_000u)
            }
            throw IllegalStateException("native network worker did not become ready; install lynx on PATH or set LYNX_EXECUTABLE")
        } catch (error: Throwable) {
            runCatching { restoreProxy(previousProxy) }
            store.clearRunning()
            throw error
        }
    }

    private fun applyDeviceProxy(port: Int, listenHost: String): Map<String, String?>? {
        val session = sessions.load() ?: return null
        if (session.deviceSerial.startsWith("ios-simulator:")) {
            require(listenHost == "0.0.0.0" || listenHost == "127.0.0.1") {
                "iOS Simulator capture requires the native proxy to listen on the host"
            }
            return macProxy.apply(port)
        }
        val visibleHost = when {
            session.deviceSerial.startsWith("emulator-") -> "10.0.2.2"
            listenHost != "0.0.0.0" -> listenHost
            else -> error("an explicit reachable --host is required for physical Android devices")
        }
        return androidProxy.apply(session.deviceSerial, visibleHost, port)
    }

    private fun restoreDeviceProxy() {
        val previous = store.previousProxy()
        if (previous?.get("controller") == "macos") {
            macProxy.restore(previous)
            return
        }
        val session = sessions.load() ?: return
        androidProxy.restore(session.deviceSerial, previous)
    }

    private fun restoreProxy(previous: Map<String, String?>?) {
        if (previous?.get("controller") == "macos") {
            macProxy.restore(previous)
        } else {
            sessions.load()?.let { androidProxy.restore(it.deviceSerial, previous) }
        }
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
                if (client >= 0) clientDispatcher.dispatch { handle(client) } else usleep(50_000u)
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
                val response = responseHeadValue.copy(
                    body = NativeHttpBodyDecoder.decode(responseHeadValue.headers, responseBody),
                )
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
            val tlsProvider = nativeTlsProvider()
            // Negotiate with the origin first. A downstream client may prefer h2 even when the
            // origin is HTTP/1.1-only; choose the downstream ALPN based on the origin so we never
            // advertise h2 to the app and then fail the upstream handshake.
            val upstreamFd = connect(host, port)
            var upstream = try {
                tlsProvider.client(upstreamFd, host, enableHttp2 = true)
            } catch (error: Throwable) {
                close(upstreamFd)
                throw error
            }
            val upstreamUsesHttp2 = upstream.applicationProtocol == "h2"
            val downstream = try {
                tlsProvider.server(client, leaf.certificate, leaf.privateKey, enableHttp2 = upstreamUsesHttp2)
            } catch (error: Throwable) {
                upstream.close()
                throw error
            }
            var upstreamClosed = false
            try {
                val downstreamUsesHttp2 = downstream.applicationProtocol == "h2"
                var negotiatedUpstreamUsesHttp2 = upstreamUsesHttp2
                if (negotiatedUpstreamUsesHttp2 && !downstreamUsesHttp2) {
                    // WebSocket clients commonly negotiate HTTP/1.1 even when the origin also
                    // supports h2. Reconnect upstream with HTTP/1.1 so the HTTP/1.1 Upgrade is
                    // preserved end-to-end instead of relaying an incompatible h2 TLS session.
                    upstream.close()
                    upstreamClosed = true
                    val http1Fd = connect(host, port)
                    upstream = try {
                        tlsProvider.client(http1Fd, host, enableHttp2 = false)
                    } catch (error: Throwable) {
                        close(http1Fd)
                        throw error
                    }
                    upstreamClosed = false
                    negotiatedUpstreamUsesHttp2 = upstream.applicationProtocol == "h2"
                }
                require(downstreamUsesHttp2 == negotiatedUpstreamUsesHttp2) {
                    "TLS application protocol mismatch: app negotiated ${downstream.applicationProtocol ?: "HTTP/1.1"}, " +
                        "origin negotiated ${upstream.applicationProtocol ?: "HTTP/1.1"}"
                }
                if (downstreamUsesHttp2) {
                    relayTlsHttp2(downstream, upstream, host, requestId, started)
                    return
                }
                val downstreamReader = NativeTlsBufferedReader(downstream)
                val upstreamReader = NativeTlsBufferedReader(upstream)
                val raw = downstreamReader.readHeaders()
                val request = NativeHttpParser.parseRequest(raw)
                val body = request.body
                val path = request.url.ifBlank { "/" }
                val websocketUpgrade = request.headers.entries.any { (name, value) ->
                    name.equals("Upgrade", true) && value.equals("websocket", true)
                } && request.headers.entries.any { (name, value) ->
                    name.equals("Connection", true) && value.split(',').any { it.trim().equals("upgrade", true) }
                }
                val scheme = if (websocketUpgrade) "wss" else "https"
                val requestUrl = if (path.startsWith("https://") || path.startsWith("wss://")) path
                    else "$scheme://$host${if (path.startsWith('/')) path else "/$path"}"
                val observedRequest = request.copy(url = requestUrl)
                val outbound = buildString {
                    append(request.method).append(' ').append(path).append(" HTTP/1.1\r\n")
                    request.headers.filterKeys { !it.equals("Proxy-Connection", true) && !it.equals("Proxy-Authorization", true) }
                        .filterKeys { websocketUpgrade || !it.equals("Connection", true) }
                        .forEach { (k, v) -> append(k).append(": ").append(v).append("\r\n") }
                    if (!websocketUpgrade) append("Connection: close\r\n")
                    append("\r\n").append(body)
                }.encodeToByteArray()
                upstream.write(outbound)
                val responseHead = upstreamReader.readHeaders()
                val response = NativeHttpParser.parseResponse(responseHead)
                if (websocketUpgrade && response.status == 101 && response.headers.keys.any { it.equals("Upgrade", true) }) {
                    downstream.write(responseHead.encodeToByteArray())
                    relayTlsWebSocket(
                        downstream, upstream, downstreamReader, upstreamReader,
                        observedRequest, requestId, response.copy(body = ""), started,
                    )
                } else {
                    val responseBody = upstreamReader.readUntilClose()
                    downstream.write((responseHead + responseBody).encodeToByteArray())
                    val capturedBody = NativeHttpBodyDecoder.decode(response.headers, responseBody)
                    store.append(exchange(observedRequest, requestId, response.copy(body = capturedBody), null, started))
                }
            } finally {
                if (!upstreamClosed) upstream.close()
                downstream.close()
            }
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
    private fun parseTarget(url: String, host: String?): Pair<String, Int> {
        val value = when {
            url.startsWith("http://") -> url.removePrefix("http://")
            url.startsWith("ws://") -> url.removePrefix("ws://")
            else -> host ?: error("proxy request has no Host header")
        }
        val authority = value.substringBefore('/')
        val parts = authority.split(':', limit = 2)
        // Android emulator traffic reaches the host through 10.0.2.2. Once the request is
        // handled by Lynx on the host, the same special address is not routable back to the host;
        // map it to loopback so local development servers remain reachable from the proxy.
        val targetHost = parts[0].takeUnless { it == "10.0.2.2" } ?: "127.0.0.1"
        return targetHost to (parts.getOrNull(1)?.toIntOrNull() ?: 80)
    }
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
                    sendBytes(upstream, frame.wireBytes); frames += frame.evidence("CLIENT_TO_SERVER")
                }
                if (openUpstream && pollers[1].revents.toInt() and POLLIN != 0) {
                    val frame = readFrame(upstream) ?: run { openUpstream = false; continue }
                    sendBytes(client, frame.wireBytes); frames += frame.evidence("SERVER_TO_CLIENT")
                }
            }
        }
        store.append(exchange(request, id, response, null, started).copy(protocol = "WebSocket", frames = frames))
    }

    private fun relayTlsWebSocket(
        downstream: NativeTlsConnection,
        upstream: NativeTlsConnection,
        downstreamReader: NativeTlsBufferedReader,
        upstreamReader: NativeTlsBufferedReader,
        request: NativeHttpParser.Request,
        id: RequestId,
        response: NativeHttpParser.Response,
        started: Long,
    ) {
        val frames = mutableListOf<NetworkFrame>()
        memScoped {
            val pollers = allocArray<pollfd>(2)
            var downstreamOpen = true
            var upstreamOpen = true
            val deadline = getTimeMillis() + 15 * 60_000
            while ((downstreamOpen || upstreamOpen) && getTimeMillis() < deadline) {
                pollers[0].fd = downstream.fileDescriptor
                pollers[0].events = POLLIN.toShort()
                pollers[0].revents = 0
                pollers[1].fd = upstream.fileDescriptor
                pollers[1].events = POLLIN.toShort()
                pollers[1].revents = 0
                val downstreamReady = downstreamReader.hasBufferedBytes()
                val upstreamReady = upstreamReader.hasBufferedBytes()
                if (!downstreamReady && !upstreamReady && poll(pollers, 2u, 250) < 0) break
                if (downstreamOpen && (downstreamReady || pollers[0].revents.toInt() and (POLLIN or POLLHUP or POLLERR) != 0)) {
                    val frame = NativeWebSocketFrameCodec.read(downstreamReader::readExactly)
                    if (frame == null) downstreamOpen = false else {
                        upstream.write(frame.wireBytes)
                        frames += frame.evidence("CLIENT_TO_SERVER")
                    }
                }
                if (upstreamOpen && (upstreamReady || pollers[1].revents.toInt() and (POLLIN or POLLHUP or POLLERR) != 0)) {
                    val frame = NativeWebSocketFrameCodec.read(upstreamReader::readExactly)
                    if (frame == null) upstreamOpen = false else {
                        downstream.write(frame.wireBytes)
                        frames += frame.evidence("SERVER_TO_CLIENT")
                    }
                }
            }
        }
        store.append(exchange(request, id, response, null, started).copy(protocol = "WebSocket", frames = frames))
    }

    private fun readFrame(fd: Int): NativeWebSocketFrame? = NativeWebSocketFrameCodec.read { readExactly(fd, it) }
    private fun readExactly(fd: Int, length: Int): ByteArray? { if (length == 0) return ByteArray(0); val out = ByteArray(length); var offset = 0; while (offset < length) { val count = out.usePinned { recv(fd, it.addressOf(offset), (length - offset).toULong(), 0) }; if (count <= 0) return null; offset += count.toInt() }; return out }
    private fun sendBytes(fd: Int, bytes: ByteArray) { bytes.usePinned { send(fd, it.addressOf(0), bytes.size.toULong(), 0) } }
    private fun networkShort(port: Int): UShort = (((port ushr 8) and 0xff) or ((port and 0xff) shl 8)).toUShort()
    private fun randomId() = getTimeMillis().toString(36)
    private fun getTimeMillis() = kotlin.time.Clock.System.now().toEpochMilliseconds()
}

/** Preserves plaintext bytes which a TLS read may return past the HTTP header boundary. */
internal class NativeTlsBufferedReader(private val connection: NativeTlsConnection) {
    private var buffered = ByteArray(0)

    fun hasBufferedBytes(): Boolean = buffered.isNotEmpty()

    fun readHeaders(): String {
        val marker = byteArrayOf('\r'.code.toByte(), '\n'.code.toByte(), '\r'.code.toByte(), '\n'.code.toByte())
        while (buffered.size < MAX_HEADER_BYTES) {
            val end = buffered.indexOfSequence(marker)
            if (end >= 0) {
                val header = buffered.copyOfRange(0, end + marker.size)
                buffered = buffered.copyOfRange(end + marker.size, buffered.size)
                return header.decodeToString()
            }
            val next = connection.read() ?: break
            buffered += next
        }
        error(if (buffered.size >= MAX_HEADER_BYTES) "TLS HTTP headers exceed $MAX_HEADER_BYTES bytes" else "TLS peer closed before HTTP headers completed")
    }

    fun readExactly(length: Int): ByteArray? {
        require(length >= 0)
        while (buffered.size < length) {
            val next = connection.read(length - buffered.size) ?: return null
            buffered += next
        }
        val result = buffered.copyOfRange(0, length)
        buffered = buffered.copyOfRange(length, buffered.size)
        return result
    }

    fun readUntilClose(): String {
        val bytes = buffered.toMutableList()
        buffered = ByteArray(0)
        while (true) {
            val next = connection.read() ?: break
            bytes.addAll(next.toList())
        }
        return bytes.toByteArray().decodeToString()
    }

    private fun ByteArray.indexOfSequence(value: ByteArray): Int {
        if (value.isEmpty() || size < value.size) return -1
        for (start in 0..(size - value.size)) {
            if (value.indices.all { this[start + it] == value[it] }) return start
        }
        return -1
    }

    private companion object { const val MAX_HEADER_BYTES = 1024 * 1024 }
}

internal data class NativeWebSocketFrame(
    val wireBytes: ByteArray,
    val opcode: String,
    val payload: ByteArray,
) {
    fun evidence(direction: String): NetworkFrame = NetworkFrame(direction, opcode, when (opcode) {
        "TEXT", "CONTINUATION" -> payload.takeIf { it.isNotEmpty() }?.decodeToString()
        else -> payload.takeIf { it.isNotEmpty() }?.let(::encodeBase64)?.let { "base64:$it" }
    })
}

/** Reads and inspects RFC 6455 frames without changing the original masked wire representation. */
internal object NativeWebSocketFrameCodec {
    fun read(readExactly: (Int) -> ByteArray?): NativeWebSocketFrame? {
        val header = readExactly(2) ?: return null
        val first = header[0].toInt() and 0xff
        val second = header[1].toInt() and 0xff
        val masked = second and 0x80 != 0
        val marker = second and 0x7f
        var length = marker.toLong()
        val extended = when (marker) {
            126 -> readExactly(2) ?: return null
            127 -> readExactly(8) ?: return null
            else -> ByteArray(0)
        }
        if (marker == 126) length = (((extended[0].toInt() and 0xff) shl 8) or (extended[1].toInt() and 0xff)).toLong()
        if (marker == 127) {
            require(extended[0].toInt() and 0x80 == 0) { "Malformed WebSocket frame length" }
            length = extended.fold(0L) { value, byte -> (value shl 8) or (byte.toLong() and 0xff) }
        }
        require(length <= Int.MAX_VALUE) { "WebSocket frame payload exceeds native array capacity" }
        val mask = if (masked) readExactly(4) ?: return null else ByteArray(0)
        val encodedPayload = readExactly(length.toInt()) ?: return null
        val decodedPayload = encodedPayload.copyOf()
        if (masked) for (index in decodedPayload.indices) {
            decodedPayload[index] = (decodedPayload[index].toInt() xor (mask[index % 4].toInt() and 0xff)).toByte()
        }
        val opcode = when (first and 0x0f) {
            0x0 -> "CONTINUATION"
            0x1 -> "TEXT"
            0x2 -> "BINARY"
            0x8 -> "CLOSE"
            0x9 -> "PING"
            0xa -> "PONG"
            else -> "OPCODE_${first and 0x0f}"
        }
        val wire = header + extended + mask + encodedPayload
        return NativeWebSocketFrame(wire, opcode, decodedPayload)
    }
}

private fun encodeBase64(bytes: ByteArray): String {
    val alphabet = "ABCDEFGHIJKLMNOPQRSTUVWXYZabcdefghijklmnopqrstuvwxyz0123456789+/"
    return buildString((bytes.size + 2) / 3 * 4) {
        var index = 0
        while (index < bytes.size) {
            val first = bytes[index++].toInt() and 0xff
            val hasSecond = index < bytes.size
            val second = if (hasSecond) bytes[index++].toInt() and 0xff else 0
            val hasThird = index < bytes.size
            val third = if (hasThird) bytes[index++].toInt() and 0xff else 0
            append(alphabet[first ushr 2])
            append(alphabet[((first and 0x03) shl 4) or (second ushr 4)])
            append(if (hasSecond) alphabet[((second and 0x0f) shl 2) or (third ushr 6)] else '=')
            append(if (hasThird) alphabet[third and 0x3f] else '=')
        }
    }
}
