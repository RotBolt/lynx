package dev.lynx.nativehost

import kotlinx.cinterop.ByteVar
import kotlinx.cinterop.CPointer
import kotlinx.cinterop.allocArray
import kotlinx.cinterop.memScoped
import kotlinx.cinterop.toKString
import platform.posix.FILE
import platform.posix.fgets
import platform.posix.getenv
import platform.posix.pclose
import platform.posix.popen

/** Minimal process adapter for macOS/Linux native binaries. */
@OptIn(kotlinx.cinterop.ExperimentalForeignApi::class)
class PosixProcessRunner : NativeProcessRunner {
    override fun run(command: List<String>): NativeCommandResult = memScoped {
        require(command.isNotEmpty()) { "command must not be empty" }
        val adb = resolveAdb()
        val normalized = command.mapIndexed { index, value ->
            when {
                index == 0 && value == "adb" -> adb
                index == 2 && command.firstOrNull() == "sh" && value.contains("adb") -> value.replace("adb", shellQuote(adb))
                else -> value
            }
        }
        val shell = normalized.joinToString(" ") { shellQuote(it) }
        val stream: CPointer<FILE> = popen(shell, "r") ?: return@memScoped NativeCommandResult(127, "", "unable to start $shell")
        val buffer = allocArray<ByteVar>(4096)
        val output = buildString {
            while (fgets(buffer, 4096, stream) != null) append(buffer.toKString())
        }
        val status = pclose(stream)
        NativeCommandResult(if (status == 0) 0 else 1, output)
    }

    private fun shellQuote(value: String): String = "'" + value.replace("'", "'\\''") + "'"

    private fun resolveAdb(): String {
        val candidates = buildList {
            getenv("ANDROID_HOME")?.toKString()?.takeIf(String::isNotBlank)?.let { add("$it/platform-tools/adb") }
            getenv("ANDROID_SDK_ROOT")?.toKString()?.takeIf(String::isNotBlank)?.let { add("$it/platform-tools/adb") }
            add("adb")
        }
        return candidates.firstOrNull { candidate ->
            if (candidate == "adb") true else popen("test -x ${shellQuote(candidate)}", "r")?.let { stream -> pclose(stream) == 0 } == true
        } ?: "adb"
    }
}
