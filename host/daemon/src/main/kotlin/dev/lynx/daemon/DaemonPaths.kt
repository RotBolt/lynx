package dev.lynx.daemon

import java.nio.file.Path

object DaemonPaths {
    fun defaultSocket(home: String = System.getProperty("user.home")): Path =
        Path.of(home, ".lynx", "daemon.sock")
}
