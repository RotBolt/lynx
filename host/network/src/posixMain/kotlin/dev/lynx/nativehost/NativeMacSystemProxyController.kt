package dev.lynx.nativehost

/** Applies the macOS system proxy used by iOS Simulator traffic and persists an exact rollback. */
class NativeMacSystemProxyController(
    private val runner: NativeProcessRunner = PosixProcessRunner(),
    private val service: String = "Wi-Fi",
    private val executable: String = "/usr/sbin/networksetup",
) {
    fun apply(port: Int): Map<String, String?> {
        require(port in 1..65535) { "proxy port must be 1..65535" }
        val previous = inspect()
        try {
            run("-setwebproxy", service, "127.0.0.1", port.toString())
            run("-setsecurewebproxy", service, "127.0.0.1", port.toString())
            run("-setwebproxystate", service, "on")
            run("-setsecurewebproxystate", service, "on")
        } catch (error: Throwable) {
            runCatching { restore(previous) }
            throw error
        }
        return previous
    }

    fun restore(previous: Map<String, String?>?) {
        if (previous == null) return
        val targetService = previous["service"] ?: service
        restoreProxy(targetService, "web", previous)
        restoreProxy(targetService, "secure", previous)
    }

    private fun inspect(): Map<String, String?> {
        val web = readProxy("-getwebproxy")
        val secure = readProxy("-getsecurewebproxy")
        return mapOf(
            "controller" to "macos",
            "service" to service,
            "webEnabled" to if (web.enabled) "Yes" else "No",
            "webServer" to web.server,
            "webPort" to web.port,
            "secureEnabled" to if (secure.enabled) "Yes" else "No",
            "secureServer" to secure.server,
            "securePort" to secure.port,
        )
    }

    private fun readProxy(command: String): ProxySettings {
        val output = run(command, service)
        fun field(name: String) = output.lineSequence()
            .firstOrNull { it.startsWith("$name:") }
            ?.substringAfter(':')
            ?.trim()
        return ProxySettings(
            enabled = field("Enabled") == "Yes",
            server = field("Server")?.takeIf(String::isNotBlank),
            port = field("Port")?.takeIf { it.isNotBlank() && it != "0" },
        )
    }

    private fun restoreProxy(targetService: String, kind: String, previous: Map<String, String?>) {
        val server = previous["${kind}Server"]
        val port = previous["${kind}Port"]
        if (server != null && port != null) {
            val option = if (kind == "web") "-setwebproxy" else "-setsecurewebproxy"
            run(option, targetService, server, port)
        }
        val stateOption = if (kind == "web") "-setwebproxystate" else "-setsecurewebproxystate"
        val state = if (previous["${kind}Enabled"] == "Yes") "on" else "off"
        run(stateOption, targetService, state)
    }

    private fun run(vararg arguments: String): String {
        val result = runner.run(listOf(executable, *arguments))
        check(result.exitCode == 0) {
            result.stderr.ifBlank { result.stdout.ifBlank { "${arguments.first()} failed" } }
        }
        return result.stdout
    }

    private data class ProxySettings(
        val enabled: Boolean,
        val server: String?,
        val port: String?,
    )
}
