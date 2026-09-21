package dev.lynx.adb

data class CommandResult(
    val exitCode: Int,
    val stdout: String,
    val stderr: String,
)

/** Result for commands whose stdout is an opaque byte stream (for example a SQLite backup). */
data class BinaryCommandResult(
    val exitCode: Int,
    val stdout: ByteArray,
    val stderr: String,
)

fun interface CommandRunner {
    fun run(arguments: List<String>): CommandResult
}

/** Optional binary-capable runner. Text-only runners remain valid for tests and legacy callers. */
interface BinaryCommandRunner : CommandRunner {
    fun runBinary(arguments: List<String>): BinaryCommandResult
}
