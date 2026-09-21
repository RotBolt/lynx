package dev.lynx.model

@JvmInline
value class SessionId(val value: String)

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
