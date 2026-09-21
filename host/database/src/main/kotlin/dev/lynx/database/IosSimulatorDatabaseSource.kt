package dev.lynx.database

import dev.lynx.adb.CommandRunner
import dev.lynx.adb.ProcessCommandRunner
import dev.lynx.daemon.DatabaseDescriptor
import dev.lynx.daemon.DatabaseSidecarState
import dev.lynx.daemon.DatabaseSource
import dev.lynx.model.*
import java.nio.file.Files
import java.nio.file.Path
import java.security.MessageDigest
import java.time.Instant
import java.util.UUID

/** Read-only SQLite access to an iOS Simulator app container. */
class IosSimulatorDatabaseSource(
    private val sessionId: SessionId,
    private val udid: String,
    private val bundleId: String,
    private val runner: CommandRunner = ProcessCommandRunner(),
    private val outputDirectory: Path = Path.of(System.getProperty("java.io.tmpdir"), "lynx-snapshots"),
) : DatabaseSource {
    private val snapshots = SessionSnapshotRegistry()
    private val inspector = SqliteDatabaseInspector(snapshots)

    override suspend fun discover(): List<DatabaseDescriptor> {
        val root = container()
        if (!Files.isDirectory(root)) throw DatabaseException("DATABASE_DISCOVERY_FAILED", "db.list", "Simulator app container is unavailable")
        return Files.walk(root).use { stream ->
            stream.filter(Files::isRegularFile)
                .filter { it.fileName.toString().endsWith(".db", true) || it.fileName.toString().endsWith(".sqlite", true) }
                .filter { isSqlite(it) }
                .map { path ->
                    val id = root.relativize(path).toString()
                    DatabaseDescriptor(sessionId, DatabaseId(id), path.fileName.toString(), Files.size(path), id, sidecar(path, "-wal"), sidecar(path, "-shm"))
                }.toList().sortedBy { it.databaseId.value }
        }
    }

    override suspend fun fingerprint(database: DatabaseId): DatabaseFingerprint {
        val path = resolve(database)
        return DatabaseFingerprint(digest(path), listOf(database.value))
    }

    override suspend fun snapshot(database: DatabaseId): DatabaseSnapshot {
        val source = resolve(database)
        Files.createDirectories(outputDirectory)
        val destination = outputDirectory.resolve("snap_${UUID.randomUUID()}_${source.fileName}")
        Files.copy(source, destination)
        copySidecar(source, destination, "-wal")
        copySidecar(source, destination, "-shm")
        val snapshot = DatabaseSnapshot(
            EvidenceMeta(EvidenceId("ev_${UUID.randomUUID()}"), sessionId, Instant.now(), EvidenceSource.DATABASE, "ios-simulator:$udid", bundleId, null),
            SnapshotId(destination.fileName.toString()), database, DatabaseFingerprint(digest(destination), listOf(database.value)), destination.toString(), false,
            "simctl app-container copy; WAL/SHM copied independently",
        )
        snapshots.register(snapshot)
        return snapshot
    }

    override suspend fun tables(snapshot: SnapshotId) = inspector.tables(QueryTarget.Snapshot(sessionId, snapshot))
    override suspend fun schema(snapshot: SnapshotId) = inspector.schema(QueryTarget.Snapshot(sessionId, snapshot))
    override suspend fun query(snapshot: SnapshotId, sql: String) = inspector.query(QueryTarget.Snapshot(sessionId, snapshot), sql)
    override fun detach() = snapshots.detach(sessionId)

    private fun container(): Path {
        val result = runner.run(listOf("xcrun", "simctl", "get_app_container", udid, bundleId, "data"))
        if (result.exitCode != 0) throw DatabaseException("DATABASE_DISCOVERY_FAILED", "db.container", result.stderr.ifBlank { "simctl app container unavailable" })
        return Path.of(result.stdout.trim())
    }

    private fun resolve(database: DatabaseId): Path {
        val root = container()
        val path = root.resolve(database.value).normalize()
        if (!path.startsWith(root) || !Files.isRegularFile(path) || !isSqlite(path)) throw DatabaseException("DATABASE_NOT_FOUND", "db.snapshot", "SQLite database '${database.value}' was not found")
        return path
    }

    private fun sidecar(path: Path, suffix: String): DatabaseSidecarState {
        val sidecar = Path.of("${path}$suffix")
        return if (Files.isRegularFile(sidecar)) DatabaseSidecarState.present(Files.size(sidecar)) else DatabaseSidecarState.absent()
    }

    private fun copySidecar(source: Path, destination: Path, suffix: String) {
        val sidecar = Path.of("${source}$suffix")
        if (Files.isRegularFile(sidecar)) Files.copy(sidecar, Path.of("${destination}$suffix"))
    }

    private fun isSqlite(path: Path): Boolean = runCatching {
        Files.readAllBytes(path).take(16).toByteArray().contentEquals("SQLite format 3\u0000".toByteArray())
    }.getOrDefault(false)

    private fun digest(path: Path): String = MessageDigest.getInstance("SHA-256").digest(Files.readAllBytes(path)).joinToString("") { "%02x".format(it) }
}
