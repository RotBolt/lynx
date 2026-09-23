package dev.lynx.nativehost

import dev.lynx.model.CaptureTarget
import dev.lynx.model.ProcessIdentity
import dev.lynx.model.SocketTuple
import dev.lynx.model.VerifiedOrigin
import dev.lynx.nativehost.proc.lynx_proc_socket_owner
import dev.lynx.nativehost.proc.lynx_proc_start_identity
import kotlinx.cinterop.ExperimentalForeignApi

/** iOS Simulator-only resolver. It accepts only the attached process with an
 * unchanged libproc start identity and an exact TCP remote tuple. */
@OptIn(ExperimentalForeignApi::class)
class MacosConnectionOwnerResolver(
    private val runner: NativeProcessRunner = PosixProcessRunner(),
) : ConnectionOwnerResolver {
    override fun resolve(target: CaptureTarget, socket: SocketTuple): OwnershipDecision {
        if (target.platform != "ios") return OwnershipDecision.Other
        val container = runner.run(listOf("xcrun", "simctl", "get_app_container", target.deviceId, target.applicationId, "app"))
            .takeIf { it.exitCode == 0 }?.stdout?.trim()?.takeIf(String::isNotBlank)
            ?: return OwnershipDecision.Unknown("simulator application container is unavailable")
        val processes = runner.run(listOf("ps", "-axo", "pid=,command="))
        if (processes.exitCode != 0) return OwnershipDecision.Unknown("cannot inspect host process list")
        val processId = processes.stdout.lineSequence()
            .mapNotNull { line -> line.trim().split(Regex("\\s+"), limit = 2).takeIf { it.size == 2 } }
            .firstOrNull { fields -> fields[1].startsWith(container) }
            ?.get(0)?.toIntOrNull()
            ?: return OwnershipDecision.Other
        val startIdentity = lynx_proc_start_identity(processId).takeIf { it != 0uL }
            ?: return OwnershipDecision.Unknown("cannot inspect simulator process identity")
        val localAddresses = listOf(socket.peerAddress, "127.0.0.1", "::1").distinct()
        val remoteAddresses = listOf(socket.localAddress, "127.0.0.1", "::1").distinct()
        val results = localAddresses.flatMap { local ->
            remoteAddresses.map { remote ->
                lynx_proc_socket_owner(
                    processId,
                    local,
                    socket.peerPort.toUShort(),
                    remote,
                    socket.localPort.toUShort(),
                )
            }
        }
        val ownership = when {
            results.any { it == 1 } -> 1
            results.any { it < 0 } -> -1
            else -> 0
        }
        return when (ownership) {
            1 -> OwnershipDecision.Target(
                VerifiedOrigin(target, ProcessIdentity(processId, startIdentity.toString()), method = "libproc-tcp-tuple"),
            )
            0 -> OwnershipDecision.Other
            else -> OwnershipDecision.Unknown("libproc socket inspection failed")
        }
    }
}
