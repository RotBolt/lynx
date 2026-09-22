package dev.lynx.nativehost

data class NativeSession(
    val id: String,
    val deviceSerial: String,
    val packageName: String,
    val processId: Int,
)

interface NativeSessionStore {
    fun load(): NativeSession?
    fun save(session: NativeSession)
    fun clear()
}

class InMemoryNativeSessionStore : NativeSessionStore {
    private var current: NativeSession? = null
    override fun load(): NativeSession? = current
    override fun save(session: NativeSession) { current = session }
    override fun clear() { current = null }
}

class NativeSessionManager(
    private val runner: NativeProcessRunner,
    private val store: NativeSessionStore,
    private val idProvider: () -> String,
) {
    fun attach(deviceSerial: String, packageName: String): String {
        val pid = pid(deviceSerial, packageName)
        val session = NativeSession(idProvider(), deviceSerial, packageName, pid)
        store.save(session)
        return "OK ATTACHED id=${session.id} device=${session.deviceSerial} package=${session.packageName} pid=${session.processId}"
    }

    fun status(): String {
        val current = store.load() ?: return "ERROR NOT_ATTACHED"
        val refreshed = pid(current.deviceSerial, current.packageName)
        val updated = current.copy(processId = refreshed)
        store.save(updated)
        return "OK ACTIVE id=${updated.id} device=${updated.deviceSerial} package=${updated.packageName} pid=${updated.processId}"
    }

    fun detach(): String {
        if (store.load() == null) return "ERROR NOT_ATTACHED"
        store.clear()
        return "OK DETACHED"
    }

    fun session(): NativeSession? = store.load()

    private fun pid(deviceSerial: String, packageName: String): Int {
        if (deviceSerial.startsWith("ios-simulator:")) {
            val udid = deviceSerial.removePrefix("ios-simulator:")
            require(udid.isNotBlank()) { "iOS Simulator UDID is required" }
            val result = runner.run(listOf("xcrun", "simctl", "launch", udid, packageName))
            require(result.exitCode == 0) { result.stderr.ifBlank { result.stdout.ifBlank { "unable to launch $packageName on simulator $udid" } } }
            return result.stdout.trim().substringAfterLast(':').trim().toIntOrNull()
                ?: error("simctl did not return a process ID for $packageName on simulator $udid")
        }

        val result = runner.run(listOf("adb", "-s", deviceSerial, "shell", "pidof", packageName))
        require(result.exitCode == 0) { result.stderr.ifBlank { "unable to resolve process for $packageName" } }
        return result.stdout.trim().split(Regex("\\s+")).firstOrNull()?.toIntOrNull()
            ?: error("no running process found for $packageName")
    }
}
