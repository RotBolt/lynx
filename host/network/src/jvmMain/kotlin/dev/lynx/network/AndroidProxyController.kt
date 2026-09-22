package dev.lynx.network

import dev.lynx.adb.CommandRunner
import dev.lynx.adb.ProcessCommandRunner
import java.util.concurrent.atomic.AtomicBoolean

/** Device-wide proxy settings captured before Lynx changes them. */
data class AndroidProxyState(
    /** Legacy combined value returned by `settings get global http_proxy`. */
    val rawValue: String?,
    /** Other Android settings used by modern clients and emulator images. */
    val settings: Map<String, String?> = mapOf("http_proxy" to rawValue),
) {
    val isConfigured: Boolean
        get() = !rawValue.isNullOrBlank() && !rawValue.equals("null", ignoreCase = true)
}

class ProxyControllerException(
    val code: String,
    message: String,
    cause: Throwable? = null,
) : RuntimeException(message, cause)

/** A session-scoped, idempotent restoration lease. */
class ProxyLease internal constructor(
    val priorState: AndroidProxyState,
    val endpoint: String,
    private val restoreAction: () -> Unit,
) {
    private val restored = AtomicBoolean(false)

    fun restore() {
        if (restored.compareAndSet(false, true)) restoreAction()
    }

    val isRestored: Boolean get() = restored.get()
}

interface AndroidProxyController {
    fun inspect(): AndroidProxyState
    fun apply(endpoint: String): ProxyLease
    fun restore(lease: ProxyLease) = lease.restore()
}

/** Controls the device-wide Android HTTP proxy using only ADB shell settings. */
class AdbAndroidProxyController(
    private val deviceSerial: String,
    private val runner: CommandRunner = ProcessCommandRunner(),
    private val executable: String = dev.lynx.adb.AdbExecutableResolver.resolve(),
) : AndroidProxyController {
    private companion object {
        // Android has shipped multiple proxy consumers. Keeping all of these
        // aligned is the same device-wide setup used by desktop proxy tools;
        // setting only http_proxy is ignored by some engines/images.
        val PROXY_SETTINGS = listOf(
            "http_proxy",
            "https_proxy",
            "global_http_proxy_host",
            "global_http_proxy_port",
        )
    }
    private var activeLease: ProxyLease? = null

    @Synchronized
    override fun inspect(): AndroidProxyState {
        val values = PROXY_SETTINGS.associateWith { key ->
            val result = run(listOf("shell", "settings", "get", "global", key), "PROXY_INSPECT_FAILED")
            result.stdout.trim().ifBlank { null }
        }
        return AndroidProxyState(values["http_proxy"], values)
    }

    @Synchronized
    override fun apply(endpoint: String): ProxyLease {
        requireEndpoint(endpoint)
        val existing = activeLease
        if (existing != null && !existing.isRestored && existing.endpoint == endpoint) return existing
        if (existing != null && !existing.isRestored) existing.restore()

        val prior = inspect()
        try {
            val host = endpoint.substringBeforeLast(':')
            val port = endpoint.substringAfterLast(':')
            val values = mapOf(
                "http_proxy" to endpoint,
                "https_proxy" to endpoint,
                "global_http_proxy_host" to host,
                "global_http_proxy_port" to port,
            )
            values.forEach { (key, value) ->
                run(listOf("shell", "settings", "put", "global", key, value), "PROXY_APPLY_FAILED")
            }
            val lease = ProxyLease(prior, endpoint) { restoreState(prior) }
            activeLease = lease
            return lease
        } catch (error: ProxyControllerException) {
            // A partially applied setting must never be left behind.
            runCatching { restoreState(prior) }
            throw error
        }
    }

    @Synchronized
    override fun restore(lease: ProxyLease) {
        lease.restore()
        if (activeLease === lease) activeLease = null
    }

    private fun restoreState(state: AndroidProxyState) {
        PROXY_SETTINGS.forEach { key ->
            val value = state.settings[key]
            if (key == "http_proxy" && (value.isNullOrBlank() || value.equals("null", ignoreCase = true))) {
                // ConnectivityService observes the legacy combined setting but
                // ignores an empty/deleted value, leaving its cached ProxyInfo
                // active until reboot. Use the explicit direct-proxy sentinel
                // so the observer clears the in-memory proxy as well.
                run(listOf("shell", "settings", "put", "global", key, ":0"), "PROXY_RESTORE_FAILED")
            } else if (!value.isNullOrBlank() && !value.equals("null", ignoreCase = true)) {
                run(listOf("shell", "settings", "put", "global", key, value), "PROXY_RESTORE_FAILED")
            } else {
                run(listOf("shell", "settings", "delete", "global", key), "PROXY_RESTORE_FAILED")
            }
        }
    }

    private fun run(args: List<String>, code: String) = try {
        runner.run(listOf(executable, "-s", deviceSerial, *args.toTypedArray())).also {
            if (it.exitCode != 0) throw ProxyControllerException(code, it.stderr.ifBlank { "ADB proxy command failed" }.trim())
        }
    } catch (error: ProxyControllerException) {
        throw error
    } catch (error: Exception) {
        throw ProxyControllerException(code, error.message ?: "ADB proxy command failed", error)
    }

    private fun requireEndpoint(endpoint: String) {
        if (!Regex("^[^:\\s]+:[0-9]{1,5}$").matches(endpoint)) {
            throw ProxyControllerException("PROXY_ENDPOINT_INVALID", "Proxy endpoint must be host:port")
        }
        val port = endpoint.substringAfterLast(':').toInt()
        if (port !in 1..65535) throw ProxyControllerException("PROXY_ENDPOINT_INVALID", "Proxy port must be 1..65535")
    }
}

object AndroidProxyEndpoint {
    /** Resolves a host bind address to the endpoint visible from the target device. */
    fun forDevice(deviceSerial: String, bindHost: String, port: Int): String {
        if (port !in 1..65535) throw ProxyControllerException("PROXY_ENDPOINT_INVALID", "Proxy port must be 1..65535")
        val host = when {
            bindHost != "0.0.0.0" -> bindHost
            deviceSerial.startsWith("emulator-") -> "10.0.2.2"
            else -> throw ProxyControllerException(
                "PHYSICAL_DEVICE_ENDPOINT_REQUIRED",
                "A physical device cannot reach 0.0.0.0; provide the host LAN address explicitly",
            )
        }
        return "$host:$port"
    }
}
