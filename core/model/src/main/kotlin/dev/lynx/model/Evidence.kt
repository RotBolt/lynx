package dev.lynx.model

import kotlinx.datetime.Instant
import kotlinx.serialization.SerialName
import kotlinx.serialization.Serializable

@Serializable
data class EvidenceId(val value: String)
@Serializable
data class RequestId(val value: String)
@Serializable
data class SnapshotId(val value: String)
@Serializable
data class DatabaseId(val value: String)

@Serializable
enum class EvidenceSource { NETWORK, DATABASE, RUNTIME }

@Serializable
data class EvidenceMeta(
    val id: EvidenceId,
    val sessionId: SessionId,
    val observedAt: Instant,
    val source: EvidenceSource,
    val deviceSerial: String,
    val packageName: String,
    val processId: Int?,
)

sealed interface Evidence { val meta: EvidenceMeta }

@Serializable
data class NetworkRequest(
    val method: String,
    val url: String,
    val headers: Map<String, String>,
    val body: String?,
)

@Serializable
data class NetworkResponse(
    val status: Int,
    val headers: Map<String, String>,
    val body: String?,
)

@Serializable
data class NetworkFrame(
    val direction: String,
    val opcode: String,
    val payload: String?,
)

@Serializable
data class NetworkFailure(val kind: String, val message: String?)

@Serializable
data class NetworkTiming(
    val startedAtEpochMillis: Long,
    val completedAtEpochMillis: Long?,
    val durationMillis: Long?,
) {
    init {
        require(startedAtEpochMillis >= 0)
        require(completedAtEpochMillis == null || completedAtEpochMillis >= startedAtEpochMillis)
        require(durationMillis == null || durationMillis >= 0)
    }
}

@Serializable
data class NetworkCaptureMetadata(
    val requestBodyTruncated: Boolean,
    val requestBodyBytes: Long,
    val responseBodyTruncated: Boolean?,
)

@Serializable
data class NetworkExchange(
    override val meta: EvidenceMeta,
    val requestId: RequestId,
    val request: NetworkRequest,
    val response: NetworkResponse?,
    val failure: NetworkFailure?,
    val timing: NetworkTiming,
    val capture: NetworkCaptureMetadata,
    val protocol: String? = null,
    val frames: List<NetworkFrame> = emptyList(),
    /** Populated only by schema-v2 scoped capture reads. */
    @SerialName("capture_session_id")
    val captureSessionId: String? = null,
    val sequence: Long? = null,
    val attribution: NetworkAttribution? = null,
) : Evidence

data class DatabaseFingerprint(val value: String, val files: List<String>)

data class DatabaseSnapshot(
    override val meta: EvidenceMeta,
    val snapshotId: SnapshotId,
    val databaseId: DatabaseId,
    val sourceFingerprint: DatabaseFingerprint,
    val localPath: String,
    val consistent: Boolean,
    val consistencyMethod: String,
) : Evidence

data class StackFrame(val className: String, val methodName: String, val fileName: String?, val lineNumber: Int?)

data class RuntimeAttribution(
    override val meta: EvidenceMeta,
    val threadId: Long?,
    val callstack: List<StackFrame>,
    val correlatedEvidenceIds: List<EvidenceId>,
) : Evidence
