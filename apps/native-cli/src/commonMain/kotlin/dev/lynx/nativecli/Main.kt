package dev.lynx.nativecli

import dev.lynx.nativehost.NativeCommandResult
import dev.lynx.nativehost.NativeProcessRunner
import dev.lynx.model.DatabaseId
import dev.lynx.model.NetworkCommand
import dev.lynx.model.NetworkCaptureSettings
import dev.lynx.model.NetworkFilter
import dev.lynx.model.RequestId
import dev.lynx.nativehost.NativeNetworkInspector
import kotlinx.serialization.json.*

expect fun nativeProcessRunner(): NativeProcessRunner
expect fun nativeSessionStore(): dev.lynx.nativehost.NativeSessionStore
expect fun nativeNetworkInspector(): NativeNetworkInspector
expect fun nativeNetworkWorker(port: Int)

fun main(args: Array<String>) {
    NativeCli(nativeProcessRunner(), nativeNetworkInspector()).run(args.toList())
}

class NativeCli(private val runner: NativeProcessRunner, private val network: NativeNetworkInspector) {
    private val json = Json { encodeDefaults = true; prettyPrint = false }
    private val sessions = dev.lynx.nativehost.NativeSessionManager(runner, nativeSessionStore()) { "session_${kotlin.time.Clock.System.now().toEpochMilliseconds()}" }

    fun run(args: List<String>) {
        when (args.firstOrNull()) {
            "--version", "version" -> println("lynx 0.1.0-SNAPSHOT")
            "devices" -> printResult(runner.run(listOf("adb", "devices")))
            "attach" -> {
                val device = args.getOrNull(1) ?: error("device is required")
                val packageName = args.getOrNull(2) ?: error("package is required")
                println(sessions.attach(device, packageName))
            }
            "status" -> println(sessions.status())
            "detach" -> println(sessions.detach())
            "db" -> runDatabase(args.drop(1))
            "network" -> runNetwork(args.drop(1))
            else -> println("Usage: lynx [--version|devices|db ...|network start|stop|list|get|doctor]")
        }
    }

    private fun runNetwork(args: List<String>) {
        val command = when (args.firstOrNull()) {
            "start" -> NetworkCommand.Start(NetworkCaptureSettings(listenHost = option(args, "--host") ?: "0.0.0.0", listenPort = option(args, "--port")?.toIntOrNull() ?: 0))
            "stop" -> NetworkCommand.Stop
            "list" -> NetworkCommand.List(NetworkFilter(method = option(args, "--method"), status = option(args, "--status")?.toIntOrNull(), urlSubstring = option(args, "--url"), limit = option(args, "--limit")?.toIntOrNull()))
            "get" -> NetworkCommand.Get(RequestId(args.getOrNull(1) ?: error("request id is required")))
            "doctor" -> NetworkCommand.Doctor
            "worker" -> { networkWorker(option(args, "--port")?.toIntOrNull() ?: args.getOrNull(1)?.toIntOrNull() ?: error("port is required")); return }
            else -> error("Usage: lynx network [start|stop|list|get|doctor]")
        }
        println(json.encodeToString(normalizeNetworkJson(json.encodeToJsonElement(network.execute(command)))))
    }

    private fun networkWorker(port: Int) {
        nativeNetworkWorker(port)
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
        val packageName = option(args, "--package") ?: error("--package is required")
        val device = option(args, "--device")
        val prefix = buildList {
            add("adb")
            if (device != null) addAll(listOf("-s", device))
            addAll(listOf("shell", "run-as", packageName))
        }
        when (command) {
            "list" -> printResult(runner.run(prefix + listOf("find", "databases", "-type", "f")))
            "snapshot" -> {
                val database = args.getOrNull(1)?.let(::DatabaseId) ?: error("database path is required")
                val path = "/tmp/lynx-native-${safeName(database.value)}.db"
                val shell = (prefix + listOf("cat", database.value)).joinToString(" ") { quote(it) }
                val result = runner.run(listOf("sh", "-c", "$shell > ${quote(path)}"))
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
            "ios-list" -> {
                val udid = option(args, "--udid") ?: error("--udid is required")
                val root = option(args, "--container") ?: "data"
                printResult(runner.run(listOf("sh", "-c", "root=\$(xcrun simctl get_app_container ${quote(udid)} ${quote(packageName)} ${quote(root)}); find \"\$root\" -name '*.db' -type f")))
            }
            "ios-snapshot" -> {
                val udid = option(args, "--udid") ?: error("--udid is required")
                val relative = args.getOrNull(1) ?: error("database path is required")
                val path = "/tmp/lynx-native-ios-${safeName(relative)}.db"
                val root = "\$(xcrun simctl get_app_container ${quote(udid)} ${quote(packageName)} data)"
                val commandLine = "cp \"$root/$relative\" ${quote(path)}"
                val result = runner.run(listOf("sh", "-c", commandLine))
                if (result.exitCode == 0) println("OK DB_SNAPSHOT path=$path database=$relative") else printResult(result)
            }
            else -> error("Usage: lynx db [list|snapshot|tables|query] ...")
        }
    }

    private fun option(args: List<String>, name: String): String? = args.windowed(2, 1).firstOrNull { it[0] == name }?.getOrNull(1)
    private fun safeName(value: String) = value.replace(Regex("[^A-Za-z0-9_.-]"), "_")
    private fun quote(value: String) = "'" + value.replace("'", "'\\''") + "'"
    private fun printResult(result: NativeCommandResult) {
        if (result.stdout.isNotBlank()) print(result.stdout)
        if (result.exitCode != 0 && result.stderr.isNotBlank()) print(result.stderr)
    }
}
