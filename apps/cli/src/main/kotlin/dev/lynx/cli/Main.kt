package dev.lynx.cli

import dev.lynx.daemon.LocalDaemonServer
import dev.lynx.daemon.DaemonPaths
import dev.lynx.daemon.LocalDaemonClient
import dev.lynx.adb.AdbClient
import dev.lynx.adb.AdbException
import dev.lynx.adb.ProcessCommandRunner
import dev.lynx.network.HttpProxyCapture
import dev.lynx.database.AdbDatabaseSource
import dev.lynx.network.AdbAndroidProxyController
import dev.lynx.network.AndroidProxyEndpoint
import dev.lynx.network.CertificateAuthorityManager
import dev.lynx.daemon.NetworkCaptureSource
import dev.lynx.daemon.NetworkCaptureConfig
import dev.lynx.daemon.NetworkDomainEvent
import dev.lynx.daemon.NetworkCapabilities
import dev.lynx.adb.ResolvedTarget
import dev.lynx.model.NetworkExchange
import dev.lynx.model.RequestId
import kotlinx.coroutines.flow.Flow
import com.google.gson.JsonObject
import com.google.gson.Gson
import com.google.gson.JsonParser

private const val VERSION = "0.1.0-SNAPSHOT"

fun main(args: Array<String>) {
    try {
        when (args.firstOrNull()) {
            "attach", "status", "detach", "network", "db" -> runInvocation(CommandLine.parseInvocation(args))
            else -> when (CommandLine.parse(args)) {
            CliCommand.VERSION -> println("lynx $VERSION")
            CliCommand.DOCTOR -> runDoctor()
            CliCommand.DEVICES -> runDevices()
            CliCommand.DAEMON -> runDaemon()
            CliCommand.HELP -> println("Usage: lynx [--version|doctor|devices|attach|status|detach|network|db|daemon]")
            }
        }
    } catch (error: AdbException) {
        System.err.println("ERROR ${error.code}: ${error.message}")
        kotlin.system.exitProcess(1)
    } catch (error: IllegalArgumentException) {
        System.err.println(error.message)
        kotlin.system.exitProcess(2)
    }
}

private fun runInvocation(invocation: CliInvocation) {
    val json = invocation is CliInvocation.Json
    val actual = if (invocation is CliInvocation.Json) invocation.delegate else invocation
    if (actual is CliInvocation.NetworkCaShow || actual is CliInvocation.NetworkCaInstall || actual is CliInvocation.NetworkCaRemove || actual is CliInvocation.NetworkCaInstallTarget) {
        runCertificateAuthority(actual, json)
        return
    }
    val command = when (actual) {
        is CliInvocation.Attach -> if (json) "ATTACH" else "ATTACH ${actual.deviceSerial ?: "-"} ${actual.packageName}"
        CliInvocation.Status -> "STATUS"
        CliInvocation.Detach -> "DETACH"
        CliInvocation.NetworkStart -> "NETWORK_START"
        CliInvocation.NetworkStop -> "NETWORK_STOP"
        CliInvocation.NetworkList -> "NETWORK_LIST"
        CliInvocation.DbList -> "DB_LIST"
        is CliInvocation.DbSnapshot -> if (json) "DB_SNAPSHOT" else "DB_SNAPSHOT ${actual.database}"
        is CliInvocation.Raw -> actual.command
        CliInvocation.NetworkCaShow, CliInvocation.NetworkCaInstall, CliInvocation.NetworkCaRemove -> error("handled locally")
        is CliInvocation.Json -> error("nested JSON invocation")
    }
    try {
        val client = LocalDaemonClient(DaemonPaths.defaultSocket())
        val response = if (json) {
            val request = JsonObject().apply {
                addProperty("protocol_version", 1)
                addProperty("schema_version", "lynx.v1")
                addProperty("request_id", "cli_${System.nanoTime()}")
                addProperty("command", command)
                val arguments = JsonObject()
                when (val item = actual) {
                    is CliInvocation.Attach -> { arguments.addProperty("device", item.deviceSerial ?: "-"); arguments.addProperty("package", item.packageName) }
                    is CliInvocation.DbSnapshot -> arguments.addProperty("database_id", item.database)
                    is CliInvocation.Raw -> item.arguments.forEach { (key, value) -> arguments.addProperty(key, value) }
                    else -> Unit
                }
                add("arguments", arguments)
            }
            client.executeJson(Gson().toJson(request))
        } else client.execute(command)
        if (json && actual is CliInvocation.Raw && actual.command == "NETWORK_WATCH" && response.contains("\"payload\"")) {
            val payload = JsonParser.parseString(response).asJsonObject.getAsJsonArray("payload")
            payload.forEach { println(Gson().toJson(it)) }
        } else println(response)
        if (response.startsWith("ERROR ")) kotlin.system.exitProcess(1)
    } catch (error: Exception) {
        throw IllegalArgumentException("Could not reach Lynx daemon: ${error.message}", error)
    }
}

