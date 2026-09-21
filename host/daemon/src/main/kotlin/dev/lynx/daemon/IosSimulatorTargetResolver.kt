package dev.lynx.daemon

import dev.lynx.adb.CommandRunner
import dev.lynx.adb.ProcessCommandRunner
import dev.lynx.adb.ResolvedTarget
import java.util.UUID

/** Resolves and launches a debuggable iOS Simulator bundle through simctl. */
class IosSimulatorTargetResolver(
    private val runner: CommandRunner = ProcessCommandRunner(),
    private val xcrun: String = "xcrun",
) {
    fun resolve(udid: String, bundleId: String): ResolvedTarget {
        if (!isUuid(udid)) throw dev.lynx.adb.AdbException("DEVICE_NOT_FOUND", "Invalid iOS simulator UDID '$udid'")
        val devices = run(listOf(xcrun, "simctl", "list", "devices", "available"))
        val booted = devices.stdout.lineSequence().any { it.contains("($udid)") && it.contains("Booted") }
        if (!booted) throw dev.lynx.adb.AdbException("DEVICE_NOT_FOUND", "iOS simulator '$udid' is not booted")
        val launch = run(listOf(xcrun, "simctl", "launch", udid, bundleId))
        val pid = launch.stdout.trim().substringAfterLast(':').trim().toIntOrNull()
            ?: throw dev.lynx.adb.AdbException("PROCESS_NOT_RUNNING", "Unable to resolve process for '$bundleId' on simulator '$udid'")
        return ResolvedTarget("ios-simulator:$udid", bundleId, pid)
    }

    private fun run(args: List<String>) = runner.run(args).also {
        if (it.exitCode != 0) throw dev.lynx.adb.AdbException("SIMCTL_COMMAND_FAILED", it.stderr.ifBlank { "simctl command failed" })
    }

    private fun isUuid(value: String): Boolean = runCatching { UUID.fromString(value); true }.getOrDefault(false)
}
