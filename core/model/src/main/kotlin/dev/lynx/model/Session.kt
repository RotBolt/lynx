package dev.lynx.model

import kotlinx.serialization.Serializable

@Serializable
data class SessionId(val value: String)

enum class SessionStatus {
    ACTIVE,
}

data class InspectorSession(
    val id: SessionId,
    val deviceSerial: String,
    val packageName: String,
    val pid: Int,
    val protocolVersion: Int,
    val status: SessionStatus,
)
