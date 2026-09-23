package dev.lynx.nativehost

import kotlinx.cinterop.ExperimentalForeignApi
import kotlinx.cinterop.toKString
import platform.posix.getenv
import platform.posix.usleep

/** Owns the device-local Android relay. Release archives bundle ABI-matched helpers;
 * LYNX_ANDROID_RELAY_BINARY remains an explicit override for development. */
@OptIn(ExperimentalForeignApi::class)
class NativeAndroidRelayController(private val processes: NativeProcessRunner) {
    data class Lease(val remotePath: String, val relayPid: String, val relayPort: Int, val upstreamPort: Int)

    fun available(deviceSerial: String): Boolean = resolveBinary(deviceSerial) != null

    fun start(deviceSerial: String, packageName: String, pid: Int, hostPort: Int, captureId: String, captureToken: String): Lease {
        val binary = resolveBinary(deviceSerial)
            ?: error("ANDROID_RELAY_UNAVAILABLE")
        val relayPort = getenv("LYNX_ANDROID_RELAY_PORT")?.toKString()?.toIntOrNull()?.takeIf { it in 1024..65535 } ?: 62007
        val remotePath = "/data/local/tmp/lynx/relay-${captureToken.replace(Regex("[^A-Za-z0-9_-]"), "_")}"
        val prefix = listOf("adb", "-s", deviceSerial)
        require(processes.run(prefix + listOf("reverse", "tcp:$hostPort", "tcp:$hostPort")).exitCode == 0) { "ANDROID_RELAY_REVERSE_FAILED" }
        require(processes.run(prefix + listOf("shell", "mkdir", "-p", "/data/local/tmp/lynx")).exitCode == 0) { "ANDROID_RELAY_PREPARE_FAILED" }
        require(processes.run(prefix + listOf("push", binary, remotePath)).exitCode == 0) { "ANDROID_RELAY_PUSH_FAILED" }
        val remoteCommand = "chmod 700 '$remotePath' && setsid nohup '$remotePath' --package '${packageName.replace("'", "")}' --pid $pid --listen-port $relayPort --upstream-host 127.0.0.1 --upstream-port $hostPort --capture-id '${captureId.replace("'", "")}' --device '${deviceSerial.replace("'", "")}' --token '$captureToken' > '$remotePath.ready' 2>&1 < /dev/null & echo \$!"
        // adb shell keeps its transport attached to a background child on some emulator
        // images. Detach the host-side adb invocation too; readiness is checked separately.
        val launch = processes.run(listOf("sh", "-c", "adb -s '${deviceSerial.replace("'", "")}' shell ${quote(remoteCommand)} >/dev/null 2>&1 & echo \$!"))
        require(launch.exitCode == 0) { launch.stderr.ifBlank { "ANDROID_RELAY_START_FAILED" } }
        val relayPid = launch.stdout.trim().lineSequence().lastOrNull { it.trim().toIntOrNull() != null }?.trim()
            ?: error("ANDROID_RELAY_PID_UNAVAILABLE")
        repeat(20) {
            val ready = processes.run(prefix + listOf("shell", "cat", "$remotePath.ready"))
            if (ready.exitCode == 0 && ready.stdout.contains("relay_ready")) return Lease(remotePath, relayPid, relayPort, hostPort)
            usleep(50_000u)
        }
        stop(deviceSerial, Lease(remotePath, relayPid, relayPort, hostPort))
        error("ANDROID_RELAY_NOT_READY")
    }

    fun stop(deviceSerial: String, lease: Lease?) {
        if (lease == null) return
        val prefix = listOf("adb", "-s", deviceSerial, "shell")
        val processName = lease.remotePath.substringAfterLast('/')
        val pids = processes.run(prefix + listOf("pidof", processName)).stdout.trim()
            .split(Regex("\\s+")).filter { it.toIntOrNull() != null }
        pids.forEach { processes.run(prefix + listOf("kill", "-9", it)) }
        processes.run(prefix + listOf("pkill", "-f", lease.remotePath))
        processes.run(listOf("adb", "-s", deviceSerial, "reverse", "--remove", "tcp:${lease.upstreamPort}"))
        processes.run(prefix + listOf("rm", "-f", lease.remotePath, "${lease.remotePath}.ready"))
    }

    private fun quote(value: String) = "'" + value.replace("'", "'\\''") + "'"

    private fun resolveBinary(deviceSerial: String): String? {
        val override = getenv("LYNX_ANDROID_RELAY_BINARY")?.toKString()?.takeIf(String::isNotBlank)
        if (override != null && processes.run(listOf("test", "-x", override)).exitCode == 0) return override

        val executable = nativeExecutablePath() ?: return null
        val root = executable.substringBeforeLast('/', missingDelimiterValue = ".")
        val abiOutput = processes.run(listOf("adb", "-s", deviceSerial, "shell", "getprop", "ro.product.cpu.abilist")).stdout
        val abis = abiOutput.trim().split(',').filter { it.isNotBlank() }
        val candidates = (abis + listOf("arm64-v8a", "x86_64")).mapNotNull { abi ->
            when {
                abi == "arm64-v8a" -> "arm64-v8a"
                abi == "x86_64" -> "x86_64"
                else -> null
            }
        }.distinct()
        return candidates
            .map { "$root/android-relay/$it/lynx-android-relay" }
            .firstOrNull { processes.run(listOf("test", "-x", it)).exitCode == 0 }
    }
}
