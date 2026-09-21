package dev.lynx.adb

import java.nio.file.Files
import java.nio.file.Path
import kotlin.io.path.Path

object AdbExecutableResolver {
    fun resolve(
        environment: Map<String, String> = System.getenv(),
        userHome: String = System.getProperty("user.home"),
        path: String = environment["PATH"].orEmpty(),
    ): String {
        val candidates = buildList {
            environment["ANDROID_HOME"]?.takeIf { it.isNotBlank() }?.let { add(Path(it, "platform-tools", "adb")) }
            environment["ANDROID_SDK_ROOT"]?.takeIf { it.isNotBlank() }?.let { add(Path(it, "platform-tools", "adb")) }
            add(Path(userHome, "Library", "Android", "sdk", "platform-tools", "adb"))
            add(Path(userHome, "Android", "Sdk", "platform-tools", "adb"))
            path.split(java.io.File.pathSeparator).filter { it.isNotBlank() }.forEach { add(Path(it, "adb")) }
            add(Path("/opt/homebrew/bin/adb"))
            add(Path("/usr/local/bin/adb"))
        }
        return candidates.firstOrNull { Files.isRegularFile(it) && Files.isExecutable(it) }?.toString() ?: "adb"
    }
}
