package dev.lynx.nativehost

import kotlinx.cinterop.ByteVar
import kotlinx.cinterop.CPointer
import kotlinx.cinterop.allocArray
import kotlinx.cinterop.memScoped
import kotlinx.cinterop.toKString
import platform.posix.FILE
import platform.posix.fclose
import platform.posix.fgets
import platform.posix.fopen
import platform.posix.getenv
import platform.posix.getpid
import platform.posix.pclose
import platform.posix.popen
import platform.posix.remove

/** Minimal process adapter for macOS/Linux native binaries. */
@OptIn(kotlinx.cinterop.ExperimentalForeignApi::class)
class PosixProcessRunner(private val tools: HostToolResolver = PosixHostToolResolver()) : NativeProcessRunner {
    override fun run(command: List<String>): NativeCommandResult = runPosixCommand(normalize(command))

    override fun runToFile(command: List<String>, outputPath: String): NativeCommandResult =
        runPosixCommand(normalize(command), outputPath)

    private fun normalize(command: List<String>): List<String> {
        require(command.isNotEmpty()) { "command must not be empty" }
        val tool = when (command.first()) {
            "adb" -> HostTool.ADB
            "xcrun" -> HostTool.XCRUN
            "simctl" -> HostTool.SIMCTL
            "sqlite3" -> HostTool.SQLITE3
            "openssl" -> HostTool.OPENSSL
            else -> null
        }
        val path = tool?.let(tools::resolve)?.takeIf { it.status == ToolStatus.AVAILABLE }?.path
        return if (path == null) command else listOf(path) + command.drop(1)
    }
}

@OptIn(kotlinx.cinterop.ExperimentalForeignApi::class)
internal fun runPosixCommand(command: List<String>, stdoutPath: String? = null): NativeCommandResult = memScoped {
    require(command.isNotEmpty()) { "command must not be empty" }
    val shell = command.joinToString(" ") { shellQuote(it) }
    val stderrPath = "/tmp/lynx-native-stderr-${getpid()}-${nextProcessNonce()}"
    val redirection = stdoutPath?.let { " > ${shellQuote(it)}" }.orEmpty()
    val stream: CPointer<FILE> = popen("$shell$redirection 2> ${shellQuote(stderrPath)}", "r")
        ?: return@memScoped NativeCommandResult(127, "", "unable to start ${command.first()}")
    val buffer = allocArray<ByteVar>(4096)
    val output = buildString {
        while (fgets(buffer, 4096, stream) != null) append(buffer.toKString())
    }
    val status = pclose(stream)
    val stderr = readPosixTextFile(stderrPath)
    remove(stderrPath)
    NativeCommandResult(decodeExitStatus(status), output, stderr)
}

@OptIn(kotlinx.cinterop.ExperimentalForeignApi::class)
private fun readPosixTextFile(path: String): String = memScoped {
    val stream = fopen(path, "r") ?: return@memScoped ""
    val buffer = allocArray<ByteVar>(4096)
    val text = buildString {
        while (fgets(buffer, 4096, stream) != null) append(buffer.toKString())
    }
    fclose(stream)
    text
}

private var processNonce = 0
private fun nextProcessNonce(): Int = ++processNonce
private fun decodeExitStatus(status: Int): Int = when {
    status < 0 -> 127
    status and 0x7f != 0 -> 128 + (status and 0x7f)
    else -> (status shr 8) and 0xff
}

internal fun shellQuote(value: String): String = "'" + value.replace("'", "'\\''") + "'"
