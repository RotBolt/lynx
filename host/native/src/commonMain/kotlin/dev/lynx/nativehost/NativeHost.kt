package dev.lynx.nativehost

import dev.lynx.model.DatabaseId
import dev.lynx.model.NetworkCommand
import dev.lynx.model.NetworkCommandResult

data class NativeCommandResult(
    val exitCode: Int,
    val stdout: String,
    val stderr: String = "",
)

interface NativeProcessRunner {
    fun run(command: List<String>): NativeCommandResult
}

interface NativeDatabaseInspector {
    fun listDatabases(packageName: String): List<DatabaseId>
    fun snapshot(packageName: String, database: DatabaseId): String
    fun tables(snapshotPath: String): String
    fun query(snapshotPath: String, sql: String): String
}

/** Platform-neutral boundary used by the native CLI and native proxy adapters. */
fun interface NativeNetworkInspector {
    fun execute(command: NetworkCommand): NetworkCommandResult
}