private fun runCertificateAuthority(invocation: CliInvocation, json: Boolean) {
    val manager = CertificateAuthorityManager()
    val payload: Any = when (invocation) {
        CliInvocation.NetworkCaShow -> manager.show()
        CliInvocation.NetworkCaInstall -> manager.install()
        CliInvocation.NetworkCaRemove -> manager.remove()
        is CliInvocation.NetworkCaInstallTarget -> {
            val state = manager.ensure()
            val path = state.pemPath?.let(java.nio.file.Path::of) ?: error("CA PEM path unavailable")
            when (invocation.platform) {
                "android" -> dev.lynx.network.AndroidCertificateInstaller().install(invocation.target, path)
                "ios-simulator" -> dev.lynx.network.AppleCertificateInstaller().installSimulator(invocation.target, path)
                else -> error("Unsupported certificate target: ${invocation.platform}")
            }
        }
        else -> error("not a certificate authority command")
    }
    if (!json) {
        when (payload) {
            is dev.lynx.network.CertificateAuthorityState -> println("OK CA configured=${payload.configured} fingerprint=${payload.fingerprint ?: "none"} pem=${payload.pemPath ?: "none"} trust=${payload.trustStatus}")
            is dev.lynx.network.CertificateAuthorityRemoval -> println("OK CA_REMOVED removed=${payload.removed} pem=${payload.pemPath ?: "none"}")
            is dev.lynx.network.CertificateInstallResult -> println("OK CA_INSTALL status=${payload.status} target=${payload.target} pem=${payload.certificatePath} message=${payload.message}")
        }
        return
    }
    val envelope = JsonObject().apply {
        addProperty("protocol_version", 1)
        addProperty("schema_version", "lynx.v1")
        addProperty("request_id", "cli_${System.nanoTime()}")
        addProperty("type", "network_ca")
        add("payload", Gson().toJsonTree(payload))
        addProperty("ok", true)
    }
    println(Gson().toJson(envelope))
}

private fun runDevices() {
    val devices = AdbClient(ProcessCommandRunner()).devices()
    if (devices.isEmpty()) {
        println("No online Android devices found")
    } else {
        devices.forEach { println("${it.serial}\t${it.state}") }
    }
}

private fun runDoctor() {
    val adb = AdbClient(ProcessCommandRunner())
    println("adb executable: ${adb.executablePath}")
    if (adb.executablePath == "adb") {
        println("adb status: not resolved (set ANDROID_HOME/ANDROID_SDK_ROOT or add platform-tools to PATH)")
        return
    }
    try {
        val result = ProcessCommandRunner().run(listOf(adb.executablePath, "version"))
        if (result.exitCode == 0) {
            val firstLine = result.stdout.lineSequence().firstOrNull { it.isNotBlank() }.orEmpty()
            println("adb status: available${if (firstLine.isNotBlank()) " ($firstLine)" else ""}")
        } else {
            println("adb status: executable failed (${result.stderr.ifBlank { "unknown error" }})")
        }
    } catch (error: AdbException) {
        println("adb status: unavailable (${error.message})")
    }
}

private fun runDaemon() {
    val socketPath = DaemonPaths.defaultSocket()
    val timeline = dev.lynx.model.InMemoryEvidenceTimeline()
    val networkFactory = dev.lynx.daemon.NetworkSourceFactory { target, evidence ->
        ManagedNetworkSource(target, HttpProxyCapture(evidence, dev.lynx.model.SessionId("daemon"), target.deviceSerial, target.packageName, target.pid))
    }
    val databaseFactory = dev.lynx.daemon.DatabaseSourceFactory { target -> AdbDatabaseSource(dev.lynx.model.SessionId("daemon"), target.deviceSerial, target.packageName, target.pid) }
    val service = dev.lynx.daemon.DaemonService(timeline = timeline, networkFactory = networkFactory, databaseFactory = databaseFactory)
    LocalDaemonServer(socketPath, service).use { server ->
        server.start()
        println("lynx daemon listening at $socketPath")
        Thread.currentThread().join()
    }
}

private class ManagedNetworkSource(
    private val target: ResolvedTarget,
    private val delegate: HttpProxyCapture,
) : NetworkCaptureSource {
    private val controller = AdbAndroidProxyController(target.deviceSerial)
    private var lease: dev.lynx.network.ProxyLease? = null
    private var appliedEndpoint: String? = null
    override val endpoint: String? get() = delegate.endpoint
    override val caCertificatePath: String? get() = delegate.caCertificatePath
    override suspend fun start(config: NetworkCaptureConfig) {
        delegate.start(config)
        try {
            val endpoint = AndroidProxyEndpoint.forDevice(target.deviceSerial, config.listenHost, delegate.port)
            lease = controller.apply(endpoint)
            val observed = controller.inspect().rawValue
            if (observed != endpoint) {
                throw dev.lynx.network.ProxyControllerException(
                    "PROXY_NOT_APPLIED",
                    "Android proxy verification returned ${observed ?: "unset"}; expected $endpoint",
                )
            }
            appliedEndpoint = endpoint
        } catch (error: Exception) {
            runCatching { delegate.stop() }
            lease?.let { runCatching { controller.restore(it) } }
            lease = null
            appliedEndpoint = null
            throw error
        }
    }
    override suspend fun stop() {
        var failure: Throwable? = null
        try {
            delegate.stop()
        } catch (error: Throwable) {
            failure = error
        } finally {
            lease?.let {
                try { controller.restore(it) } catch (error: Throwable) { if (failure == null) failure = error }
            }
            lease = null
            appliedEndpoint = null
        }
        failure?.let { throw it }
    }
    override fun events(): Flow<NetworkDomainEvent> = delegate.events()
    override suspend fun capabilities(): NetworkCapabilities {
        val base = delegate.capabilities()
        val endpoint = appliedEndpoint
        if (endpoint == null) return base.copy(proxyStatus = "stopped", proxyEndpoint = null)
        val observed = runCatching { controller.inspect().rawValue }.getOrNull()
        return base.copy(
            proxyEndpoint = endpoint,
            proxyStatus = when (observed) {
                endpoint -> "configured"
                null -> "unreachable"
                else -> "bypassed_or_replaced"
            },
            bypassLikely = if (delegate.exchanges().isEmpty()) null else false,
        )
    }
    override fun exchanges(): List<NetworkExchange> = delegate.exchanges()
    override fun exchange(requestId: RequestId): NetworkExchange? = delegate.exchange(requestId)
}
