package dev.lynx.nativehost

import kotlinx.serialization.Serializable

@Serializable
data class NativeMacProxySetting(
    val enabled: Boolean,
    val server: String? = null,
    val port: String? = null,
    val authenticated: Boolean = false,
)

@Serializable
enum class NativeMacProxyLeasePhase {
    PREPARED,
    APPLYING,
    ACTIVE,
    RESTORING,
}

@Serializable
data class NativeMacProxyLease(
    val leaseId: String,
    val service: String,
    val endpoint: String,
    val webOriginal: NativeMacProxySetting,
    val secureOriginal: NativeMacProxySetting,
    val webInstalled: NativeMacProxySetting,
    val secureInstalled: NativeMacProxySetting,
    val phase: NativeMacProxyLeasePhase = NativeMacProxyLeasePhase.PREPARED,
    val webApplied: Boolean = false,
    val secureApplied: Boolean = false,
    val webRestored: Boolean = false,
    val secureRestored: Boolean = false,
    val lastError: String? = null,
    val workerPid: Int? = null,
    val workerStartIdentity: String? = null,
    val supervisorPid: Int? = null,
    val captureToken: String? = null,
    val pacUrl: String? = null,
    val autodiscoveryEnabled: Boolean = false,
    val bypassDomains: List<String> = emptyList(),
) {
    fun previousProxyMap() = mapOf(
        "controller" to "macos",
        "service" to service,
        "webEnabled" to if (webOriginal.enabled) "Yes" else "No",
        "webServer" to webOriginal.server,
        "webPort" to webOriginal.port,
        "secureEnabled" to if (secureOriginal.enabled) "Yes" else "No",
        "secureServer" to secureOriginal.server,
        "securePort" to secureOriginal.port,
    )

    fun hasAppliedSettings(): Boolean = webApplied || secureApplied
    fun isFullyRestored(): Boolean = (!webApplied || webRestored) && (!secureApplied || secureRestored)
}
interface NativeMacProxyLeaseStore {
    fun load(): NativeMacProxyLease?
    fun save(lease: NativeMacProxyLease)
    fun clear()
}
