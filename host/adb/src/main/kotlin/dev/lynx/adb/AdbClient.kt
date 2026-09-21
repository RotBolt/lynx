package dev.lynx.adb

data class AdbDevice(val serial: String, val state: String)

data class ResolvedTarget(
    val deviceSerial: String,
    val packageName: String,
    val pid: Int,
)

class AdbException(
    val code: String,
    message: String,
    cause: Throwable? = null,
) : RuntimeException(message, cause)

class AdbClient(
    private val runner: CommandRunner,
    private val executable: String = defaultExecutable(),
) {
    val executablePath: String get() = executable

    fun devices(): List<AdbDevice> {
        val result = run(listOf(executable, "devices", "-l"))
        return result.stdout.lineSequence()
            .drop(1)
            .mapNotNull { line ->
                val fields = line.trim().split(Regex("\\s+"))
                if (fields.size >= 2 && fields[1] == "device") AdbDevice(fields[0], fields[1]) else null
            }
            .toList()
    }

    fun resolveTarget(deviceSerial: String?, packageName: String): ResolvedTarget {
        if (packageName.isBlank()) throw AdbException("PACKAGE_REQUIRED", "Package name is required")
        val onlineDevices = devices()
        val device = when {
            deviceSerial != null -> onlineDevices.firstOrNull { it.serial == deviceSerial }
                ?: throw AdbException("DEVICE_NOT_FOUND", "Device '$deviceSerial' is not online")
            onlineDevices.size == 1 -> onlineDevices.single()
            onlineDevices.isEmpty() -> throw AdbException("DEVICE_NOT_FOUND", "No online Android device found")
            else -> throw AdbException("MULTIPLE_DEVICES", "Specify --device when multiple devices are online")
        }

        val pidResult = run(listOf(executable, "-s", device.serial, "shell", "pidof", packageName), allowFailure = true)
        val pids = pidResult.stdout.trim().split(Regex("\\s+")).mapNotNull { it.toIntOrNull() }
        if (pids.isEmpty()) throw AdbException("PROCESS_NOT_RUNNING", "Package '$packageName' has no running process")
        if (pids.size > 1) throw AdbException("MULTIPLE_PROCESSES", "Package '$packageName' has multiple running processes")

        val debugCheck = run(listOf(executable, "-s", device.serial, "shell", "run-as", packageName, "id"), allowFailure = true)
        if (debugCheck.exitCode != 0) {
            throw AdbException("APP_NOT_DEBUGGABLE", "Package '$packageName' is not debuggable")
        }

        return ResolvedTarget(device.serial, packageName, pids.single())
    }

    private fun run(arguments: List<String>, allowFailure: Boolean = false): CommandResult {
        val result = runner.run(arguments)
        if (result.exitCode != 0 && !allowFailure) {
            throw AdbException("ADB_COMMAND_FAILED", result.stderr.ifBlank { "adb command failed" })
        }
        return result
    }

    companion object {
        fun defaultExecutable(environment: Map<String, String> = System.getenv()): String {
            return AdbExecutableResolver.resolve(environment)
        }
    }
}
