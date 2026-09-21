package dev.lynx.daemon

import dev.lynx.adb.AdbClient
import dev.lynx.adb.ProcessCommandRunner
import dev.lynx.adb.ResolvedTarget

fun interface TargetResolver {
    fun resolve(deviceSerial: String?, packageName: String): ResolvedTarget
}

class AdbTargetResolver(
    private val adb: AdbClient = AdbClient(ProcessCommandRunner()),
    private val ios: IosSimulatorTargetResolver = IosSimulatorTargetResolver(),
) : TargetResolver {
    override fun resolve(deviceSerial: String?, packageName: String): ResolvedTarget =
        if (deviceSerial?.startsWith("ios-simulator:") == true) {
            ios.resolve(deviceSerial.removePrefix("ios-simulator:"), packageName)
        } else adb.resolveTarget(deviceSerial, packageName)
}
