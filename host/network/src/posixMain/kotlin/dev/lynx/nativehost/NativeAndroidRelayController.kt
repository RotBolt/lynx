package dev.lynx.nativehost

import kotlinx.cinterop.ExperimentalForeignApi
import kotlinx.cinterop.toKString
import platform.posix.getenv
import platform.posix.usleep

/** Owns the optional device-local Android relay. Direct global-proxy mode remains the fallback
 * until a distribution bundles an ABI-matching relay binary. */
@OptIn(ExperimentalForeignApi::class)
class NativeAndroidRelayController(private val processes: NativeProcessRunner) {
    data class Lease(val remotePath: String, val relayPid: String, val relayPort: Int, val upstreamPort: Int)

    fun available(): Boolean = getenv("LYNX_ANDROID_RELAY_BINARY")?.toKString()?.let { path ->
        processes.run(listOf("test", "-x", path)).exitCode == 0
    } == true

    fun start(deviceSerial: String, packageName: String, pid: Int, hostPort: Int, captureId: String, captureToken: String): Lease {
        val binary = getenv("LYNX_ANDROID_RELAY_BINARY")?.toKString()?.takeIf(String::isNotBlank)
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
}
