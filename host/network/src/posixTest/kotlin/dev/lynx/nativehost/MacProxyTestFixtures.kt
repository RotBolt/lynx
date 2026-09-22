package dev.lynx.nativehost

data class ProxyFixture(
    val enabled: Boolean = false,
    val server: String? = null,
    val port: String? = null,
) {
    fun output() = buildString {
        append("Enabled: ").append(if (enabled) "Yes" else "No").append('\n')
        append("Server: ").append(server.orEmpty()).append('\n')
        append("Port: ").append(port ?: "0").append('\n')
    }

    fun setting() = NativeMacProxySetting(enabled = enabled, server = server, port = port)
}

class StatefulNetworksetupRunner(
    web: ProxyFixture = ProxyFixture(),
    secure: ProxyFixture = ProxyFixture(),
    private val failures: MutableMap<String, String> = mutableMapOf(),
) : NativeProcessRunner {
    var web: ProxyFixture = web
        private set
    var secure: ProxyFixture = secure
        private set
    val commands = mutableListOf<List<String>>()

    override fun run(command: List<String>): NativeCommandResult {
        commands += command
        val action = command.getOrNull(1).orEmpty()
        failures.remove(action)?.let { return NativeCommandResult(1, "", it) }
        return when (action) {
            "-getwebproxy" -> NativeCommandResult(0, web.output())
            "-getsecurewebproxy" -> NativeCommandResult(0, secure.output())
            "-setwebproxy" -> {
                web = web.copy(server = command[3], port = command[4])
                NativeCommandResult(0, "")
            }
            "-setsecurewebproxy" -> {
                secure = secure.copy(server = command[3], port = command[4])
                NativeCommandResult(0, "")
            }
            "-setwebproxystate" -> {
                web = web.copy(enabled = command[3] == "on")
                NativeCommandResult(0, "")
            }
            "-setsecurewebproxystate" -> {
                secure = secure.copy(enabled = command[3] == "on")
                NativeCommandResult(0, "")
            }
            else -> NativeCommandResult(0, "")
        }
    }
}

class InMemoryMacProxyLeaseStore(initial: NativeMacProxyLease? = null) : NativeMacProxyLeaseStore {
    private var lease = initial
    override fun load(): NativeMacProxyLease? = lease
    override fun save(lease: NativeMacProxyLease) {
        this.lease = lease
    }
    override fun clear() {
        lease = null
    }
}
