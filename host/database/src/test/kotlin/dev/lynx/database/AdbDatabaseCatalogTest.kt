package dev.lynx.database

import dev.lynx.adb.CommandResult
import dev.lynx.adb.CommandRunner
import dev.lynx.adb.BinaryCommandResult
import dev.lynx.adb.BinaryCommandRunner
import dev.lynx.model.DatabaseId
import dev.lynx.model.SessionId
import kotlinx.coroutines.runBlocking
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFailsWith
import kotlin.test.assertFalse
import kotlin.test.assertTrue
import java.nio.file.Files
import java.nio.file.Path

class AdbDatabaseCatalogTest {
    @Test
    fun usesSqliteOnlineBackupAndMarksSnapshotConsistent() = runBlocking {
        val directory = Files.createTempDirectory("lynx-snapshot-test")
        try {
            val runner = BackupRunner()
            val source = AdbDatabaseSource(
                SessionId("session-1"), "emulator-5554", "com.example.app", 123,
                runner = runner, executable = "adb", outputDirectory = directory,
            )

            val snapshot = source.snapshot(DatabaseId("databases/app.db"))

            assertTrue(snapshot.consistent)
            assertTrue(snapshot.consistencyMethod.contains("backup streamed"))
            assertTrue(Files.readAllBytes(Path.of(snapshot.localPath)).copyOfRange(0, 16).contentEquals("SQLite format 3\u0000".toByteArray()))
            assertTrue(runner.commands.flatten().any { it.contains(".backup /dev/stdout") })
        } finally {
            directory.toFile().deleteRecursively()
        }
    }

    @Test
    fun discoversValidatedDatabasesAndAssociatesWalSidecars() = runBlocking {
        val runner = RecordingRunner(
            CommandResult(0, "databases/conversation.db\ndatabases/conversation.db-wal\ndatabases/conversation.db-shm\ndatabases/not-a-database.db\ndatabases/cache.sqlite\n", ""),
            CommandResult(0, "SQLite format 3\u0000", ""),
            CommandResult(0, "4096\n", ""),
            CommandResult(0, "512\n", ""),
            CommandResult(0, "128\n", ""),
            CommandResult(0, "not sqlite", ""),
            CommandResult(0, "SQLite format 3\u0000", ""),
            CommandResult(0, "2048\n", ""),
        )

        val databases = source(runner).discover()

        assertEquals(listOf(DatabaseId("databases/cache.sqlite"), DatabaseId("databases/conversation.db")), databases.map { it.databaseId })
        val conversation = databases.single { it.name == "conversation.db" }
        assertEquals("databases/conversation.db", conversation.relativePath)
        assertEquals(4096, conversation.sizeBytes)
        assertTrue(conversation.wal.present)
        assertEquals(512, conversation.wal.sizeBytes)
        assertTrue(conversation.shm.present)
        assertEquals(128, conversation.shm.sizeBytes)
        val cache = databases.single { it.name == "cache.sqlite" }
        assertFalse(cache.wal.present)
        assertFalse(cache.shm.present)
    }

    @Test
    fun reportsRunAsDiscoveryFailureAsStructuredDatabaseError() {
        val error = assertFailsWith<DatabaseException> {
            runBlocking { source(RecordingRunner(CommandResult(1, "", "run-as: package not debuggable"))).discover() }
        }

        assertEquals("DATABASE_DISCOVERY_FAILED", error.code)
        assertEquals("db.list", error.operation)
        assertEquals(false, error.retryable)
    }

    private fun source(runner: CommandRunner) = AdbDatabaseSource(
        sessionId = SessionId("session-1"),
        deviceSerial = "emulator-5554",
        packageName = "com.example.app",
        processId = 123,
        runner = runner,
        executable = "adb",
    )
}

private class BackupRunner : BinaryCommandRunner {
    val commands = mutableListOf<List<String>>()

    override fun run(arguments: List<String>): CommandResult {
        commands += arguments
        return when {
            arguments.contains("sha256sum") -> CommandResult(0, "abc123  databases/app.db\n", "")
            arguments.contains("sqlite3") -> CommandResult(0, "", "")
            else -> CommandResult(0, "", "")
        }
    }

    override fun runBinary(arguments: List<String>): BinaryCommandResult {
        commands += arguments
        return BinaryCommandResult(0, "SQLite format 3\u0000".toByteArray() + byteArrayOf(1, 2, 3, 4), "")
    }
}

private class RecordingRunner(vararg results: CommandResult) : CommandRunner {
    private val results = ArrayDeque(results.toList())

    override fun run(arguments: List<String>): CommandResult = results.removeFirst()
}
