package dev.lynx.database

import dev.lynx.adb.BinaryCommandRunner
import dev.lynx.adb.CommandRunner
import dev.lynx.adb.ProcessCommandRunner
import dev.lynx.daemon.DatabaseDescriptor
import dev.lynx.daemon.DatabaseSidecarState
import dev.lynx.daemon.DatabaseSource
import dev.lynx.model.*
import java.nio.file.Files
import java.nio.file.Path
import java.time.Instant
import java.util.UUID
import java.security.MessageDigest

class AdbDatabaseSource(
    private val sessionId: SessionId,
    private val deviceSerial: String,
    private val packageName: String,
    private val processId: Int?,
    private val runner: CommandRunner = ProcessCommandRunner(),
    private val executable: String = AdbExecutablePath.resolve(),
    private val outputDirectory: Path = Path.of(System.getProperty("java.io.tmpdir"), "lynx-snapshots"),
) : DatabaseSource {
    private val snapshots = SessionSnapshotRegistry()
    private val inspector = SqliteDatabaseInspector(snapshots)
    override suspend fun discover(): List<DatabaseDescriptor> {
        val files = run("db.list", null, "shell", "run-as", packageName, "find", "databases", "-type", "f")
            .stdout.lineSequence().map(String::trim).filter(String::isNotEmpty).toSet()
        return files.asSequence()
            .filter(::hasDatabaseExtension)
            .filter(::hasSqliteHeader)
            .map { path ->
                DatabaseDescriptor(
                    sessionId = sessionId,
                    databaseId = DatabaseId(path),
                    name = path.substringAfterLast('/'),
                    sizeBytes = size(path, "db.list"),
                    relativePath = path,
                    wal = sidecar(files, "$path-wal"),
                    shm = sidecar(files, "$path-shm"),
                )
            }
            .sortedBy { it.databaseId.value }
            .toList()
    }

    override suspend fun fingerprint(database: DatabaseId): DatabaseFingerprint {
        val result = run("db.fingerprint", database.value, "shell", "run-as", packageName, "sha256sum", database.value)
        return DatabaseFingerprint(result.stdout.trim().substringBefore(' '), listOf(database.value))
    }

    override suspend fun snapshot(database: DatabaseId): DatabaseSnapshot {
        // Resolve case-insensitively against the app catalog. This protects the
        // JSON/ADB boundary from shells or helpers that normalize argument casing,
        // while retaining the device's real path spelling for run-as.
        val resolvedDatabase = if (database.value.any(Char::isUpperCase)) {
            discover().firstOrNull { it.relativePath.equals(database.value, ignoreCase = true) }?.databaseId ?: database
        } else database
        Files.createDirectories(outputDirectory)
        val destination = outputDirectory.resolve("snap_${UUID.randomUUID()}_${resolvedDatabase.value.substringAfterLast('/')}")
        val acquisition = acquireSnapshot(resolvedDatabase, destination)
        // Fingerprint the bytes Lynx actually captured. Device-side sha256sum is not
        // required for database access and is inconsistently available across images.
        val digest = MessageDigest.getInstance("SHA-256").digest(Files.readAllBytes(destination))
        val sourceFingerprint = DatabaseFingerprint(
            digest.joinToString("") { "%02x".format(it) },
            listOf(resolvedDatabase.value),
        )
        val snapshot = DatabaseSnapshot(EvidenceMeta(EvidenceId("ev_${UUID.randomUUID()}"), sessionId, Instant.now(), EvidenceSource.DATABASE, deviceSerial, packageName, processId), SnapshotId(destination.fileName.toString()), resolvedDatabase, sourceFingerprint, destination.toString(), acquisition.consistent, acquisition.method)
        snapshots.register(snapshot)
        return snapshot
    }

    override suspend fun tables(snapshot: SnapshotId) = inspector.tables(QueryTarget.Snapshot(sessionId, snapshot))
    override suspend fun schema(snapshot: SnapshotId) = inspector.schema(QueryTarget.Snapshot(sessionId, snapshot))
    override suspend fun query(snapshot: SnapshotId, sql: String) = inspector.query(QueryTarget.Snapshot(sessionId, snapshot), sql)
    override fun detach() = snapshots.detach(sessionId)

    private data class Acquisition(val consistent: Boolean, val method: String)

    /**
     * SQLite's online backup API is the only acquisition we mark consistent. The Android
     * package need not ship a special Lynx agent: when the platform sqlite3 CLI is present,
     * run-as performs the backup inside the app sandbox while SQLite coordinates WAL readers.
     * Older/minimal images fall back to copying all files as a diagnostic artifact, explicitly
     * marked inconsistent so an agent never mistakes a file bundle for a coherent snapshot.
     */
    private fun acquireSnapshot(database: DatabaseId, destination: Path): Acquisition {
        // Stream the SQLite backup directly to stdout. A relative temporary filename
        // is unsafe here because run-as/sqlite3 does not guarantee the database
        // directory as its working directory.
        val backup = binary(
            "db.snapshot.backup",
            database.value,
            "exec-out", "run-as", packageName, "sqlite3", database.value,
            ".backup /dev/stdout",
        )
        if (backup.exitCode == 0 && isSqlite(backup.stdout)) {
            Files.write(destination, backup.stdout)
            return Acquisition(true, "sqlite3 backup streamed to stdout; WAL coordinated by SQLite")
        }

        // `adb exec-out` may return exit code 0 while run-as writes its failure
        // diagnostic to stdout. An invalid SQLite payload therefore also means
        // the sqlite3 helper is unavailable; continue to the file-stream fallback.
        if (backup.exitCode != 0 && !isMissingSqliteCli(backup.stderr)) {
            throw accessFailure("db.snapshot.backup", database.value, backup.stderr)
        }

        val main = binary("db.snapshot", database.value, "exec-out", "run-as", packageName, "cat", database.value)
        if (main.exitCode != 0) throw accessFailure("db.snapshot", database.value, main.stderr)
        if (!isSqlite(main.stdout)) throw accessFailure(
            "db.snapshot", database.value,
            main.stderr.ifBlank {
                val diagnostic = main.stdout.toString(Charsets.UTF_8).trim().take(240)
                if (diagnostic.isNotBlank()) "ADB returned a non-SQLite database payload: $diagnostic"
                else "ADB returned a non-SQLite database payload"
            },
        )
        Files.write(destination, main.stdout)
        val walPath = database.value + "-wal"
        val shmPath = database.value + "-shm"
        val wal = binary("db.snapshot.wal", walPath, "exec-out", "run-as", packageName, "cat", walPath)
        val shm = binary("db.snapshot.shm", shmPath, "exec-out", "run-as", packageName, "cat", shmPath)
        // Sidecars are colocated so SQLite can inspect the captured WAL. Their independent
        // transfer is intentionally reported as non-consistent.
        if (wal.exitCode == 0) Files.write(Path.of("${destination}-wal"), wal.stdout)
        if (shm.exitCode == 0) Files.write(Path.of("${destination}-shm"), shm.stdout)
        return Acquisition(false, "adb run-as copied main database and available WAL/SHM sidecars independently; coherence not guaranteed")
    }

    private fun isSqlite(bytes: ByteArray): Boolean =
        bytes.size >= 16 && bytes.copyOfRange(0, 16).contentEquals("SQLite format 3\u0000".toByteArray(Charsets.ISO_8859_1))

    private fun binary(operation: String, resourceId: String?, vararg args: String) =
        (runner as? BinaryCommandRunner)?.runBinary(listOf(executable, "-s", deviceSerial, *args))
            ?: run {
                val result = run(operation, resourceId, *args)
                dev.lynx.adb.BinaryCommandResult(result.exitCode, result.stdout.toByteArray(Charsets.ISO_8859_1), result.stderr)
            }

    private fun runRaw(operation: String, resourceId: String?, vararg args: String) =
        try { runner.run(listOf(executable, "-s", deviceSerial, *args)) }
        catch (error: Exception) { throw DatabaseException("DATABASE_ACCESS_FAILED", operation, error.message ?: "ADB database operation failed", retryable = true, resourceId = resourceId, cause = error) }

    private fun isMissingSqliteCli(stderr: String): Boolean {
        val text = stderr.lowercase()
        return "not found" in text || "no such file" in text || "unknown command" in text
    }

    private fun accessFailure(operation: String, resourceId: String, message: String): DatabaseException =
        DatabaseException("DATABASE_ACCESS_FAILED", operation, message.ifBlank { "ADB database operation failed" }, resourceId = resourceId)

    private fun hasDatabaseExtension(path: String): Boolean =
        path.endsWith(".db", ignoreCase = true) || path.endsWith(".sqlite", ignoreCase = true) || path.endsWith(".sqlite3", ignoreCase = true)

    private fun hasSqliteHeader(path: String): Boolean =
        run("db.list", path, "exec-out", "run-as", packageName, "head", "-c", "16", path).stdout.startsWith("SQLite format 3")

    private fun size(path: String, operation: String): Long {
        val value = run(operation, path, "shell", "run-as", packageName, "stat", "-c", "%s", path).stdout.trim()
        return value.toLongOrNull() ?: throw DatabaseException(
            code = "DATABASE_METADATA_INVALID",
            operation = operation,
            message = "Invalid size returned for $path",
            resourceId = path,
        )
    }

    private fun sidecar(files: Set<String>, path: String): DatabaseSidecarState =
        if (path in files) DatabaseSidecarState.present(size(path, "db.list")) else DatabaseSidecarState.absent()

    private fun run(operation: String, resourceId: String?, vararg args: String) =
        try {
            runner.run(listOf(executable, "-s", deviceSerial, *args)).also { result ->
                if (result.exitCode != 0) {
                    throw DatabaseException(
                        code = if (operation == "db.list") "DATABASE_DISCOVERY_FAILED" else "DATABASE_ACCESS_FAILED",
                        operation = operation,
                        message = result.stderr.ifBlank { "ADB command failed for ${resourceId ?: packageName}" }.trim(),
                        retryable = false,
                        resourceId = resourceId,
                    )
                }
            }
        } catch (error: DatabaseException) {
            throw error
        } catch (error: Exception) {
            throw DatabaseException(
                code = if (operation == "db.list") "DATABASE_DISCOVERY_FAILED" else "DATABASE_ACCESS_FAILED",
                operation = operation,
                message = error.message ?: "ADB database operation failed",
                retryable = true,
                resourceId = resourceId,
                cause = error,
            )
        }
}

private object AdbExecutablePath {
    fun resolve(): String = dev.lynx.adb.AdbExecutableResolver.resolve()
}
