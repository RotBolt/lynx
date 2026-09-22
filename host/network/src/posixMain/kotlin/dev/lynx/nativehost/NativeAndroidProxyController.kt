package dev.lynx.nativehost

/** Applies Android's device-wide proxy through the complete set of Settings.Global keys used by
 * the supported platform proxy consumers. The returned snapshot is persisted so a later CLI
 * invocation can restore every value exactly. */
class NativeAndroidProxyController(private val processes: NativeProcessRunner) {
    private val keys = listOf("http_proxy", "https_proxy", "global_http_proxy_host", "global_http_proxy_port")

    fun apply(deviceSerial: String, host: String, port: Int): Map<String, String?> {
        require(port in 1..65535) { "proxy port must be 1..65535" }
        require(host.isNotBlank() && host != "0.0.0.0") { "device proxy host must be reachable from the Android device" }
        val prefix = listOf("adb", "-s", deviceSerial, "shell", "settings")
        val previous = keys.associateWith { key ->
            val result = processes.run(prefix + listOf("get", "global", key))
            require(result.exitCode == 0) { result.stderr.ifBlank { "unable to read Android proxy setting $key" } }
            result.stdout.trim().takeIf { it.isNotEmpty() && !it.equals("null", ignoreCase = true) }
        }
        val values = mapOf(
            "http_proxy" to "$host:$port",
            "https_proxy" to "$host:$port",
            "global_http_proxy_host" to host,
            "global_http_proxy_port" to port.toString(),
        )
        try {
            values.forEach { (key, value) ->
                val result = processes.run(prefix + listOf("put", "global", key, value))
                require(result.exitCode == 0) { result.stderr.ifBlank { "unable to set Android proxy setting $key" } }
            }
        } catch (error: Throwable) {
            runCatching { restore(deviceSerial, previous) }
            throw error
        }
        return previous
    }

    fun restore(deviceSerial: String, previous: Map<String, String?>?) {
        if (previous == null) return
        val prefix = listOf("adb", "-s", deviceSerial, "shell", "settings")
        keys.forEach { key ->
            val value = previous[key]
            // ConnectivityService observes the deprecated combined setting, but
            // ignores an empty/deleted value. Deleting it can leave its cached
            // ProxyInfo active until reboot. Android's explicit direct-proxy
            // sentinel causes the observer to clear that cached proxy.
            val operation = when {
                key == "http_proxy" && value.isNullOrBlank() -> listOf("put", "global", key, ":0")
                value.isNullOrBlank() -> listOf("delete", "global", key)
                else -> listOf("put", "global", key, value)
            }
            val result = processes.run(prefix + operation)
            require(result.exitCode == 0) { result.stderr.ifBlank { "unable to restore Android proxy setting $key" } }
        }
    }
}
