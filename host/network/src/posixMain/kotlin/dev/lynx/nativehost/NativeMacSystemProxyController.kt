package dev.lynx.nativehost

/** Applies the macOS system proxy used by iOS Simulator traffic and persists an exact rollback. */
class NativeMacSystemProxyController(
    private val runner: NativeProcessRunner = PosixProcessRunner(),
    private val service: String = "Wi-Fi",
    private val executable: String = "/usr/sbin/networksetup",
) {
    fun apply(port: Int): Map<String, String?> {
        require(port in 1..65535) { "proxy port must be 1..65535" }
        val previous = inspectLegacy()
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

    fun inspect(): NativeMacProxySnapshot {
        val web = readProxy("-getwebproxy")
        val secure = readProxy("-getsecurewebproxy")
        if (web.authenticated || secure.authenticated) {
            error("authenticated macOS proxies cannot be safely leased without credentials")
        }
        return NativeMacProxySnapshot(service, web.setting(), secure.setting())
    }

    fun applyWeb(lease: NativeMacProxyLease) {
        applyProxy("web", lease.webInstalled)
    }

    fun applySecure(lease: NativeMacProxyLease) {
        applyProxy("secure", lease.secureInstalled)
    }

    fun restoreWebIfOwned(lease: NativeMacProxyLease) {
        restoreIfOwned("web", lease.webInstalled, lease.webOriginal, lease.phase)
    }

    fun restoreSecureIfOwned(lease: NativeMacProxyLease) {
        restoreIfOwned("secure", lease.secureInstalled, lease.secureOriginal, lease.phase)
    }

    fun restore(previous: Map<String, String?>?) {
        if (previous == null) return
        val targetService = previous["service"] ?: service
        restoreProxy(targetService, "web", previous)
        restoreProxy(targetService, "secure", previous)
    }

    private fun inspectLegacy(): Map<String, String?> {
        val snapshot = inspect()
        val web = snapshot.web
        val secure = snapshot.secure
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
            authenticated = field("Authenticated Proxy Enabled") == "Yes",
        )
    }

    private fun applyProxy(kind: String, setting: NativeMacProxySetting) {
        val option = if (kind == "web") "-setwebproxy" else "-setsecurewebproxy"
        val stateOption = if (kind == "web") "-setwebproxystate" else "-setsecurewebproxystate"
        val server = setting.server ?: error("macOS $kind proxy server is required")
        val port = setting.port ?: error("macOS $kind proxy port is required")
        run(option, service, server, port)
        run(stateOption, service, if (setting.enabled) "on" else "off")
    }

    private fun restoreIfOwned(
        kind: String,
        installed: NativeMacProxySetting,
        original: NativeMacProxySetting,
        phase: NativeMacProxyLeasePhase,
    ) {
        val current = readProxy(if (kind == "web") "-getwebproxy" else "-getsecurewebproxy").setting()
        if (current == original) return
        val partiallyInstalledDuringApply = phase == NativeMacProxyLeasePhase.APPLYING &&
            current.server == installed.server &&
            current.port == installed.port
        if (current != installed && !partiallyInstalledDuringApply) {
            error("PROXY_OWNERSHIP_CONFLICT: $kind proxy no longer points at Lynx-owned ${installed.server}:${installed.port}")
        }
        restoreProxy(service, kind, original)
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

    private fun restoreProxy(targetService: String, kind: String, previous: NativeMacProxySetting) {
        if (previous.server != null && previous.port != null) {
            val option = if (kind == "web") "-setwebproxy" else "-setsecurewebproxy"
            run(option, targetService, previous.server, previous.port)
        }
        val stateOption = if (kind == "web") "-setwebproxystate" else "-setsecurewebproxystate"
        run(stateOption, targetService, if (previous.enabled) "on" else "off")
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
        val authenticated: Boolean,
    ) {
        fun setting() = NativeMacProxySetting(enabled = enabled, server = server, port = port, authenticated = authenticated)
    }
}

data class NativeMacProxySnapshot(
    val service: String,
    val web: NativeMacProxySetting,
    val secure: NativeMacProxySetting,
)
