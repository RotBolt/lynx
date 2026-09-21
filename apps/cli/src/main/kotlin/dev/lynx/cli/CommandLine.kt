package dev.lynx.cli

enum class CliCommand {
    VERSION,
    DOCTOR,
    DEVICES,
    DAEMON,
    HELP,
}

sealed interface CliInvocation {
    data class Json(val delegate: CliInvocation) : CliInvocation
    data class Raw(val command: String, val arguments: Map<String, String> = emptyMap()) : CliInvocation
    data class Attach(val deviceSerial: String?, val packageName: String) : CliInvocation
    data object Status : CliInvocation
    data object Detach : CliInvocation
    data object NetworkStart : CliInvocation
    data object NetworkStop : CliInvocation
    data object NetworkList : CliInvocation
    data object NetworkCaShow : CliInvocation
    data object NetworkCaInstall : CliInvocation
    data class NetworkCaInstallTarget(val platform: String, val target: String) : CliInvocation
    data object NetworkCaRemove : CliInvocation
    data object DbList : CliInvocation
    data class DbSnapshot(val database: String) : CliInvocation
}

object CommandLine {
    fun parse(args: Array<String>): CliCommand = when (args.firstOrNull()) {
        null, "--help", "-h" -> CliCommand.HELP
        "--version" -> CliCommand.VERSION
        "doctor" -> CliCommand.DOCTOR
        "devices" -> CliCommand.DEVICES
        "daemon" -> CliCommand.DAEMON
        else -> throw IllegalArgumentException("Unknown command: ${args.first()}")
    }

    fun parseInvocation(args: Array<String>): CliInvocation {
        val json = args.contains("--json") || args.contains("--jsonl")
        val filtered = args.filter { it != "--json" && it != "--jsonl" }.toTypedArray()
        val invocation = when (filtered.firstOrNull()) {
        "status" -> requireSize(filtered, 1, CliInvocation.Status)
        "detach" -> requireSize(filtered, 1, CliInvocation.Detach)
        "network" -> parseNetwork(filtered)
        "db" -> parseDb(filtered)
        "attach" -> parseAttach(filtered)
        else -> throw IllegalArgumentException("Expected attach, status, or detach")
        }
        return if (json) CliInvocation.Json(invocation) else invocation
    }

    private fun parseNetwork(args: Array<String>): CliInvocation = when {
        args.contentEquals(arrayOf("network", "start")) -> CliInvocation.NetworkStart
        args.contentEquals(arrayOf("network", "stop")) -> CliInvocation.NetworkStop
        args.contentEquals(arrayOf("network", "list")) -> CliInvocation.NetworkList
        args.contentEquals(arrayOf("network", "ca", "show")) -> CliInvocation.NetworkCaShow
        args.contentEquals(arrayOf("network", "ca", "install")) -> CliInvocation.NetworkCaInstall
        args.size == 5 && args[1] == "ca" && args[2] == "install" && args[3] in setOf("--android", "--ios-simulator") -> CliInvocation.NetworkCaInstallTarget(args[3].removePrefix("--"), args[4])
        args.contentEquals(arrayOf("network", "ca", "remove")) -> CliInvocation.NetworkCaRemove
        args.size == 3 && args[1] == "get" -> CliInvocation.Raw("NETWORK_GET", mapOf("request_id" to args[2]))
        args.contentEquals(arrayOf("network", "watch")) -> CliInvocation.Raw("NETWORK_WATCH")
        args.contentEquals(arrayOf("network", "doctor")) -> CliInvocation.Raw("NETWORK_DOCTOR")
        else -> throw IllegalArgumentException("Usage: lynx network start|stop|list")
    }

    private fun parseDb(args: Array<String>): CliInvocation = when {
        args.contentEquals(arrayOf("db", "list")) -> CliInvocation.DbList
        args.size == 3 && args[1] == "snapshot" -> CliInvocation.DbSnapshot(args[2])
        args.size == 4 && args[1] == "tables" && args[2] == "--snapshot" -> CliInvocation.Raw("DB_TABLES", mapOf("snapshot_id" to args[3]))
        args.size == 4 && args[1] == "schema" && args[2] == "--snapshot" -> CliInvocation.Raw("DB_SCHEMA", mapOf("snapshot_id" to args[3]))
        args.size >= 5 && args[1] == "query" && args[2] == "--snapshot" -> CliInvocation.Raw("DB_QUERY", mapOf("snapshot_id" to args[3], "sql" to args.drop(4).joinToString(" ")))
        else -> throw IllegalArgumentException("Usage: lynx db list|snapshot <database>")
    }

    private fun parseAttach(args: Array<String>): CliInvocation {
        if (args.size !in 3..5) {
            throw IllegalArgumentException("Usage: lynx attach [--device <serial>] --package <package>")
        }
        var device: String? = null
        var packageName: String? = null
        var index = 1
        while (index < args.size) {
            when (args[index]) {
                "--device" -> {
                    device = args.getOrNull(index + 1) ?: throw IllegalArgumentException("--device requires a serial")
                    index += 2
                }
                "--package" -> {
                    packageName = args.getOrNull(index + 1) ?: throw IllegalArgumentException("--package requires a name")
                    index += 2
                }
                else -> throw IllegalArgumentException("Usage: lynx attach [--device <serial>] --package <package>")
            }
        }
        return CliInvocation.Attach(device, packageName ?: throw IllegalArgumentException("--package is required"))
    }

    private fun <T : CliInvocation> requireSize(args: Array<String>, size: Int, invocation: T): T {
        if (args.size != size) throw IllegalArgumentException("Unexpected arguments for ${args[0]}")
        return invocation
    }
}
