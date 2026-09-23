package dev.lynx.model

import kotlinx.serialization.Serializable

@Serializable
data class ProcessIdentity(val pid: Int, val startIdentity: String)

@Serializable
data class VerifiedOrigin(
    val target: CaptureTarget,
    val process: ProcessIdentity,
    val uid: Int? = null,
    val method: String,
)

@Serializable
data class ConnectionContext(
    val captureId: String,
    val connectionId: String,
    val origin: VerifiedOrigin,
    val acceptedAtEpochMillis: Long,
)

@Serializable
data class SocketTuple(val localAddress: String, val localPort: Int, val peerAddress: String, val peerPort: Int)
