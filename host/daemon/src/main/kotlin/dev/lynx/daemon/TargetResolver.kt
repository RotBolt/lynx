package dev.lynx.daemon

import dev.lynx.adb.AdbClient
import dev.lynx.adb.ProcessCommandRunner
import dev.lynx.adb.ResolvedTarget

fun interface TargetResolver {
    fun resolve(deviceSerial: String?, packageName: String): ResolvedTarget
}

class AdbTargetResolver(
    private val adb: AdbClient = AdbClient(ProcessCommandRunner()),
) : TargetResolver {
    override fun resolve(deviceSerial: String?, packageName: String): ResolvedTarget =
        adb.resolveTarget(deviceSerial, packageName)
}
