package dev.lynx.daemon

import dev.lynx.model.DatabaseFingerprint
import dev.lynx.model.DatabaseId
import dev.lynx.model.DatabaseSnapshot
import dev.lynx.model.EvidenceSource
import dev.lynx.model.SessionId
import dev.lynx.model.DatabaseQueryResponse
import dev.lynx.model.DatabaseSchemaResponse
import dev.lynx.model.DatabaseTablesResponse
import dev.lynx.model.SnapshotId
import dev.lynx.model.NetworkExchange
import dev.lynx.model.RequestId
import dev.lynx.adb.ResolvedTarget
import dev.lynx.model.EvidenceTimeline
import kotlinx.coroutines.flow.Flow

data class NetworkCaptureConfig(
    val listenHost: String = "0.0.0.0",
    val listenPort: Int = 0,
    /** Zero means retain the complete body; MVP deliberately has no semantic body cap. */
    val maxBodyBytes: Long = 0,
    val captureBodies: Boolean = true,
)

data class NetworkCapabilities(
    val source: EvidenceSource = EvidenceSource.NETWORK,
    val httpsMitm: Boolean,
    val maxBodyBytes: Long,
    val limitations: List<String>,
    val supportedProtocols: List<String> = emptyList(),
    val caFingerprint: String? = null,
    val caCertificatePath: String? = null,
    val caTrustStatus: String? = null,
)

sealed interface NetworkDomainEvent {
    data class Started(val requestId: String) : NetworkDomainEvent
    data class Completed(val requestId: String) : NetworkDomainEvent
    data class Failed(val requestId: String, val message: String) : NetworkDomainEvent
}

interface NetworkCaptureSource {
    val endpoint: String? get() = null
    val caCertificatePath: String? get() = null
    suspend fun start(config: NetworkCaptureConfig)
    suspend fun stop()
    fun events(): Flow<NetworkDomainEvent>
    suspend fun capabilities(): NetworkCapabilities
    fun exchanges(): List<NetworkExchange> = emptyList()
    fun exchange(requestId: RequestId): NetworkExchange? = null
}

data class DatabaseDescriptor(
    val sessionId: SessionId,
    val databaseId: DatabaseId,
    val name: String,
    val sizeBytes: Long,
    val relativePath: String = databaseId.value,
    val wal: DatabaseSidecarState = DatabaseSidecarState.absent(),
    val shm: DatabaseSidecarState = DatabaseSidecarState.absent(),
)

data class DatabaseSidecarState(
    val present: Boolean,
    val sizeBytes: Long?,
) {
    companion object {
        fun absent() = DatabaseSidecarState(present = false, sizeBytes = null)
        fun present(sizeBytes: Long) = DatabaseSidecarState(present = true, sizeBytes = sizeBytes)
    }
}

data class DatabaseCapabilities(
    val source: EvidenceSource = EvidenceSource.DATABASE,
    val supportsWal: Boolean,
    val readOnly: Boolean,
    val limitations: List<String>,
)

interface DatabaseSource {
    suspend fun discover(): List<DatabaseDescriptor>
    suspend fun fingerprint(database: DatabaseId): DatabaseFingerprint
    suspend fun snapshot(database: DatabaseId): DatabaseSnapshot
    suspend fun tables(snapshot: SnapshotId): DatabaseTablesResponse = error("database tables inspection unavailable")
    suspend fun schema(snapshot: SnapshotId): DatabaseSchemaResponse = error("database schema inspection unavailable")
    suspend fun query(snapshot: SnapshotId, sql: String): DatabaseQueryResponse = error("database query inspection unavailable")
    fun detach() = Unit
}

fun interface NetworkSourceFactory { fun create(target: ResolvedTarget, timeline: EvidenceTimeline): NetworkCaptureSource }
fun interface DatabaseSourceFactory { fun create(target: ResolvedTarget): DatabaseSource }
