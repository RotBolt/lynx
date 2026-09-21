package dev.lynx.database

import dev.lynx.model.DatabaseFingerprint
import dev.lynx.model.DatabaseId
import dev.lynx.model.DatabaseSnapshot
import dev.lynx.model.DatabaseValue
import dev.lynx.model.EvidenceId
import dev.lynx.model.EvidenceMeta
import dev.lynx.model.EvidenceSource
import dev.lynx.model.SessionId
import dev.lynx.model.SnapshotId
import java.nio.file.Files
import java.nio.file.Path
import java.sql.DriverManager
import java.time.Duration
import kotlinx.datetime.Instant
import kotlin.io.path.deleteIfExists
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFailsWith
import kotlin.test.assertTrue

class SqliteDatabaseInspectorTest {
    @Test
    fun returnsTablesSchemaAndEverySQLiteValueInStructuredForm() = withDatabase { path ->
        DriverManager.getConnection("jdbc:sqlite:$path").use { connection ->
            connection.createStatement().use { statement ->
                statement.execute("CREATE TABLE evidence(id INTEGER, score REAL, note TEXT, payload BLOB, missing TEXT)")
            }
            connection.prepareStatement("INSERT INTO evidence VALUES (?, ?, ?, ?, ?)").use { statement ->
                statement.setLong(1, 7)
                statement.setDouble(2, 2.5)
                statement.setString(3, "complete")
                statement.setBytes(4, byteArrayOf(0, 1, 2, -1))
                statement.setNull(5, java.sql.Types.NULL)
                statement.executeUpdate()
            }
        }
        val (inspector, target) = inspector(path)

        val tables = inspector.tables(target)
        val schema = inspector.schema(target)
        val result = inspector.query(target, "SELECT id, score, note, payload, missing FROM evidence")

        assertEquals(listOf("evidence"), tables.tables.map { it.name })
        assertTrue(schema.objects.single { it.name == "evidence" }.sql!!.startsWith("CREATE TABLE evidence"))
        assertEquals(
            listOf(
                DatabaseValue.IntegerValue(7),
                DatabaseValue.RealValue(2.5),
                DatabaseValue.TextValue("complete"),
                DatabaseValue.BlobValue("AAEC/w=="),
                DatabaseValue.NullValue,
            ),
            result.results.single().rows.single(),
        )
        assertEquals(listOf("INTEGER", "REAL", "TEXT", "BLOB", "TEXT"), result.results.single().columns.map { it.declaredType })
        assertEquals(false, result.truncated)
        assertEquals(true, result.readOnly)
    }

    @Test
    fun returnsAllResultSetsForMultipleReadOnlyStatements() = withDatabase { path ->
        createNumbers(path, 3)
        val (inspector, target) = inspector(path)

        val response = inspector.query(target, "SELECT value FROM numbers ORDER BY value; SELECT count(*) AS total FROM numbers;")

        assertEquals(2, response.results.size)
        assertEquals(listOf(1L, 2L, 3L), response.results[0].rows.map { (it.single() as DatabaseValue.IntegerValue).value })
        assertEquals(3, (response.results[1].rows.single().single() as DatabaseValue.IntegerValue).value)
    }

    @Test
    fun rejectsEveryMutationClassBeforeExecution() = withDatabase { path ->
        createNumbers(path, 1)
        val (inspector, target) = inspector(path)
        val forbidden = listOf(
            "INSERT INTO numbers VALUES (2)",
            "UPDATE numbers SET value = 2",
            "DELETE FROM numbers",
            "CREATE TABLE changed(id INTEGER)",
            "ALTER TABLE numbers ADD COLUMN changed INTEGER",
            "DROP TABLE numbers",
            "ATTACH DATABASE ':memory:' AS other",
            "DETACH DATABASE main",
            "PRAGMA user_version = 2",
            "BEGIN TRANSACTION",
        )

        forbidden.forEach { sql ->
            val error = assertFailsWith<DatabaseException>(sql) { inspector.query(target, sql) }
            assertEquals("SQL_READ_ONLY_VIOLATION", error.code, sql)
            assertEquals("db.query", error.operation, sql)
        }
        assertEquals(1, inspector.query(target, "SELECT count(*) FROM numbers").results.single().rows.size)
    }

