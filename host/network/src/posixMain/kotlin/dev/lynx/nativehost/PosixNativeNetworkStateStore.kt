package dev.lynx.nativehost

import dev.lynx.model.NetworkCapabilities
import dev.lynx.model.NetworkExchange
import dev.lynx.model.NetworkFilter
import dev.lynx.model.RequestId
import kotlinx.cinterop.ExperimentalForeignApi
import kotlinx.cinterop.ByteVar
import kotlinx.cinterop.allocArray
import kotlinx.cinterop.memScoped
import kotlinx.cinterop.toKString
import kotlinx.serialization.Serializable
import kotlinx.serialization.json.Json
import platform.posix.getenv
import platform.posix.fopen
import platform.posix.fclose
import platform.posix.fgets
import platform.posix.fputs
import platform.posix.getpid
import platform.posix.remove
import platform.posix.rename

@Serializable
private data class RuntimeState(
    val endpoint: String,
    val capabilities: NetworkCapabilities,
    val previousProxy: Map<String, String?>? = null,
    val workerPid: Int? = null,
    val workerStartIdentity: String? = null,
    val supervisorPid: Int? = null,
    val captureToken: String? = null,
)

/** JSONL evidence store. Files are intentionally plain and portable across native processes. */
@OptIn(ExperimentalForeignApi::class)
class PosixNativeNetworkStateStore(
    private val root: String = (getenv("HOME")?.toKString()?.takeIf(String::isNotBlank) ?: "/tmp") + "/.lynx/native-network",
    private val json: Json = Json { ignoreUnknownKeys = true; encodeDefaults = true },
) : NativeNetworkStateStore, NativeMacProxyLeaseStore {
    private val statePath get() = "$root/state.json"
    private val macProxyLeasePath get() = "$root/mac-proxy-lease.json"
    private val evidencePath get() = "$root/exchanges.jsonl"
    private val readyPath get() = "$root/worker.ready"
    private val supervisorReadyPath get() = "$root/supervisor.ready"

    override fun isRunning(): Boolean = readState() != null
    override fun endpoint(): String? = readState()?.endpoint
    override fun capabilities(): NetworkCapabilities? = readState()?.capabilities
    fun workerPid(): Int? = readState()?.workerPid
    fun workerIdentity(): NativeWorkerIdentity? = readState()?.let { state ->
        state.workerPid?.let { pid -> state.workerStartIdentity?.let { NativeWorkerIdentity(pid, it) } }
    }
    fun supervisorPid(): Int? = readState()?.supervisorPid
    fun captureToken(): String? = readState()?.captureToken

    override fun setRunning(endpoint: String, capabilities: NetworkCapabilities, previousProxy: Map<String, String?>?) {
        setRunning(endpoint, capabilities, previousProxy, workerPid = null, workerStartIdentity = null, supervisorPid = null, captureToken = null)
    }

    fun setRunning(
        endpoint: String,
        capabilities: NetworkCapabilities,
        previousProxy: Map<String, String?>?,
        workerPid: Int?,
        workerStartIdentity: String?,
        supervisorPid: Int?,
        captureToken: String?,
    ) {
        ensureRoot()
        writeText(
            statePath,
            json.encodeToString(
                RuntimeState.serializer(),
                RuntimeState(endpoint, capabilities, previousProxy, workerPid, workerStartIdentity, supervisorPid, captureToken),
            ),
        )
    }
    override fun previousProxy(): Map<String, String?>? = readState()?.previousProxy

    override fun clearRunning() { remove(statePath) }

    /** Marks the detached worker ready only after its listener has bound successfully. */
    fun markWorkerReady(port: Int) {
        ensureRoot()
        writeText(readyPath, "$port\n")
    }

    fun markWorkerReady(port: Int, captureToken: String, workerPid: Int) {
        ensureRoot()
        writeText(readyPath, "$port $captureToken $workerPid\n")
    }

    fun workerReady(): Boolean = readText(readyPath)?.trim()?.isNotEmpty() == true
    fun workerReady(port: Int): Boolean = readText(readyPath)?.trim() == port.toString()
    fun workerReady(port: Int, captureToken: String, workerPid: Int?): Boolean {
        val parts = readText(readyPath)?.trim()?.split(Regex("\\s+")) ?: return false
        if (parts.size != 3) return false
        if (parts[0].toIntOrNull() != port || parts[1] != captureToken) return false
        val readyPid = parts[2].toIntOrNull() ?: return false
        return workerPid == null || readyPid == workerPid
    }

    fun clearWorkerReady() { remove(readyPath) }

    fun markSupervisorReady(captureToken: String, supervisorPid: Int) {
        ensureRoot()
        writeText(supervisorReadyPath, "$captureToken $supervisorPid\n")
    }

    fun supervisorReady(captureToken: String, supervisorPid: Int?): Boolean {
        val parts = readText(supervisorReadyPath)?.trim()?.split(Regex("\\s+")) ?: return false
        if (parts.size != 2 || parts[0] != captureToken) return false
        val readyPid = parts[1].toIntOrNull() ?: return false
        return supervisorPid == null || readyPid == supervisorPid
    }

    fun clearSupervisorReady() { remove(supervisorReadyPath) }

    override fun load(): NativeMacProxyLease? = macProxyLease()

    fun macProxyLease(): NativeMacProxyLease? = readText(macProxyLeasePath)?.trim()?.takeIf(String::isNotEmpty)?.let {
        runCatching { json.decodeFromString(NativeMacProxyLease.serializer(), it) }.getOrNull()
    }

    override fun save(lease: NativeMacProxyLease) {
        ensureRoot()
        writeText(macProxyLeasePath, json.encodeToString(NativeMacProxyLease.serializer(), lease))
    }

    override fun clear() { remove(macProxyLeasePath) }

    override fun append(exchange: NetworkExchange) {
        ensureRoot()
        val file = fopen(evidencePath, "a") ?: error("unable to open native network evidence store")
        try { fputs(json.encodeToString(NetworkExchange.serializer(), exchange) + "\n", file) } finally { fclose(file) }
    }

    override fun list(filter: NetworkFilter): List<NetworkExchange> {
        val urlSubstring = filter.urlSubstring
        val sinceEpochMillis = filter.sinceEpochMillis
        val values = readLines().mapNotNull { line -> runCatching { json.decodeFromString(NetworkExchange.serializer(), line) }.getOrNull() }
            .filter { exchange ->
                (filter.method == null || exchange.request.method.equals(filter.method, ignoreCase = true)) &&
                    (filter.status == null || exchange.response?.status == filter.status) &&
                    (urlSubstring == null || exchange.request.url.contains(urlSubstring, ignoreCase = true)) &&
                    (sinceEpochMillis == null || exchange.timing.startedAtEpochMillis >= sinceEpochMillis)
            }
        return filter.limit?.let { values.takeLast(it) } ?: values
    }

    override fun get(requestId: RequestId): NetworkExchange? = list().lastOrNull { it.requestId == requestId }

    private fun readState(): RuntimeState? = readText(statePath)?.trim()?.takeIf(String::isNotEmpty)?.let {
        runCatching { json.decodeFromString(RuntimeState.serializer(), it) }.getOrNull()
    }

    private fun readLines(): List<String> {
        val file = fopen(evidencePath, "r") ?: return emptyList()
        val result = mutableListOf<String>()
        try {
            memScoped {
                val buffer = allocArray<ByteVar>(64 * 1024)
                while (fgets(buffer, 64 * 1024, file) != null) result += buffer.toKString().trimEnd()
            }
        } finally { fclose(file) }
        return result
    }

    private fun ensureRoot() { PosixProcessRunner().run(listOf("mkdir", "-p", root)) }
    private fun writeText(path: String, value: String) = memScoped {
        // State is read concurrently by the detached proxy worker. Replacing the target in place
        // briefly truncates it, which can make isRunning() mistake an update for network stop.
        val temporaryPath = "$path.tmp.${getpid()}.${kotlin.time.Clock.System.now().toEpochMilliseconds()}"
        val file = fopen(temporaryPath, "w") ?: error("unable to write temporary native network state")
        val writeStatus = try { fputs(value, file) } finally { fclose(file) }
        if (writeStatus < 0 || rename(temporaryPath, path) != 0) {
            remove(temporaryPath)
            error("unable to replace native network state")
        }
    }
    private fun readText(path: String): String? {
        val file = fopen(path, "r") ?: return null
        return try { buildString { memScoped { val buffer = allocArray<ByteVar>(64 * 1024); while (fgets(buffer, 64 * 1024, file) != null) append(buffer.toKString()) } } } finally { fclose(file) }
    }
}
