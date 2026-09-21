package dev.lynx.nativecli

import dev.lynx.nativehost.NativeCommandResult
import dev.lynx.nativehost.NativeProcessRunner
import dev.lynx.model.DatabaseId

expect fun nativeProcessRunner(): NativeProcessRunner

fun main(args: Array<String>) {
    NativeCli(nativeProcessRunner()).run(args.toList())
}

class NativeCli(private val runner: NativeProcessRunner) {
    fun run(args: List<String>) {
        when (args.firstOrNull()) {
            "--version", "version" -> println("lynx-native 0.1.0-SNAPSHOT")
            "devices" -> printResult(runner.run(listOf("adb", "devices")))
            "db" -> runDatabase(args.drop(1))
            else -> println("Usage: lynx [--version|devices|db list|db snapshot|db tables|db query]")
        }
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
