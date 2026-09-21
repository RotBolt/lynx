package dev.lynx.nativehost

import dev.lynx.model.DatabaseId
import kotlinx.serialization.Serializable

data class NativeCommandResult(
    val exitCode: Int,
    val stdout: String,
    val stderr: String = "",
)

@Serializable
data class NativeCertificateState(
    val configured: Boolean,
    val pemPath: String,
    val fingerprint: String? = null,
    val trustStatus: String = "user_installation_required",
    val instructions: List<String> = emptyList(),
)

/** Host CA lifecycle; platform installers remain explicit and outside the proxy core. */
interface NativeCertificateManager {
    fun show(): NativeCertificateState
    fun install(): NativeCertificateState
    fun remove(): NativeCertificateState
}

/** Resolves the current native executable for detached worker processes. */
expect fun nativeExecutablePath(): String?

interface NativeProcessRunner {
    fun run(command: List<String>): NativeCommandResult
}

interface NativeDatabaseInspector {
    fun listDatabases(packageName: String): List<DatabaseId>
    fun snapshot(packageName: String, database: DatabaseId): String
    fun tables(snapshotPath: String): String
    fun query(snapshotPath: String, sql: String): String
}
