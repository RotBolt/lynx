package dev.lynx.model

import kotlinx.serialization.Serializable

@Serializable
data class CaptureTarget(
    val platform: String,
    val deviceId: String,
    val applicationId: String,
    val androidUserId: Int? = null,
)

@Serializable
enum class CaptureState { STARTING, RUNNING, STOPPING, STOPPED, INTERRUPTED, FAILED }

@Serializable
data class CaptureSession(
    val id: String,
    val attachmentId: String,
    val target: CaptureTarget,
    val state: CaptureState,
    val startedAtEpochMillis: Long,
)
