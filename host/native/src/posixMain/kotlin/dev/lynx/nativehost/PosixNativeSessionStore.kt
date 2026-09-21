package dev.lynx.nativehost

import kotlinx.cinterop.ExperimentalForeignApi
import kotlinx.cinterop.toKString
import platform.posix.getenv

/** Cross-process state is kept in a small tab-separated record under ~/.lynx. */
@OptIn(ExperimentalForeignApi::class)
class PosixNativeSessionStore : NativeSessionStore {
    private val path: String = (getenv("HOME")?.toKString()?.takeIf(String::isNotBlank) ?: "/tmp") + "/.lynx/native-session"

    override fun load(): NativeSession? {
        val result = PosixProcessRunner().run(listOf("sh", "-c", "test -f '$path' && cat '$path'"))
        if (result.exitCode != 0 || result.stdout.isBlank()) return null
        val fields = result.stdout.trimEnd().split('\t')
        if (fields.size != 4) return null
        return NativeSession(fields[0], fields[1], fields[2], fields[3].toIntOrNull() ?: return null)
    }

    override fun save(session: NativeSession) {
        val command = "mkdir -p '${path.substringBeforeLast('/')}' && printf '%s\\t%s\\t%s\\t%s\\n' '${quote(session.id)}' '${quote(session.deviceSerial)}' '${quote(session.packageName)}' '${session.processId}' > '$path'"
        PosixProcessRunner().run(listOf("sh", "-c", command))
    }

    override fun clear() {
        PosixProcessRunner().run(listOf("sh", "-c", "rm -f '$path'"))
    }

    private fun quote(value: String) = value.replace("'", "'\\''")
}
