package dev.lynx.nativehost

import dev.lynx.model.*
import kotlinx.cinterop.*
import kotlinx.serialization.SerialName
import kotlinx.serialization.Serializable
import kotlinx.serialization.json.Json
import platform.posix.*

@Serializable
private data class AndroidRelayPreface(
    @SerialName("protocol_version") val protocolVersion: Int,
    @SerialName("capture_id") val captureId: String,
    @SerialName("capture_token") val captureToken: String,
    @SerialName("device_serial") val deviceSerial: String,
    @SerialName("package_name") val packageName: String,
    val decision: String,
    val method: String,
)

/** Native, process-independent proxy. Protocol parsing stays in the shared POSIX layer while TLS
 * and HPACK are provided by the platform's native adapters. */
@OptIn(ExperimentalForeignApi::class)
class PosixNativeNetworkInspector(
    private val store: PosixNativeNetworkStateStore = PosixNativeNetworkStateStore(),
    private val sessions: NativeSessionStore = PosixNativeSessionStore(),
    private val processes: NativeProcessRunner = PosixProcessRunner(),
    private val certificates: PosixNativeCertificateAuthority = PosixNativeCertificateAuthority(processes),
    private val captureRepository: CaptureRepository = PosixCaptureRepository(),
) : NativeNetworkInspector {
    private val androidProxy = NativeAndroidProxyController(processes)
    private val androidRelay = NativeAndroidRelayController(processes)
    private val macProxy = NativeMacSystemProxyController(processes, service = null)
    private val macRecovery = NativeMacProxyRecovery(macProxy, store)
    private val ownerResolver = nativeConnectionOwnerResolver()
    private val clientDispatcher = PosixNativeConnectionDispatcher()
    private val captures = NativeCaptureCoordinator(captureRepository, idProvider = { "capture_${randomId()}" })

    override fun execute(command: NetworkCommand): NetworkCommandResult = when (command) {
        is NetworkCommand.Start -> start(command.settings)
        NetworkCommand.Stop -> { store.captureId()?.let(captures::stop); restoreDeviceProxy(); store.clearRunning(); store.clearWorkerReady(); NetworkCommandResult.Stopped }
        is NetworkCommand.List -> NetworkCommandResult.Exchanges(store.list(command.filter))
        NetworkCommand.Snapshot -> snapshot()
        NetworkCommand.Sessions -> sessionCatalog()
        is NetworkCommand.SessionList -> sessionExchanges(command.sessionId, command.filter)
        is NetworkCommand.Get -> verifiedExchange(command.requestId)?.let(NetworkCommandResult::Exchange)
            ?: error("network exchange not found: ${command.requestId.value}")
        NetworkCommand.Doctor -> {
            reconcileRunningState()
            NetworkCommandResult.Diagnostics(capabilities())
        }
    }

    private fun snapshot(): NetworkCommandResult.Snapshot {
        val id = store.captureId()?.takeIf { store.isRunning() } ?: error("NO_ACTIVE_CAPTURE")
        val capture = captureRepository.session(id) ?: error("CAPTURE_SESSION_NOT_FOUND")
        val attached = sessions.load()
        if (attached != null && capture.target != captureTarget(attached)) {
            error("CAPTURE_TARGET_MISMATCH active=${capture.target.deviceId}/${capture.target.applicationId} attached=${attached.deviceSerial}/${attached.packageName}")
        }
        val read = captureRepository.read(id)
        return NetworkCommandResult.Snapshot(id, read.throughSequence, scopedExchanges(read), read.inFlightCount)
    }

    private fun sessionCatalog(): NetworkCommandResult.Sessions = NetworkCommandResult.Sessions(
        captureRepository.sessions().map { session ->
            val read = captureRepository.read(session.id)
            val exchanges = scopedExchanges(read)
            NetworkCommandResult.NetworkSessionSummary(
                sessionId = session.id,
                attachmentId = session.attachmentId,
                target = session.target,
                state = session.state,
                startedAtEpochMillis = session.startedAtEpochMillis,
                exchangeCount = exchanges.size,
                failureCount = exchanges.count { it.failure != null },
                inFlightCount = read.inFlightCount,
            )
        },
    )

    private fun sessionExchanges(id: String, filter: NetworkFilter): NetworkCommandResult {
        val read = captureRepository.session(id)?.let { captureRepository.read(id) }
            ?: error("CAPTURE_SESSION_NOT_FOUND")
        val method = filter.method
        val status = filter.status
        val urlSubstring = filter.urlSubstring
        val sinceEpochMillis = filter.sinceEpochMillis
        val exchanges = scopedExchanges(read).asSequence()
            .filter { exchange ->
                (method == null || exchange.request.method.equals(method, ignoreCase = true)) &&
                    (status == null || exchange.response?.status == status) &&
                    (urlSubstring == null || exchange.request.url.contains(urlSubstring, ignoreCase = true)) &&
                    (sinceEpochMillis == null || exchange.timing.startedAtEpochMillis >= sinceEpochMillis)
            }
            .let { values -> filter.limit?.let(values::take) ?: values }
            .toList()
        return NetworkCommandResult.ScopedExchanges(id, read.throughSequence, exchanges, read.inFlightCount)
    }

    private fun verifiedExchange(requestId: RequestId): NetworkExchange? {
        captureRepository.sessions().asSequence().forEach { session ->
            val read = captureRepository.read(session.id)
            scopedExchanges(read).lastOrNull { it.requestId == requestId }?.let { return it }
        }
        return null
    }

    private fun scopedExchanges(read: CaptureRead): List<NetworkExchange> {
        val latest = LinkedHashMap<RequestId, Pair<Long, NetworkExchange>>()
        read.exchanges.forEach { verified ->
            val exchange = verified.exchange.copy(
                captureSessionId = read.session.id,
                sequence = latestSequence(read, verified),
                attribution = NetworkAttribution(
                    status = "verified",
                    method = verified.context.origin.method,
                    deviceId = verified.context.origin.target.deviceId,
                    applicationId = verified.context.origin.target.applicationId,
                    processId = verified.context.origin.process.pid,
                    processStartIdentity = verified.context.origin.process.startIdentity,
                    uid = verified.context.origin.uid,
                ),
            )
            val sequence = exchange.sequence ?: 0L
            latest[exchange.requestId] = sequence to exchange
        }
        return latest.values.sortedBy { it.first }.map { it.second }
    }

    /** CaptureRead currently returns verified rows in sequence order; use a stable local index
     * until the repository exposes the per-row sequence alongside each value. */
    private fun latestSequence(read: CaptureRead, verified: VerifiedExchange): Long =
        read.exchanges.indexOf(verified).toLong() + 1L

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
        if (store.isRunning() && store.endpoint() == endpoint) {
            val activeCapture = store.captureId()?.let(captureRepository::session)
            val attached = sessions.load()
            if (activeCapture != null && attached != null && activeCapture.target != captureTarget(attached)) {
                error("CAPTURE_ACTIVE")
            }
            if (currentCaptureHealthy()) {
                val running = capabilities().copy(proxyEndpoint = endpoint, proxyStatus = "running")
                return NetworkCommandResult.Started(
                    endpoint = endpoint,
                    capabilities = running,
                    sessionId = store.captureId(),
                    attachmentId = sessions.load()?.id,
                    target = sessions.load()?.let(::captureTarget),
                    alreadyRunning = true,
                )
            }
            reconcileRunningState()
        }
        if (!store.isRunning()) {
            captureRepository.sessions()
                .filter { it.state in setOf(CaptureState.STARTING, CaptureState.RUNNING, CaptureState.STOPPING) }
                .forEach { captures.interrupt(it.id) }
            macRecovery.restoreOwnedLease()
        }
        val caps = capabilities().copy(proxyEndpoint = endpoint, proxyStatus = "starting")
        val session = sessions.load()
        if (session?.deviceSerial?.startsWith("ios-simulator:") == true && ownerResolver == null) {
            error("APP_ATTRIBUTION_UNAVAILABLE")
        }
        val captureId = session?.let { attached ->
            captures.start(
                attached.id,
                CaptureTarget(
                    platform = if (attached.deviceSerial.startsWith("ios-simulator:")) "ios" else "android",
                    deviceId = attached.deviceSerial.removePrefix("ios-simulator:"),
                    applicationId = attached.packageName,
                ),
            ).id
        }
        val captureToken = "capture_${randomId()}"
        val macLease = if (session?.deviceSerial?.startsWith("ios-simulator:") == true) {
            require(settings.listenHost == "0.0.0.0" || settings.listenHost == "127.0.0.1") {
                "iOS Simulator capture requires the native proxy to listen on the host"
            }
            macRecovery.prepareLease(endpoint, port, captureToken)
        } else null
        val previousProxy = macLease?.previousProxyMap()
        store.clearWorkerReady()
        store.clearSupervisorReady()
        store.setRunning(endpoint, caps, previousProxy, workerPid = null, workerStartIdentity = null, supervisorPid = null, captureToken = captureToken, captureId = captureId)
        try {
            // Launch the same native executable as a detached worker. Installers put `lynx` on PATH;
            // tests and embedders can provide LYNX_EXECUTABLE explicitly.
            val executable = getenv("LYNX_EXECUTABLE")?.toKString()?.takeIf(String::isNotBlank)
            ?: nativeExecutablePath()
            ?: "lynx"
            val launch = processes.run(listOf("sh", "-c", "LYNX_CAPTURE_TOKEN='$captureToken' LYNX_CAPTURE_ID='${captureId.orEmpty()}' LYNX_CAPTURE_ENDPOINT='$endpoint' nohup '$executable' network worker $port >/dev/null 2>&1 </dev/null & echo \$!"))
            val workerPid = launch.stdout.trim().toIntOrNull()
            ?: error("native network worker did not report a process ID")
            val workerStartIdentity = processStartIdentity(workerPid)
            ?: error("native network worker identity could not be verified")
            val captureLease = captureId?.let { NativeCaptureLease(it, captureToken, endpoint, NativeWorkerIdentity(workerPid, workerStartIdentity)) }
            store.setRunning(endpoint, caps, previousProxy, workerPid, workerStartIdentity, supervisorPid = null, captureToken = captureToken, captureId = captureId)
            val preparedMacLease = macLease?.copy(workerPid = workerPid, workerStartIdentity = workerStartIdentity)
            if (preparedMacLease != null) store.save(preparedMacLease)
            repeat(40) {
                if ((captureLease?.let(store::workerReady) ?: store.workerReady(port)) && workerIdentityIsCurrent() && probeListener(port)) {
                    val supervisorPid = if (preparedMacLease != null) launchSupervisor(port, captureToken) else null
                    if (supervisorPid != null && !waitForSupervisor(captureToken, supervisorPid)) {
                        error("native proxy supervisor did not become ready")
                    }
                    val activePreviousProxy = preparedMacLease?.let {
                        macRecovery.activatePreparedLease(it.copy(supervisorPid = supervisorPid)).previousProxyMap()
                    } ?: applyDeviceProxy(port, settings.listenHost, captureToken)
                    val running = caps.copy(proxyStatus = "running")
                    store.setRunning(endpoint, running, activePreviousProxy, workerPid, workerStartIdentity, supervisorPid, captureToken, captureId)
                    return NetworkCommandResult.Started(
                        endpoint = endpoint,
                        capabilities = running,
                        sessionId = captureId,
                        attachmentId = session?.id,
                        target = session?.let(::captureTarget),
                    )
                }
                usleep(50_000u)
            }
            throw IllegalStateException("native network worker did not become ready; install lynx on PATH or set LYNX_EXECUTABLE")
        } catch (error: Throwable) {
            captureId?.let(captures::interrupt)
            runCatching { restoreDeviceProxy() }
            store.clearRunning()
            store.clearWorkerReady()
            store.clearSupervisorReady()
            throw error
        }
    }

    private fun captureTarget(session: NativeSession): CaptureTarget = CaptureTarget(
        platform = if (session.deviceSerial.startsWith("ios-simulator:")) "ios" else "android",
        deviceId = session.deviceSerial.removePrefix("ios-simulator:"),
        applicationId = session.packageName,
    )

    private fun applyDeviceProxy(port: Int, listenHost: String, captureToken: String): Map<String, String?>? {
        val session = sessions.load() ?: return null
        if (session.deviceSerial.startsWith("ios-simulator:")) {
            require(listenHost == "0.0.0.0" || listenHost == "127.0.0.1") {
                "iOS Simulator capture requires the native proxy to listen on the host"
            }
            return macProxy.apply(port)
        }
        require(androidRelay.available(session.deviceSerial)) { "ANDROID_RELAY_UNAVAILABLE" }
        val relay = androidRelay.start(session.deviceSerial, session.packageName, session.processId, port, captureId = store.captureId() ?: error("ANDROID_RELAY_CAPTURE_ID_UNAVAILABLE"), captureToken)
        val previous = androidProxy.apply(session.deviceSerial, "127.0.0.1", relay.relayPort).toMutableMap()
        previous["controller"] = "android-relay"
        previous["relayPath"] = relay.remotePath
        previous["relayPid"] = relay.relayPid
        previous["relayPort"] = relay.relayPort.toString()
        previous["relayUpstreamPort"] = relay.upstreamPort.toString()
        return previous
    }

    private fun restoreDeviceProxy() {
        if (store.macProxyLease() != null) {
            macRecovery.restoreOwnedLease()
            return
        }
        val previous = store.previousProxy()
        if (previous?.get("controller") == "macos") {
            store.save(legacyMacLease(previous))
            macRecovery.restoreOwnedLease()
            return
        }
        // Android cleanup must never be attempted for an iOS capture. A stale or
        // partially-written proxy record can otherwise route an iOS UDID through
        // adb, which reports the opaque `<udid>:features` host-service error.
        val deviceSerial = androidDeviceForCleanup() ?: return
        if (previous?.get("controller") == "android-relay") {
            val relay = NativeAndroidRelayController.Lease(
                remotePath = previous["relayPath"] ?: return,
                relayPid = previous["relayPid"] ?: return,
                relayPort = previous["relayPort"]?.toIntOrNull() ?: return,
                upstreamPort = previous["relayUpstreamPort"]?.toIntOrNull() ?: return,
            )
            androidRelay.stop(deviceSerial, relay)
        }
        androidProxy.restore(deviceSerial, previous)
    }

    private fun restoreProxy(previous: Map<String, String?>?) {
        if (previous?.get("controller") == "macos") {
            macProxy.restore(previous)
        } else {
            androidDeviceForCleanup()?.let { androidProxy.restore(it, previous) }
        }
    }

    private fun captureTargetForCleanup(): CaptureTarget? = store.captureId()
        ?.let(captureRepository::session)
        ?.target

    private fun androidDeviceForCleanup(): String? {
        captureTargetForCleanup()?.let { target ->
            return target.deviceId.takeIf { target.platform.equals("android", ignoreCase = true) }
        }
        return sessions.load()?.deviceSerial?.takeUnless { it.startsWith("ios-simulator:") }
    }

    private fun reconcileRunningState() {
        if (!store.isRunning()) return
        if (currentCaptureHealthy()) return
        store.captureId()?.let(captures::interrupt)
        restoreDeviceProxy()
        store.clearRunning()
        store.clearWorkerReady()
        store.clearSupervisorReady()
    }

    private fun currentCaptureHealthy(): Boolean {
        val endpoint = store.endpoint() ?: return false
        val port = endpoint.substringAfterLast(':').toIntOrNull() ?: return false
        val token = store.captureToken() ?: return false
        val captureId = store.captureId() ?: return false
        val worker = store.workerIdentity() ?: return false
        if (!store.workerReady(NativeCaptureLease(captureId, token, endpoint, worker))) return false
        if (!workerIdentityIsCurrent() || !probeListener(port)) return false
        val hasMacLease = store.macProxyLease() != null
        return !hasMacLease || store.supervisorReady(token, store.supervisorPid())
    }

    private fun workerIdentityIsCurrent(): Boolean = store.workerIdentity()?.let { worker ->
        processStartIdentity(worker.pid) == worker.startIdentity
    } == true

    private fun processStartIdentity(pid: Int): String? = processes.run(
        listOf("ps", "-o", "lstart=", "-p", pid.toString()),
    ).takeIf { it.exitCode == 0 }?.stdout?.trim()?.takeIf(String::isNotBlank)

    private fun launchSupervisor(port: Int, captureToken: String): Int {
        val executable = getenv("LYNX_EXECUTABLE")?.toKString()?.takeIf(String::isNotBlank)
            ?: nativeExecutablePath()
            ?: "lynx"
        val launch = processes.run(
            listOf("sh", "-c", "LYNX_CAPTURE_TOKEN='$captureToken' nohup '$executable' network supervisor $port >/dev/null 2>&1 </dev/null & echo \$!"),
        )
        return launch.stdout.trim().toIntOrNull()
            ?: error("native proxy supervisor did not report a process ID")
    }

    private fun waitForSupervisor(captureToken: String, supervisorPid: Int): Boolean {
        repeat(40) {
            if (store.supervisorReady(captureToken, supervisorPid)) return true
            usleep(50_000u)
        }
        return false
    }

    private fun probeListener(port: Int): Boolean = runCatching {
        val fd = connect("127.0.0.1", port)
        close(fd)
        true
    }.getOrDefault(false)

    private fun legacyMacLease(previous: Map<String, String?>): NativeMacProxyLease {
        val service = previous["service"] ?: error("PROXY_RECOVERY_REQUIRED: legacy macOS proxy state has no service")
        val endpoint = store.endpoint() ?: error("PROXY_RECOVERY_REQUIRED: legacy macOS proxy state has no endpoint")
        val port = endpoint.substringAfterLast(':').toIntOrNull()
            ?: error("PROXY_RECOVERY_REQUIRED: legacy macOS proxy state has invalid endpoint $endpoint")
        fun setting(kind: String) = NativeMacProxySetting(
            enabled = when (previous["${kind}Enabled"]) {
                "Yes" -> true
                "No" -> false
                else -> error("PROXY_RECOVERY_REQUIRED: legacy macOS proxy state lacks ${kind}Enabled")
            },
            server = previous["${kind}Server"],
            port = previous["${kind}Port"],
        )
        val installed = NativeMacProxySetting(enabled = true, server = "127.0.0.1", port = port.toString())
        return NativeMacProxyLease(
            leaseId = "legacy_${randomId()}",
            service = service,
            endpoint = endpoint,
            webOriginal = setting("web"),
            secureOriginal = setting("secure"),
            webInstalled = installed,
            secureInstalled = installed,
            phase = NativeMacProxyLeasePhase.ACTIVE,
            webApplied = true,
            secureApplied = true,
        )
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
        val captureToken = getenv("LYNX_CAPTURE_TOKEN")?.toKString()?.takeIf(String::isNotBlank)
        val captureId = getenv("LYNX_CAPTURE_ID")?.toKString()?.takeIf(String::isNotBlank)
        val endpoint = getenv("LYNX_CAPTURE_ENDPOINT")?.toKString()?.takeIf(String::isNotBlank)
        val workerStartIdentity = processStartIdentity(getpid())
        if (captureToken != null && captureId != null && endpoint != null && workerStartIdentity != null) {
            store.markWorkerReady(NativeCaptureLease(captureId, captureToken, endpoint, NativeWorkerIdentity(getpid(), workerStartIdentity)))
        } else store.markWorkerReady(port)
        try {
            while (store.isRunning()) {
                val client = accept(server, null, null)
                if (client >= 0) clientDispatcher.dispatch { handle(client) } else usleep(50_000u)
            }
        } finally {
            runCatching { restoreDeviceProxy() }
            store.clearWorkerReady()
            close(server)
        }
    }

    fun supervisor(port: Int) {
        val token = getenv("LYNX_CAPTURE_TOKEN")?.toKString()?.takeIf(String::isNotBlank) ?: return
        val worker = store.workerIdentity() ?: return
        val captureId = store.captureId() ?: return
        val endpoint = store.endpoint() ?: return
        if (!store.workerReady(NativeCaptureLease(captureId, token, endpoint, worker))) return
        val monitor = NativeMacProxyWorkerSupervisor(
            worker = worker,
            identityForPid = ::processStartIdentity,
            listenerHealthy = { probeListener(port) },
            restoreOwnedProxy = { restoreDeviceProxy() },
            clearRuntime = {
                store.clearRunning()
                store.clearWorkerReady()
                store.clearSupervisorReady()
            },
        )
        if (!monitor.checkOnce()) return
        store.markSupervisorReady(token, getpid())
        while (store.isRunning() && monitor.checkOnce()) usleep(100_000u)
    }

    private fun handle(client: Int) {
        // The listener is non-blocking so stop can observe state; accepted
        // sockets must be blocking for TLS handshakes and complete bodies.
        fcntl(client, F_SETFL, fcntl(client, F_GETFL) and O_NONBLOCK.inv())
        val started = getTimeMillis()
        var admissionDecision: ConnectionAdmission.Decision? = null
        var relayPrefaceSeen = false
        var verifiedOrigin: VerifiedOrigin? = null
        try {
            val relayPrefix = readRelayPreface(client) {
                relayPrefaceSeen = true
                val attached = sessions.load() ?: error("relay metadata has no attached session")
                verifiedOrigin = VerifiedOrigin(
                    target = captureTarget(attached),
                    process = ProcessIdentity(attached.processId, "android-relay:${attached.processId}"),
                    method = it.method,
                )
            }
            admissionDecision = admission(client)
            if (admissionDecision is ConnectionAdmission.Decision.Intercept) {
                verifiedOrigin = admissionDecision.origin
            }
            val raw = readHeaders(client, relayPrefix)
            val request = NativeHttpParser.parseRequest(raw)
            if (admissionDecision is ConnectionAdmission.Decision.PassThrough) {
                forwardWithoutRecording(client, request, raw)
                return
            }
            val requestId = RequestId("req_${randomId()}")
            if (request.method.equals("CONNECT", true)) {
                handleConnect(client, request.url, requestId, started, verifiedOrigin)
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
                    relayPlainWebSocket(client, upstream, request, requestId, responseHeadValue, started, verifiedOrigin)
                    return
                }
                val responseBody = readUntilClose(upstream)
                sendBytes(client, responseHead.encodeToByteArray() + responseBody)
                val response = responseHeadValue.copy(
                    body = NativeHttpBodyDecoder.decode(
                        responseHeadValue.headers.mapValues { it.value.joinToString(", ") }, responseBody,
                    ).decodeToString(),
                )
                appendTarget(exchange(request, requestId, response, null, started), verifiedOrigin)
            } finally { close(upstream) }
        } catch (t: Throwable) {
            // Foreign/unknown iOS connections are deliberately opaque. Never retain their
            // parse/TLS failures as if they belonged to the attached app.
            if (!relayPrefaceSeen && admissionDecision !is ConnectionAdmission.Decision.PassThrough) {
                runCatching { store.append(failureExchange(client, started, t.message ?: t::class.simpleName.orEmpty())) }
            }
        } finally { close(client) }
    }

    private fun admission(client: Int): ConnectionAdmission.Decision? {
        val session = sessions.load() ?: return null
        if (!session.deviceSerial.startsWith("ios-simulator:")) return null
        val tuple = nativeAcceptedSocketTuple(client)
            ?: return ConnectionAdmission.Decision.PassThrough("accepted socket tuple unavailable")
        val target = CaptureTarget("ios", session.deviceSerial.removePrefix("ios-simulator:"), session.packageName)
        return ConnectionAdmission(ownerResolver ?: return ConnectionAdmission.Decision.PassThrough("ownership resolver unavailable"))
            .decide(target, tuple)
    }

    private fun forwardWithoutRecording(client: Int, request: NativeHttpParser.Request, raw: String) {
        if (request.method.equals("CONNECT", true)) {
            val host = request.url.substringBeforeLast(':').ifBlank { request.url }
            val port = request.url.substringAfterLast(':', "443").toIntOrNull() ?: 443
            val upstream = connect(host, port)
            try {
                sendBytes(client, "HTTP/1.1 200 Connection Established\r\nProxy-Agent: lynx\r\n\r\n".encodeToByteArray())
                relayOpaque(client, upstream)
            } finally { close(upstream) }
            return
        }
        val target = parseTarget(request.url, request.headers["Host"] ?: request.headers["host"])
        val upstream = connect(target.first, target.second)
        try {
            sendBytes(upstream, NativeHttpParser.originFormRequest(raw, request).encodeToByteArray())
            sendBytes(client, readHeaders(upstream).encodeToByteArray() + readUntilClose(upstream))
        } finally { close(upstream) }
    }

    private fun handleConnect(client: Int, authority: String, requestId: RequestId, started: Long, origin: VerifiedOrigin?) {
        val host = authority.substringBeforeLast(':').ifBlank { authority }
        val port = authority.substringAfterLast(':', "443").toIntOrNull() ?: 443
        try {
            sendBytes(client, "HTTP/1.1 200 Connection Established\r\nProxy-Agent: lynx\r\n\r\n".encodeToByteArray())
            val leaf = try { certificates.ensureLeaf(host) } catch (error: Throwable) {
                throw NativeTlsFailure("CA_LEAF_SIGN_FAILED", error)
            }
            val tlsProvider = nativeTlsProvider()
            // Negotiate with the origin first. A downstream client may prefer h2 even when the
            // origin is HTTP/1.1-only; choose the downstream ALPN based on the origin so we never
            // advertise h2 to the app and then fail the upstream handshake.
            val upstreamFd = try { connect(host, port) } catch (error: Throwable) {
                throw NativeTlsFailure("TLS_UPSTREAM_FAILED", error)
            }
            var upstream = try {
                tlsProvider.client(upstreamFd, host, enableHttp2 = true)
            } catch (error: Throwable) {
                close(upstreamFd)
                throw NativeTlsFailure("TLS_UPSTREAM_FAILED", error)
            }
            val upstreamUsesHttp2 = upstream.applicationProtocol == "h2"
            val downstream = try {
                tlsProvider.server(client, leaf.certificate, leaf.privateKey, enableHttp2 = upstreamUsesHttp2)
            } catch (error: Throwable) {
                upstream.close()
                throw NativeTlsFailure("TLS_CLIENT_REJECTED_CERTIFICATE", error)
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
                    val http1Fd = try { connect(host, port) } catch (error: Throwable) {
                        throw NativeTlsFailure("TLS_UPSTREAM_FAILED", error)
                    }
                    upstream = try {
                        tlsProvider.client(http1Fd, host, enableHttp2 = false)
                    } catch (error: Throwable) {
                        close(http1Fd)
                        throw NativeTlsFailure("TLS_UPSTREAM_FAILED", error)
                    }
                    upstreamClosed = false
                    negotiatedUpstreamUsesHttp2 = upstream.applicationProtocol == "h2"
                }
                require(downstreamUsesHttp2 == negotiatedUpstreamUsesHttp2) {
                    "TLS application protocol mismatch: app negotiated ${downstream.applicationProtocol ?: "HTTP/1.1"}, " +
                        "origin negotiated ${upstream.applicationProtocol ?: "HTTP/1.1"}"
                }
                if (downstreamUsesHttp2) {
                    relayTlsHttp2(downstream, upstream, host, requestId, started, origin)
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
                        observedRequest, requestId, response.copy(body = ""), started, origin,
                    )
                } else {
                    val responseBody = upstreamReader.readUntilClose()
                    downstream.write(responseHead.encodeToByteArray() + responseBody)
                    val capturedBody = NativeHttpBodyDecoder.decode(
                        response.headers.mapValues { it.value.joinToString(", ") }, responseBody,
                    ).decodeToString()
                    appendTarget(exchange(observedRequest, requestId, response.copy(body = capturedBody), null, started), origin)
                }
            } finally {
                if (!upstreamClosed) upstream.close()
                downstream.close()
            }
        } catch (t: Throwable) {
            appendTarget(NetworkExchange(nativeNetworkEvidenceMeta(EvidenceId("ev_${randomId()}"), sessions.load()), requestId, NetworkRequest("CONNECT", "https://$host", emptyMap(), null), null, NetworkFailure(nativeTlsFailureKind(t), t.message), NetworkTiming(started, getTimeMillis(), getTimeMillis() - started), NetworkCaptureMetadata(false, 0, false), protocol = "HTTPS"), origin)
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
        origin: VerifiedOrigin?,
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
                            decodeHttp2(requestDecoder, bytes, host, connectId, started, origin).forEach { decoded ->
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
                            decodeHttp2(responseDecoder, bytes, host, connectId, started, origin).forEach { decoded ->
                                responses[decoded.streamId] = decoded
                            }
                        }
                    }
                    flushHttp2Exchanges(requests, responses, host, connectId, started, origin)
                }
            }
        } finally {
            requestDecoder.close()
            responseDecoder.close()
            // Preserve one-sided failures instead of silently dropping streams when a peer closes.
            requests.values.forEach { pending ->
                if (responses.remove(pending.exchange.streamId) == null) {
                    appendTarget(http2Exchange(pending, null, host, connectId, started, NetworkFailure("HTTP2_INCOMPLETE", "response stream closed before END_STREAM")), origin)
                }
            }
            responses.values.forEach { response ->
                    appendTarget(http2Exchange(null, response, host, connectId, started, NetworkFailure("HTTP2_INCOMPLETE", "request stream closed before END_STREAM")), origin)
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
        origin: VerifiedOrigin?,
    ): List<NativeHttp2Decoder.Exchange> = try {
        decoder.feed(bytes)
    } catch (t: Throwable) {
        appendTarget(http2Exchange(null, null, host, connectId, started, NetworkFailure("HTTP2_DECODER_ERROR", t.message ?: t::class.simpleName)), origin)
        emptyList()
    }

    private fun flushHttp2Exchanges(
        requests: MutableMap<Int, H2Request>,
        responses: MutableMap<Int, NativeHttp2Decoder.Exchange>,
        host: String,
        connectId: RequestId,
        started: Long,
        origin: VerifiedOrigin?,
    ) {
        val complete = requests.keys.intersect(responses.keys).toList()
        complete.forEach { streamId ->
            val request = requests.remove(streamId) ?: return@forEach
            val response = responses.remove(streamId) ?: return@forEach
            appendTarget(http2Exchange(request, response, host, connectId, started, null), origin)
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
        val responseBody = response?.let {
            NativeHttpBodyDecoder.decode(response.headers, it.body)
                .takeIf(ByteArray::isNotEmpty)?.decodeToString()
        }
        val completed = getTimeMillis()
        val id = request?.requestId ?: RequestId("${connectId.value}_${response?.streamId ?: "unknown"}")
        return NetworkExchange(
            meta = nativeNetworkEvidenceMeta(EvidenceId("ev_${randomId()}"), sessions.load()),
            requestId = id,
            request = NetworkRequest(method, url, requestHeaders, requestBody),
            response = response?.let { NetworkResponse(status, responseHeaders, responseBody) },
            failure = failure,
            timing = NetworkTiming(started, completed, (completed - started).coerceAtLeast(0)),
            capture = NetworkCaptureMetadata(false, request?.exchange?.body?.size?.toLong() ?: 0, false),
            protocol = "HTTP/2",
        )
    }

    private fun appendTarget(exchange: NetworkExchange, origin: VerifiedOrigin?) {
        store.append(exchange)
        val captureId = store.captureId() ?: return
        if (origin != null) {
            captureRepository.append(
                VerifiedExchange(
                    context = ConnectionContext(captureId, exchange.requestId.value, origin, exchange.timing.startedAtEpochMillis),
                    exchange = exchange,
                ),
            )
        }
    }

    private fun exchange(request: NativeHttpParser.Request, id: RequestId, response: NativeHttpParser.Response?, failure: NetworkFailure?, started: Long) = NetworkExchange(
        meta = nativeNetworkEvidenceMeta(EvidenceId("ev_${randomId()}"), sessions.load()),
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
    private fun connect(host: String, port: Int): Int = memScoped {
        val hints = alloc<addrinfo>()
        hints.ai_family = AF_UNSPEC
        hints.ai_socktype = SOCK_STREAM
        val result = allocPointerTo<addrinfo>()
        require(getaddrinfo(host, port.toString(), hints.ptr, result.ptr) == 0) { "unable to resolve $host" }
        val first = result.value ?: error("unable to resolve $host")
        var candidate: CPointer<addrinfo>? = first
        while (candidate != null) {
            val info = candidate.pointed
            val fd = socket(info.ai_family, info.ai_socktype, info.ai_protocol)
            if (fd >= 0) {
                if (platform.posix.connect(fd, info.ai_addr, info.ai_addrlen) == 0) {
                    freeaddrinfo(first)
                    return@memScoped fd
                }
                close(fd)
            }
            candidate = info.ai_next
        }
        freeaddrinfo(first)
        error("unable to connect to $host:$port")
    }
    /** Read exactly through the header terminator. A bulk recv can consume the
     * beginning of a TLS ClientHello after CONNECT, so this intentionally reads
     * one byte at a time at the protocol boundary. */
    private fun readRelayPreface(fd: Int, seen: (AndroidRelayPreface) -> Unit): ByteArray {
        val prefix = ByteArray(4)
        val peeked = prefix.usePinned { recv(fd, it.addressOf(0), 4uL, MSG_PEEK) }
        if (peeked < 4) return ByteArray(0)
        val length = ((prefix[0].toInt() and 0xff) shl 24) or ((prefix[1].toInt() and 0xff) shl 16) or
            ((prefix[2].toInt() and 0xff) shl 8) or (prefix[3].toInt() and 0xff)
        if (length !in 1..64 * 1024) return ByteArray(0)
        readExactly(fd, 4)?.let { }
        val raw = readExactly(fd, length) ?: error("relay metadata preface truncated")
        val preface = Json { ignoreUnknownKeys = false }.decodeFromString(AndroidRelayPreface.serializer(), raw.decodeToString())
        val session = sessions.load() ?: error("relay metadata has no attached session")
        require(preface.protocolVersion == 1) { "unsupported relay protocol version" }
        require(preface.captureId == store.captureId()) { "relay capture ID does not match active capture" }
        require(preface.captureToken == store.captureToken()) { "relay capture token rejected" }
        require(preface.deviceSerial == session.deviceSerial && preface.packageName == session.packageName) { "relay target does not match attachment" }
        require(preface.decision == "verified_target") { "relay connection is not verified target" }
        seen(preface)
        return ByteArray(0)
    }
    private fun readHeaders(fd: Int, initial: ByteArray = ByteArray(0)): String { val bytes = initial.toMutableList(); while (bytes.size < 1024 * 1024) { if (bytes.size >= 4 && bytes.takeLast(4).toByteArray().decodeToString() == "\r\n\r\n") break; memScoped { val native = allocArray<ByteVar>(1); val count = recv(fd, native, 1uL, 0); if (count <= 0) return bytes.toByteArray().decodeToString(); bytes += native[0] } }; return bytes.toByteArray().decodeToString() }
    private fun readUntilClose(fd: Int): ByteArray { val bytes = mutableListOf<Byte>(); memScoped { val buffer = allocArray<ByteVar>(8192); while (true) { val count = recv(fd, buffer, 8192.convert(), 0); if (count <= 0) break; for (i in 0 until count) bytes += buffer[i] } }; return bytes.toByteArray() }
    private fun relayOpaque(left: Int, right: Int) = memScoped {
        val pollers = allocArray<pollfd>(2)
        val buffer = allocArray<ByteVar>(16 * 1024)
        var leftOpen = true
        var rightOpen = true
        while (leftOpen || rightOpen) {
            pollers[0].fd = left; pollers[0].events = POLLIN.toShort(); pollers[0].revents = 0
            pollers[1].fd = right; pollers[1].events = POLLIN.toShort(); pollers[1].revents = 0
            if (poll(pollers, 2u, 250) <= 0) continue
            for (index in 0..1) {
                if (pollers[index].revents.toInt() and (POLLIN or POLLHUP or POLLERR) == 0) continue
                val source = if (index == 0) left else right
                val destination = if (index == 0) right else left
                val count = recv(source, buffer, 16_384u, 0)
                if (count <= 0) {
                    if (index == 0) leftOpen = false else rightOpen = false
                } else {
                    var offset = 0
                    while (offset < count) {
                        val written = send(destination, buffer + offset, (count - offset).toULong(), 0)
                        if (written <= 0) { leftOpen = false; rightOpen = false; break }
                        offset += written.toInt()
                    }
                }
            }
        }
    }
    private fun relayPlainWebSocket(client: Int, upstream: Int, request: NativeHttpParser.Request, id: RequestId, response: NativeHttpParser.Response, started: Long, origin: VerifiedOrigin?) {
        val frames = mutableListOf<NetworkFrame>()
        fun publish() { appendTarget(exchange(request, id, response, null, started).copy(protocol = "WebSocket", frames = frames.toList()), origin) }
        publish()
        memScoped {
            val pollers = allocArray<pollfd>(2)
            var openClient = true; var openUpstream = true; val deadline = getTimeMillis() + 15 * 60_000
            while ((openClient || openUpstream) && getTimeMillis() < deadline) {
                pollers[0].fd = client; pollers[0].events = POLLIN.toShort(); pollers[0].revents = 0
                pollers[1].fd = upstream; pollers[1].events = POLLIN.toShort(); pollers[1].revents = 0
                if (poll(pollers, 2u, 250) < 0) break
                if (openClient && pollers[0].revents.toInt() and POLLIN != 0) {
                    val frame = readFrame(client) ?: run { openClient = false; continue }
                    sendBytes(upstream, frame.wireBytes); frames += frame.evidence("CLIENT_TO_SERVER"); publish()
                }
                if (openUpstream && pollers[1].revents.toInt() and POLLIN != 0) {
                    val frame = readFrame(upstream) ?: run { openUpstream = false; continue }
                    sendBytes(client, frame.wireBytes); frames += frame.evidence("SERVER_TO_CLIENT"); publish()
                }
            }
        }
        publish()
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
        origin: VerifiedOrigin?,
    ) {
        val frames = mutableListOf<NetworkFrame>()
        fun publish() { appendTarget(exchange(request, id, response, null, started).copy(protocol = "WebSocket", frames = frames.toList()), origin) }
        publish()
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
                        publish()
                    }
                }
                if (upstreamOpen && (upstreamReady || pollers[1].revents.toInt() and (POLLIN or POLLHUP or POLLERR) != 0)) {
                    val frame = NativeWebSocketFrameCodec.read(upstreamReader::readExactly)
                    if (frame == null) upstreamOpen = false else {
                        downstream.write(frame.wireBytes)
                        frames += frame.evidence("SERVER_TO_CLIENT")
                        publish()
                    }
                }
            }
        }
        publish()
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

    fun readUntilClose(): ByteArray {
        val bytes = buffered.toMutableList()
        buffered = ByteArray(0)
        while (true) {
            val next = connection.read() ?: break
            bytes.addAll(next.toList())
        }
        return bytes.toByteArray()
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
