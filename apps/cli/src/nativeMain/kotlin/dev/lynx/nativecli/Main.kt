package dev.lynx.nativecli

import dev.lynx.nativehost.NativeCommandResult
import dev.lynx.nativehost.NativeProcessRunner
import dev.lynx.network.NativeNetworkBackend
import dev.lynx.network.nativeNetworkBackend
import dev.lynx.model.DatabaseId
import dev.lynx.model.NetworkCommand
import kotlinx.serialization.json.*

expect fun nativeProcessRunner(): NativeProcessRunner
expect fun nativeHostToolResolver(): dev.lynx.nativehost.HostToolResolver
expect fun nativeSessionStore(): dev.lynx.nativehost.NativeSessionStore
expect fun nativeCertificateManager(): dev.lynx.nativehost.NativeCertificateManager

fun main(args: Array<String>) {
    val runner = nativeProcessRunner()
    val tools = nativeHostToolResolver()
    NativeCli(
        runner, nativeNetworkBackend(), tools,
        inventory = {
            dev.lynx.nativehost.DeviceInventoryService(
                listOf(dev.lynx.nativehost.AndroidDeviceProvider(runner), dev.lynx.nativehost.IosSimulatorDeviceProvider(runner, tools)),
                tools, nativeSessionStore().load(),
            ).discover()
        },
    ).run(args.toList())
}

