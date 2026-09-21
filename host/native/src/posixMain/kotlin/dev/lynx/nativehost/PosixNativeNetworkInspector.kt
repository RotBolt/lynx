package dev.lynx.nativehost

import dev.lynx.model.*
import kotlinx.cinterop.*
import platform.posix.*

/** Native, process-independent HTTP/1 proxy. TLS/HTTP2 are deliberately capability-gated until their
 * platform adapters are linked; they must never be misreported as captured. */
@OptIn(ExperimentalForeignApi::class)
class PosixNativeNetworkInspector(
    private val store: PosixNativeNetworkStateStore = PosixNativeNetworkStateStore(),
) : NativeNetworkInspector {
    override fun execute(command: NetworkCommand): NetworkCommandResult = when (command) {
        is NetworkCommand.Start -> start(command.settings)
        NetworkCommand.Stop -> { store.clearRunning(); NetworkCommandResult.Stopped }
        is NetworkCommand.List -> NetworkCommandResult.Exchanges(store.list(command.filter))
        is NetworkCommand.Get -> store.get(command.requestId)?.let(NetworkCommandResult::Exchange)
            ?: error("network exchange not found: ${command.requestId.value}")
        NetworkCommand.Doctor -> NetworkCommandResult.Diagnostics(capabilities())
    }

    private fun capabilities() = NetworkCapabilities(
        httpsMitm = false,
        supportedProtocols = listOf("HTTP/1.1"),
        limitations = listOf("HTTPS CONNECT interception is not enabled", "HTTP/2 and WebSocket capture are not enabled"),
        proxyEndpoint = store.endpoint(),
        proxyStatus = if (store.isRunning()) "running" else "stopped",
    )

    private fun start(settings: NetworkCaptureSettings): NetworkCommandResult.Started {
        val port = if (settings.listenPort == 0) 62006 else settings.listenPort
        val endpoint = "${settings.listenHost}:$port"
        val caps = capabilities().copy(proxyEndpoint = endpoint, proxyStatus = "starting")
        store.setRunning(endpoint, caps)
        // Launch the same native executable as a detached worker. Installers put `lynx` on PATH;
        // tests and embedders can provide LYNX_EXECUTABLE explicitly.
        val executable = getenv("LYNX_EXECUTABLE")?.toKString()?.takeIf(String::isNotBlank) ?: "lynx"
        PosixProcessRunner().run(listOf("sh", "-c", "(nohup '$executable' network worker $port >/dev/null 2>&1 </dev/null &)"))
        val running = caps.copy(proxyStatus = "running")
        store.setRunning(endpoint, running)
        return NetworkCommandResult.Started(endpoint, running)
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
        while (store.isRunning()) {
            val client = accept(server, null, null)
            if (client >= 0) handle(client) else usleep(50_000u)
        }
        close(server)
    }

    private fun handle(client: Int) {
        val started = getTimeMillis()
        try {
            val raw = readHeaders(client)
            val request = NativeHttpParser.parseRequest(raw)
            val requestId = RequestId("req_${randomId()}")
            if (request.method.equals("CONNECT", true)) {
                store.append(exchange(request, requestId, null, NetworkFailure("HTTPS_MITM_ERROR", "HTTPS interception is not enabled"), started))
                return
            }
            val target = parseTarget(request.url, request.headers["Host"] ?: request.headers["host"])
            val upstream = connect(target.first, target.second)
            try {
                sendBytes(upstream, raw.encodeToByteArray())
                val responseRaw = readUntilClose(upstream)
                val response = NativeHttpParser.parseResponse(responseRaw)
                sendBytes(client, responseRaw.encodeToByteArray())
                store.append(exchange(request, requestId, response, null, started))
            } finally { close(upstream) }
        } catch (t: Throwable) {
            // Keep failures visible to an agent; malformed/failed requests are evidence too.
            runCatching { store.append(failureExchange(client, started, t.message ?: t::class.simpleName.orEmpty())) }
        } finally { close(client) }
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
    private fun readHeaders(fd: Int): String { val bytes = mutableListOf<Byte>(); val buffer = ByteArray(8192); while (bytes.size < 1024 * 1024) { memScoped { val native = allocArray<ByteVar>(buffer.size); val count = recv(fd, native, buffer.size.convert(), 0); if (count <= 0) return bytes.toByteArray().decodeToString(); for (i in 0 until count) bytes += native[i]; }; if (bytes.toByteArray().decodeToString().contains("\r\n\r\n")) break }; return bytes.toByteArray().decodeToString() }
    private fun readUntilClose(fd: Int): String { val bytes = mutableListOf<Byte>(); memScoped { val buffer = allocArray<ByteVar>(8192); while (true) { val count = recv(fd, buffer, 8192.convert(), 0); if (count <= 0) break; for (i in 0 until count) bytes += buffer[i] } }; return bytes.toByteArray().decodeToString() }
    private fun sendBytes(fd: Int, bytes: ByteArray) { bytes.usePinned { send(fd, it.addressOf(0), bytes.size.toULong(), 0) } }
    private fun networkShort(port: Int): UShort = (((port ushr 8) and 0xff) or ((port and 0xff) shl 8)).toUShort()
    private fun randomId() = getTimeMillis().toString(36)
    private fun getTimeMillis() = kotlin.time.Clock.System.now().toEpochMilliseconds()
}
