package dev.lynx.nativehost

import dev.lynx.model.CaptureTarget
import dev.lynx.model.SocketTuple
import dev.lynx.model.VerifiedOrigin

/** Decides whether a connection may reach the recording/MITM path. */
class ConnectionAdmission(private val resolver: ConnectionOwnerResolver) {
    sealed interface Decision {
        data class Intercept(val origin: VerifiedOrigin) : Decision
        data class PassThrough(val reason: String) : Decision
    }

    fun decide(target: CaptureTarget, socket: SocketTuple): Decision = when (val owner = resolver.resolve(target, socket)) {
        is OwnershipDecision.Target -> Decision.Intercept(owner.origin)
        OwnershipDecision.Other -> Decision.PassThrough("verified_other")
        is OwnershipDecision.Unknown -> Decision.PassThrough(owner.reason)
    }
}