class NativeCli(
    private val runner: NativeProcessRunner,
    private val network: NativeNetworkBackend,
    private val tools: dev.lynx.nativehost.HostToolResolver = dev.lynx.nativehost.HostToolResolver { dev.lynx.nativehost.ResolvedTool(it, dev.lynx.nativehost.ToolStatus.MISSING, null, null, "tool resolver unavailable") },
    private val inventory: () -> dev.lynx.nativehost.DeviceInventory = { dev.lynx.nativehost.DeviceInventory(emptyList(), emptyList(), emptyList()) },
) {
    private val json = Json { encodeDefaults = true; prettyPrint = false }
    private val sessions = dev.lynx.nativehost.NativeSessionManager(runner, nativeSessionStore()) { "session_${kotlin.time.Clock.System.now().toEpochMilliseconds()}" }

    fun run(args: List<String>) {
        when (args.firstOrNull()) {
            "--version", "version" -> println("lynx 0.1.0-SNAPSHOT")
            "devices" -> println(HostDiagnosticsCommands(tools, inventory).devices("--json" in args, option(args, "--platform")))
            "doctor" -> println(HostDiagnosticsCommands(tools, inventory).doctor("--json" in args))
            "attach" -> {
                val device = args.getOrNull(1) ?: error("device is required")
                val packageName = args.getOrNull(2) ?: error("package is required")
                println(sessions.attach(device, packageName))
            }
            "status" -> println(sessions.status())
            "detach" -> {
                val cleanupError = runCatching { network.execute(NetworkCommand.Stop) }.exceptionOrNull()
                if (cleanupError != null) {
                    println("ERROR NETWORK_CLEANUP_FAILED ${cleanupError.message ?: cleanupError::class.simpleName}")
                    return
                }
                println(sessions.detach())
            }
            "db" -> runDatabase(args.drop(1))
            "network" -> runNetwork(args.drop(1))
            else -> println("Usage: lynx [--version|doctor|devices|db ...|network start|stop|list|get]")
        }
    }

    private fun runNetwork(args: List<String>) {
        if (args.firstOrNull() == "ca") {
            val manager = nativeCertificateManager()
            val state = when (args.getOrNull(1)) {
                "show" -> manager.show()
                "install" -> manager.install()
                "remove" -> manager.remove()
                else -> error("Usage: lynx network ca [show|install|remove]")
            }
            println(json.encodeToString(state))
            return
        }
        runCatching {
            val command = when (args.firstOrNull()) {
                "worker" -> { networkWorker(option(args, "--port")?.toIntOrNull() ?: args.getOrNull(1)?.toIntOrNull() ?: error("port is required")); return }
                "supervisor" -> { networkSupervisor(option(args, "--port")?.toIntOrNull() ?: args.getOrNull(1)?.toIntOrNull() ?: error("port is required")); return }
                else -> NetworkInspectionCommands.parse(args)
            }
            network.execute(command)
        }
            .onSuccess { result -> println(networkEnvelope(normalizeNetworkJson(json.encodeToJsonElement(result)))) }
            .onFailure { error -> println(networkError(error)) }
    }

    private fun networkEnvelope(element: JsonElement): JsonObject = buildJsonObject {
        put("protocol_version", 1)
        put("schema_version", "lynx.v2")
        element.jsonObject.forEach { (key, value) -> put(key, value) }
    }

    private fun networkError(error: Throwable): String {
        val message = error.message ?: error::class.simpleName.orEmpty()
        val code = when {
            message.startsWith("NO_ACTIVE_CAPTURE") -> "NO_ACTIVE_CAPTURE"
            message.startsWith("CAPTURE_SESSION_NOT_FOUND") -> "CAPTURE_SESSION_NOT_FOUND"
            message.startsWith("SESSION_REQUIRED") -> "SESSION_REQUIRED"
            else -> "NETWORK_ERROR"
        }
        return json.encodeToString(buildJsonObject {
            put("protocol_version", 1)
            put("schema_version", "lynx.v2")
            put("type", "error")
            put("code", code)
            put("message", message)
            put("operation", "NETWORK")
            put("retryable", code == "NO_ACTIVE_CAPTURE")
        })
    }

    private fun networkWorker(port: Int) {
        network.worker(port)
    }

    private fun networkSupervisor(port: Int) {
        network.supervisor(port)
    }

    private fun normalizeNetworkJson(element: JsonElement): JsonElement = when (element) {
        is JsonArray -> JsonArray(element.map(::normalizeNetworkJson))
        is JsonObject -> {
            val flattened = element.mapValues { (key, value) ->
                if (key in setOf("id", "sessionId", "requestId", "snapshotId", "databaseId") && value is JsonObject && value.size == 1 && value["value"] is JsonPrimitive) {
                    value["value"]!!
                } else normalizeNetworkJson(value)
            }
            JsonObject(flattened)
        }
        else -> element
    }

    private fun runDatabase(args: List<String>) {
        val command = args.firstOrNull()
        when (command) {
            "list" -> {
                val platform = option(args, "--platform") ?: error("--platform android|ios is required")
                val target = databaseTarget(args, platform)
                val invocation = when (platform.lowercase()) {
                    "android" -> listOf("adb", "-s", target.first, "shell", "run-as", target.second, "find", "databases", "-type", "f")
                    "ios" -> return listIosDatabases(target)
                    else -> error("unsupported platform '$platform'; expected android or ios")
                }
                printResult(runner.run(invocation))
            }
            "snapshot" -> {
                val database = args.getOrNull(1)?.let(::DatabaseId) ?: error("database path is required")
                val platform = option(args, "--platform") ?: error("--platform android|ios is required")
                val target = databaseTarget(args, platform)
                requireSafeRelativePath(database.value)
                val path = "/tmp/lynx-native-${safeName(database.value)}.db"
                val result = when (platform.lowercase()) {
                    "android" -> runner.runToFile(
                        listOf("adb", "-s", target.first, "exec-out", "run-as", target.second, "cat", database.value),
                        path,
                    )
                    "ios" -> {
                        val root = iosContainer(target) ?: return
                        runner.run(listOf("cp", "$root/${database.value}", path))
                    }
                    else -> error("unsupported platform '$platform'; expected android or ios")
                }
                if (result.exitCode == 0) println("OK DB_SNAPSHOT path=$path database=${database.value}") else printResult(result)
            }
            "tables" -> {
                val snapshot = args.getOrNull(1) ?: error("snapshot path is required")
                printResult(runner.run(listOf("sqlite3", "-readonly", "-json", snapshot, "select name, type from sqlite_master where type in ('table','view') order by name;")))
            }
            "query" -> {
                val snapshot = args.getOrNull(1) ?: error("snapshot path is required")
                val sql = args.drop(2).joinToString(" ").ifBlank { error("SQL is required") }
                printResult(runner.run(listOf("sqlite3", "-readonly", "-json", snapshot, sql)))
            }
            else -> error("Usage: lynx db [list|snapshot|tables|query] ...")
        }
    }

    private fun listIosDatabases(target: Pair<String, String>) {
        val root = iosContainer(target) ?: return
        val result = runner.run(listOf("find", root, "-name", "*.db", "-type", "f"))
        if (result.exitCode != 0) return printResult(result)
        val prefix = "$root/"
        val relative = result.stdout.lineSequence().joinToString("\n") { it.removePrefix(prefix) }
        printResult(result.copy(stdout = if (relative.isBlank()) relative else "$relative\n"))
    }

    private fun iosContainer(target: Pair<String, String>): String? {
        val result = runner.run(listOf("xcrun", "simctl", "get_app_container", target.first, target.second, "data"))
        if (result.exitCode != 0) {
            printResult(result)
            return null
        }
        return result.stdout.trim().takeIf { it.isNotEmpty() }
    }

    /** Returns the platform target ID and app identifier for DB discovery and snapshots. */
    private fun databaseTarget(args: List<String>, platform: String): Pair<String, String> = when (platform.lowercase()) {
        "android" -> (option(args, "--device") ?: error("Android requires --device <adb-serial>")) to
            (option(args, "--package") ?: error("Android requires --package <application-id>"))
        "ios" -> (option(args, "--simulator") ?: option(args, "--device")
            ?: error("iOS requires --simulator <simulator-udid>")) to
            (option(args, "--bundle-id") ?: option(args, "--package")
                ?: error("iOS requires --bundle-id <bundle-id>"))
        else -> error("unsupported platform '$platform'; expected android or ios")
    }

    private fun option(args: List<String>, name: String): String? {
        args.firstOrNull { it.startsWith("$name=") }?.substringAfter('=')?.takeIf(String::isNotEmpty)?.let { return it }
        return args.windowed(2, 1).firstOrNull { it[0] == name }?.getOrNull(1)?.takeUnless { it.startsWith("--") }
    }
    private fun safeName(value: String) = value.replace(Regex("[^A-Za-z0-9_.-]"), "_")
    private fun requireSafeRelativePath(value: String) {
        require(!value.startsWith('/') && value.split('/').none { it == ".." || it.isBlank() }) {
            "database path must be a safe app-relative path"
        }
    }
    private fun printResult(result: NativeCommandResult) {
        if (result.stdout.isNotBlank()) print(result.stdout)
        if (result.exitCode != 0 && result.stderr.isNotBlank()) print(result.stderr)
    }
}
