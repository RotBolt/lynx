package dev.lynx.nativehost

/**
 * Verifies that the worker which earned a macOS proxy lease is still the same
 * process and still owns a usable listener. A failed check restores only the
 * settings represented by the durable lease before clearing runtime state.
 */
data class NativeWorkerIdentity(
    val pid: Int,
    val startIdentity: String,
)

class NativeMacProxyWorkerSupervisor(
    private val worker: NativeWorkerIdentity,
    private val identityForPid: (Int) -> String?,
    private val listenerHealthy: () -> Boolean,
    private val restoreOwnedProxy: () -> Unit,
    private val clearRuntime: () -> Unit,
) {
    fun checkOnce(): Boolean {
        val healthy = identityForPid(worker.pid) == worker.startIdentity && listenerHealthy()
        if (healthy) return true
        restoreOwnedProxy()
        clearRuntime()
        return false
    }
}