    @Test
    fun rejectsBatchWhenAnyStatementMutates() = withDatabase { path ->
        createNumbers(path, 1)
        val (inspector, target) = inspector(path)

        val error = assertFailsWith<DatabaseException> {
            inspector.query(target, "SELECT * FROM numbers; DELETE FROM numbers; SELECT * FROM numbers")
        }

        assertEquals("SQL_READ_ONLY_VIOLATION", error.code)
        val remaining = inspector.query(target, "SELECT count(*) FROM numbers")
        assertEquals(1, (remaining.results.single().rows.single().single() as DatabaseValue.IntegerValue).value)
    }

    @Test
    fun invalidatesSnapshotQueriesWhenOwningSessionDetaches() = withDatabase { path ->
        val registry = SessionSnapshotRegistry()
        val snapshot = snapshot(path)
        registry.register(snapshot)
        val inspector = SqliteDatabaseInspector(registry)
        val target = QueryTarget.Snapshot(SessionId("session-1"), SnapshotId("snapshot-1"))
        registry.detach(SessionId("session-1"))

        val error = assertFailsWith<DatabaseException> { inspector.tables(target) }

        assertEquals("SESSION_DETACHED", error.code)
        assertEquals("db.tables", error.operation)
    }

    @Test
    fun returnsCompleteLargeResultWithoutSemanticTruncation() = withDatabase { path ->
        createNumbers(path, 2_000)
        val (inspector, target) = inspector(path)

        val response = inspector.query(target, "SELECT value FROM numbers ORDER BY value")

        assertEquals(2_000, response.results.single().rows.size)
        assertEquals(2_000, (response.results.single().rows.last().single() as DatabaseValue.IntegerValue).value)
        assertEquals(false, response.truncated)
    }

    @Test
    fun interruptsQueriesThatExceedTheConfiguredDeadline() = withDatabase { path ->
        val registry = SessionSnapshotRegistry().also { it.register(snapshot(path)) }
        val inspector = SqliteDatabaseInspector(registry, queryTimeout = Duration.ZERO)
        val target = QueryTarget.Snapshot(SessionId("session-1"), SnapshotId("snapshot-1"))

        val error = assertFailsWith<DatabaseException> {
            inspector.query(target, "WITH RECURSIVE values_(n) AS (SELECT 1 UNION ALL SELECT n + 1 FROM values_ WHERE n < 1000000) SELECT sum(n) FROM values_")
        }

        assertEquals("QUERY_TIMEOUT", error.code)
        assertEquals(true, error.retryable)
    }

    private fun inspector(path: Path): Pair<SqliteDatabaseInspector, QueryTarget.Snapshot> {
        val registry = SessionSnapshotRegistry().also { it.register(snapshot(path)) }
        return SqliteDatabaseInspector(registry) to QueryTarget.Snapshot(SessionId("session-1"), SnapshotId("snapshot-1"))
    }

    private fun snapshot(path: Path) = DatabaseSnapshot(
        meta = EvidenceMeta(EvidenceId("evidence-1"), SessionId("session-1"), Instant.fromEpochMilliseconds(0), EvidenceSource.DATABASE, "emulator-5554", "com.example.app", 123),
        snapshotId = SnapshotId("snapshot-1"),
        databaseId = DatabaseId("databases/app.db"),
        sourceFingerprint = DatabaseFingerprint("fingerprint", listOf("databases/app.db")),
        localPath = path.toString(),
        consistent = true,
        consistencyMethod = "test fixture",
    )

    private fun createNumbers(path: Path, count: Int) {
        DriverManager.getConnection("jdbc:sqlite:$path").use { connection ->
            connection.createStatement().use { it.execute("CREATE TABLE numbers(value INTEGER)") }
            connection.prepareStatement("INSERT INTO numbers VALUES (?)").use { statement ->
                (1..count).forEach { value ->
                    statement.setInt(1, value)
                    statement.addBatch()
                }
                statement.executeBatch()
            }
        }
    }

    private inline fun withDatabase(block: (Path) -> Unit) {
        val path = Files.createTempFile("lynx-inspector", ".db")
        try {
            block(path)
        } finally {
            path.deleteIfExists()
        }
    }
}
