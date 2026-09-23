package dev.lynx.nativehost

import dev.lynx.model.CaptureSession
import dev.lynx.model.CaptureState
import dev.lynx.model.CaptureTarget
import dev.lynx.model.ConnectionContext
import dev.lynx.model.NetworkExchange
import dev.lynx.model.SocketTuple
import dev.lynx.model.VerifiedOrigin

sealed interface OwnershipDecision {
    data class Target(val origin: VerifiedOrigin) : OwnershipDecision
    data object Other : OwnershipDecision
    data class Unknown(val reason: String) : OwnershipDecision
}

fun interface ConnectionOwnerResolver {
    fun resolve(target: CaptureTarget, socket: SocketTuple): OwnershipDecision
}

data class VerifiedExchange(val context: ConnectionContext, val exchange: NetworkExchange)
data class CaptureRead(val session: CaptureSession, val throughSequence: Long, val exchanges: List<VerifiedExchange>, val inFlightCount: Int)

interface CaptureRepository {
    fun create(session: CaptureSession)
    fun session(id: String): CaptureSession?
    fun sessions(): List<CaptureSession>
    fun transition(id: String, expected: CaptureState, next: CaptureState): Boolean
    fun append(exchange: VerifiedExchange): Long
    fun read(id: String, throughSequence: Long? = null): CaptureRead
}
