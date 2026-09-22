package dev.lynx.nativehost

/** Applies the macOS system proxy used by iOS Simulator traffic and persists an exact rollback. */
class NativeMacSystemProxyController(
    private val runner: NativeProcessRunner = PosixProcessRunner(),
    private val service: String? = "Wi-Fi",
    private val executable: String = "/usr/sbin/networksetup",
) {
    fun apply(port: Int): Map<String, String?> {
        require(port in 1..65535) { "proxy port must be 1..65535" }
        val previous = inspectLegacy()
        val targetService = previous["service"] ?: actualService()
        try {
            run("-setwebproxy", targetService, "127.0.0.1", port.toString())
            run("-setsecurewebproxy", targetService, "127.0.0.1", port.toString())
            run("-setwebproxystate", targetService, "on")
            run("-setsecurewebproxystate", targetService, "on")
        } catch (error: Throwable) {
            runCatching { restore(previous) }
            throw error
        }
        return previous
    }

    fun inspect(): NativeMacProxySnapshot {
        val targetService = actualService()
        val web = readProxy("-getwebproxy", targetService)
        val secure = readProxy("-getsecurewebproxy", targetService)
        val pac = readPac(targetService)
        val autodiscovery = readAutodiscovery(targetService)
        val bypass = readBypassDomains(targetService)
        if (web.authenticated || secure.authenticated) {
            error("authenticated macOS proxies cannot be safely leased without credentials")
        }
        return NativeMacProxySnapshot(targetService, web.setting(), secure.setting(), pac.url, autodiscovery, bypass)
    }

    fun applyWeb(lease: NativeMacProxyLease) {
        applyProxy("web", lease.service, lease.webInstalled)
    }

    fun applySecure(lease: NativeMacProxyLease) {
        applyProxy("secure", lease.service, lease.secureInstalled)
    }

    fun restoreWebIfOwned(lease: NativeMacProxyLease) {
        restoreIfOwned("web", lease.service, lease.webInstalled, lease.webOriginal, lease.phase)
    }

    fun restoreSecureIfOwned(lease: NativeMacProxyLease) {
        restoreIfOwned("secure", lease.service, lease.secureInstalled, lease.secureOriginal, lease.phase)
    }

    fun verifyAuxiliarySettingsUnchanged(lease: NativeMacProxyLease) {
        val pac = readPac(lease.service)
        val autodiscovery = readAutodiscovery(lease.service)
        val bypass = readBypassDomains(lease.service)
        if (pac.url != lease.pacUrl || autodiscovery != lease.autodiscoveryEnabled || bypass != lease.bypassDomains) {
            error("PROXY_OWNERSHIP_CONFLICT: PAC, autodiscovery, or bypass domains changed during capture")
        }
    }

    fun restore(previous: Map<String, String?>?) {
        if (previous == null) return
        val targetService = previous["service"] ?: actualService()
        restoreProxy(targetService, "web", previous)
        restoreProxy(targetService, "secure", previous)
    }

    private fun inspectLegacy(): Map<String, String?> {
        val targetService = actualService()
        val web = readProxy("-getwebproxy", targetService).setting()
        val secure = readProxy("-getsecurewebproxy", targetService).setting()
        return mapOf(
            "controller" to "macos",
            "service" to targetService,
            "webEnabled" to if (web.enabled) "Yes" else "No",
            "webServer" to web.server,
            "webPort" to web.port,
            "secureEnabled" to if (secure.enabled) "Yes" else "No",
            "secureServer" to secure.server,
            "securePort" to secure.port,
        )
    }

    private fun readProxy(command: String, targetService: String): ProxySettings {
        val output = run(command, targetService)
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

    private fun readPac(targetService: String): PacSettings {
        val output = run("-getautoproxyurl", targetService)
        fun field(name: String) = output.lineSequence()
            .firstOrNull { it.startsWith("$name:") }
            ?.substringAfter(':')
            ?.trim()
        return PacSettings(
            enabled = field("Enabled") == "Yes",
            url = field("URL")?.takeIf(String::isNotBlank),
        )
    }

    private fun readAutodiscovery(targetService: String): Boolean {
        val output = run("-getproxyautodiscovery", targetService)
        return output.lineSequence()
            .firstOrNull { it.startsWith("Enabled:") }
            ?.substringAfter(':')
            ?.trim() == "Yes"
    }

    private fun readBypassDomains(targetService: String): List<String> {
        val output = run("-getproxybypassdomains", targetService)
        return output.lineSequence().map(String::trim).filter(String::isNotBlank).toList()
    }

    private fun applyProxy(kind: String, targetService: String, setting: NativeMacProxySetting) {
        val option = if (kind == "web") "-setwebproxy" else "-setsecurewebproxy"
        val stateOption = if (kind == "web") "-setwebproxystate" else "-setsecurewebproxystate"
        val server = setting.server ?: error("macOS $kind proxy server is required")
        val port = setting.port ?: error("macOS $kind proxy port is required")
        run(option, targetService, server, port)
        run(stateOption, targetService, if (setting.enabled) "on" else "off")
    }

    private fun restoreIfOwned(
        kind: String,
        targetService: String,
        installed: NativeMacProxySetting,
        original: NativeMacProxySetting,
        phase: NativeMacProxyLeasePhase,
    ) {
        val current = readProxy(if (kind == "web") "-getwebproxy" else "-getsecurewebproxy", targetService).setting()
        if (current == original) return
        val endpointMatches = current.server == installed.server && current.port == installed.port
        val partiallyAppliedOwnedEndpoint = phase == NativeMacProxyLeasePhase.APPLYING && endpointMatches
        if (current != installed && !partiallyAppliedOwnedEndpoint) {
            error("PROXY_OWNERSHIP_CONFLICT: $kind proxy no longer points at Lynx-owned ${installed.server}:${installed.port}")
        }
        restoreProxy(targetService, kind, original)
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

    private fun actualService(): String = service ?: activeRouteService() ?: firstHardwareService() ?: "Wi-Fi"

    private fun activeRouteService(): String? = runCatching {
        val activeInterface = runner.run(listOf("/usr/sbin/scutil", "--nwi"))
            .takeIf { it.exitCode == 0 }
            ?.stdout
            ?.lineSequence()
            ?.map(String::trim)
            ?.firstNotNullOfOrNull { line ->
                Regex("^([A-Za-z]+[0-9]+)\\s*:").find(line)?.groupValues?.get(1)
            }
            ?: return@runCatching null
        val order = run("-listnetworkserviceorder")
        var candidate: String? = null
        for (line in order.lineSequence()) {
            Regex("^\\([0-9]+\\)\\s+(.+)$").find(line.trim())?.let { match ->
                candidate = match.groupValues[1].trim()
            }
            if (candidate != null && line.contains("Device: $activeInterface")) return@runCatching candidate
        }
        null
    }.getOrNull()

    private fun firstHardwareService(): String? = runCatching {
        run("-listnetworkserviceorder")
            .lineSequence()
            .firstOrNull { it.contains("Hardware Port:") }
            ?.substringAfter("Hardware Port:")
            ?.substringBefore(',')
            ?.trim()
            ?.takeIf(String::isNotBlank)
    }.getOrNull()

    private data class ProxySettings(
        val enabled: Boolean,
        val server: String?,
        val port: String?,
        val authenticated: Boolean,
    ) {
        fun setting() = NativeMacProxySetting(enabled = enabled, server = server, port = port, authenticated = authenticated)
    }

    private data class PacSettings(
        val enabled: Boolean,
        val url: String?,
    )
}

data class NativeMacProxySnapshot(
    val service: String,
    val web: NativeMacProxySetting,
    val secure: NativeMacProxySetting,
    val pacUrl: String?,
    val autodiscoveryEnabled: Boolean,
    val bypassDomains: List<String>,
)
