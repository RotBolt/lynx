package dev.lynx.database

import dev.lynx.model.DatabaseId
import dev.lynx.model.DatabaseQueryResponse
import dev.lynx.model.DatabaseSchemaResponse
import dev.lynx.model.DatabaseSnapshot
import dev.lynx.model.DatabaseTablesResponse
import dev.lynx.model.SessionId
import dev.lynx.model.SnapshotId
import java.nio.file.Files
import java.nio.file.Path

sealed interface QueryTarget {
    val sessionId: SessionId

    data class Snapshot(
        override val sessionId: SessionId,
        val snapshotId: SnapshotId,
    ) : QueryTarget

    data class Live(
        override val sessionId: SessionId,
        val databaseId: DatabaseId,
    ) : QueryTarget
}

interface DatabaseInspector {
    fun tables(target: QueryTarget): DatabaseTablesResponse
    fun schema(target: QueryTarget): DatabaseSchemaResponse
    fun query(target: QueryTarget, sql: String): DatabaseQueryResponse
}

class SessionSnapshotRegistry {
    private val snapshots = mutableMapOf<Pair<SessionId, SnapshotId>, DatabaseSnapshot>()
    private val detachedSessions = mutableSetOf<SessionId>()

    @Synchronized
    fun register(snapshot: DatabaseSnapshot) {
        val sessionId = snapshot.meta.sessionId
        if (sessionId in detachedSessions) {
            throw DatabaseException(
                code = "SESSION_DETACHED",
                operation = "db.snapshot",
                message = "Session ${sessionId.value} is detached",
                resourceId = snapshot.snapshotId.value,
            )
        }
        snapshots[sessionId to snapshot.snapshotId] = snapshot
    }

    @Synchronized
    fun detach(sessionId: SessionId) {
        detachedSessions += sessionId
    }

    @Synchronized
    fun resolve(sessionId: SessionId, snapshotId: SnapshotId, operation: String): DatabaseSnapshot {
        if (sessionId in detachedSessions) {
            throw DatabaseException(
                code = "SESSION_DETACHED",
                operation = operation,
                message = "Session ${sessionId.value} is detached",
                resourceId = snapshotId.value,
            )
        }
        val snapshot = snapshots[sessionId to snapshotId] ?: throw DatabaseException(
            code = "SNAPSHOT_NOT_FOUND",
            operation = operation,
            message = "Snapshot ${snapshotId.value} was not found in session ${sessionId.value}",
            resourceId = snapshotId.value,
        )
        if (!Files.isRegularFile(Path.of(snapshot.localPath))) {
            throw DatabaseException(
                code = "SNAPSHOT_NOT_FOUND",
                operation = operation,
                message = "Snapshot artifact ${snapshotId.value} is unavailable",
                resourceId = snapshotId.value,
            )
        }
        return snapshot
    }
}
