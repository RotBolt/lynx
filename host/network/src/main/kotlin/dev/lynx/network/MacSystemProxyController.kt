package dev.lynx.network

import dev.lynx.adb.CommandRunner
import dev.lynx.adb.ProcessCommandRunner
import java.util.concurrent.atomic.AtomicBoolean

data class MacProxyState(val webEnabled: Boolean, val webServer: String?, val webPort: String?, val secureEnabled: Boolean, val secureServer: String?, val securePort: String?)

/** Controls the macOS system proxy used by iOS Simulator networking. */
class MacSystemProxyController(
    private val service: String = "Wi-Fi",
    private val runner: CommandRunner = ProcessCommandRunner(),
    private val executable: String = "/usr/sbin/networksetup",
) {
    fun inspect(): MacProxyState {
        val web = proxy("-getwebproxy")
        val secure = proxy("-getsecurewebproxy")
        return MacProxyState(web.first, web.second, web.third, secure.first, secure.second, secure.third)
    }

    fun apply(endpoint: String): MacProxyLease {
        val prior = inspect()
        val host = endpoint.substringBeforeLast(':')
        val port = endpoint.substringAfterLast(':')
        run("-setwebproxy", service, host, port)
        run("-setsecurewebproxy", service, host, port)
        run("-setwebproxystate", service, "on")
        run("-setsecurewebproxystate", service, "on")
        return MacProxyLease(prior) { restore(prior) }
    }

    fun restore(state: MacProxyState) {
        setState("web", state.webEnabled, state.webServer, state.webPort)
        setState("secure", state.secureEnabled, state.secureServer, state.securePort)
    }

    private fun setState(kind: String, enabled: Boolean, server: String?, port: String?) {
        val set = if (kind == "web") "-setwebproxy" else "-setsecurewebproxy"
        val toggle = if (kind == "web") "-setwebproxystate" else "-setsecurewebproxystate"
        if (server != null && port != null) run(set, service, server, port)
        run(toggle, service, if (enabled) "on" else "off")
    }

    private fun proxy(command: String): Triple<Boolean, String?, String?> {
        val out = run(command, service, check = false)
        val enabled = out.stdout.lineSequence().firstOrNull { it.startsWith("Enabled:") }?.substringAfter(':')?.trim() == "Yes"
        val server = out.stdout.lineSequence().firstOrNull { it.startsWith("Server:") }?.substringAfter(':')?.trim()?.takeIf { it.isNotEmpty() }
        val port = out.stdout.lineSequence().firstOrNull { it.startsWith("Port:") }?.substringAfter(':')?.trim()?.takeIf { it.isNotEmpty() && it != "0" }
        return Triple(enabled, server, port)
    }

    private fun run(vararg args: String, check: Boolean = true) = runner.run(listOf(executable, *args)).also {
        if (check && it.exitCode != 0) throw ProxyControllerException("MAC_PROXY_FAILED", it.stderr.ifBlank { "networksetup failed" })
    }
}

class MacProxyLease internal constructor(val priorState: MacProxyState, private val restoreAction: () -> Unit) {
    private val restored = AtomicBoolean(false)
    fun restore() { if (restored.compareAndSet(false, true)) restoreAction() }
}
