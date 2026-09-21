package dev.lynx.database

import dev.lynx.model.DatabaseColumn
import dev.lynx.model.DatabaseQueryResponse
import dev.lynx.model.DatabaseResultSet
import dev.lynx.model.DatabaseSchemaObject
import dev.lynx.model.DatabaseSchemaResponse
import dev.lynx.model.DatabaseSnapshot
import dev.lynx.model.DatabaseTable
import dev.lynx.model.DatabaseTablesResponse
import dev.lynx.model.DatabaseValue
import org.sqlite.SQLiteConfig
import org.sqlite.SQLiteConnection
import org.sqlite.SQLiteOpenMode
import java.nio.file.Path
import java.sql.Connection
import java.sql.ResultSet
import java.sql.SQLException
import java.time.Duration
import java.util.Base64
import java.util.concurrent.ExecutionException
import java.util.concurrent.Executors
import java.util.concurrent.TimeUnit
import java.util.concurrent.TimeoutException
import java.util.concurrent.atomic.AtomicReference

val DEFAULT_DATABASE_QUERY_TIMEOUT: Duration = Duration.ofMinutes(15)

class SqliteDatabaseInspector(
    private val snapshots: SessionSnapshotRegistry,
    private val queryTimeout: Duration = DEFAULT_DATABASE_QUERY_TIMEOUT,
) : DatabaseInspector {
    override fun tables(target: QueryTarget): DatabaseTablesResponse {
        val snapshot = snapshot(target, "db.tables")
        val result = execute(
            snapshot,
            "SELECT name, type FROM sqlite_schema WHERE type IN ('table', 'view') AND name NOT LIKE 'sqlite_%' ORDER BY name",
            "db.tables",
        ).single()
        return DatabaseTablesResponse(
            sessionId = target.sessionId,
            snapshotId = snapshot.snapshotId,
            databaseId = snapshot.databaseId,
            tables = result.rows.map { row ->
                DatabaseTable(
                    name = (row[0] as DatabaseValue.TextValue).value,
                    kind = (row[1] as DatabaseValue.TextValue).value,
                )
            },
        )
    }

    override fun schema(target: QueryTarget): DatabaseSchemaResponse {
        val snapshot = snapshot(target, "db.schema")
        val result = execute(
            snapshot,
            "SELECT type, name, tbl_name, sql FROM sqlite_schema WHERE name NOT LIKE 'sqlite_%' ORDER BY type, name",
            "db.schema",
        ).single()
        return DatabaseSchemaResponse(
            sessionId = target.sessionId,
            snapshotId = snapshot.snapshotId,
            databaseId = snapshot.databaseId,
            objects = result.rows.map { row ->
                DatabaseSchemaObject(
                    type = (row[0] as DatabaseValue.TextValue).value,
                    name = (row[1] as DatabaseValue.TextValue).value,
                    tableName = (row[2] as DatabaseValue.TextValue).value,
                    sql = (row[3] as? DatabaseValue.TextValue)?.value,
                )
            },
        )
    }

    override fun query(target: QueryTarget, sql: String): DatabaseQueryResponse {
        val snapshot = snapshot(target, "db.query")
        val started = System.nanoTime()
        val results = execute(snapshot, sql, "db.query")
        return DatabaseQueryResponse(
            sessionId = target.sessionId,
            snapshotId = snapshot.snapshotId,
            databaseId = snapshot.databaseId,
            results = results,
            elapsedMillis = TimeUnit.NANOSECONDS.toMillis(System.nanoTime() - started),
        )
    }

    private fun snapshot(target: QueryTarget, operation: String): DatabaseSnapshot = when (target) {
        is QueryTarget.Snapshot -> snapshots.resolve(target.sessionId, target.snapshotId, operation)
        is QueryTarget.Live -> throw DatabaseException(
            code = "LIVE_DATABASE_QUERY_UNAVAILABLE",
            operation = operation,
            message = "Live database ${target.databaseId.value} must be snapshotted before host-side inspection",
            resourceId = target.databaseId.value,
        )
    }

    private fun execute(snapshot: DatabaseSnapshot, sql: String, operation: String): List<DatabaseResultSet> {
        val statements = ReadOnlySqlPolicy.statements(sql, operation)
        if (queryTimeout.isZero || queryTimeout.isNegative) throw timeout(operation, snapshot)

        val connection = AtomicReference<SQLiteConnection?>()
        val executor = Executors.newSingleThreadExecutor { task ->
            Thread(task, "lynx-sqlite-query").apply { isDaemon = true }
        }
        val future = executor.submit<List<DatabaseResultSet>> {
            openReadOnly(Path.of(snapshot.localPath)).use { opened ->
                connection.set(opened)
                opened.createStatement().use { it.execute("PRAGMA query_only = ON") }
                statements.mapIndexed { index, statement -> executeStatement(opened, statement, index) }
            }
        }
        return try {
            future.get(queryTimeout.toMillis(), TimeUnit.MILLISECONDS)
        } catch (_: TimeoutException) {
            connection.get()?.database?.interrupt()
            future.cancel(true)
            throw timeout(operation, snapshot)
        } catch (error: ExecutionException) {
            val cause = error.cause
            if (cause is DatabaseException) throw cause
            throw DatabaseException(
                code = "SQL_EXECUTION_ERROR",
                operation = operation,
                message = cause?.message ?: "SQLite query failed",
                resourceId = snapshot.snapshotId.value,
                cause = cause,
            )
        } catch (error: InterruptedException) {
            Thread.currentThread().interrupt()
            connection.get()?.database?.interrupt()
            throw DatabaseException(
                code = "QUERY_INTERRUPTED",
                operation = operation,
                message = "SQLite query was interrupted",
                retryable = true,
                resourceId = snapshot.snapshotId.value,
                cause = error,
            )
        } finally {
            executor.shutdownNow()
        }
    }

    private fun executeStatement(connection: Connection, sql: String, statementIndex: Int): DatabaseResultSet =
        try {
            connection.createStatement().use { statement ->
                if (!statement.execute(sql)) {
                    throw DatabaseException(
                        code = "SQL_READ_ONLY_VIOLATION",
                        operation = "db.query",
                        message = "Read-only statement did not produce a result set",
                    )
                }
                statement.resultSet.use { result -> result.toDatabaseResult(statementIndex) }
            }
        } catch (error: DatabaseException) {
            throw error
        } catch (error: SQLException) {
            throw DatabaseException(
                code = "SQL_EXECUTION_ERROR",
                operation = "db.query",
                message = error.message ?: "SQLite statement failed",
                cause = error,
            )
        }

    private fun ResultSet.toDatabaseResult(statementIndex: Int): DatabaseResultSet {
        val metadata = metaData
        val columns = (1..metadata.columnCount).map { index ->
            DatabaseColumn(metadata.getColumnLabel(index), metadata.getColumnTypeName(index).takeIf(String::isNotBlank))
        }
        val rows = buildList {
            while (next()) {
                add((1..metadata.columnCount).map { index -> value(index) })
            }
        }
        return DatabaseResultSet(statementIndex, columns, rows)
    }

    private fun ResultSet.value(index: Int): DatabaseValue = when (val value = getObject(index)) {
        null -> DatabaseValue.NullValue
        is ByteArray -> DatabaseValue.BlobValue(Base64.getEncoder().encodeToString(value))
        is Float -> DatabaseValue.RealValue(value.toDouble())
        is Double -> DatabaseValue.RealValue(value)
        is Number -> DatabaseValue.IntegerValue(value.toLong())
        else -> DatabaseValue.TextValue(value.toString())
    }

    private fun openReadOnly(path: Path): SQLiteConnection {
        val config = SQLiteConfig().apply {
            setReadOnly(true)
            setOpenMode(SQLiteOpenMode.READONLY)
            enableLoadExtension(false)
        }
        return config.createConnection("jdbc:sqlite:${path.toAbsolutePath()}") as SQLiteConnection
    }

    private fun timeout(operation: String, snapshot: DatabaseSnapshot) = DatabaseException(
        code = "QUERY_TIMEOUT",
        operation = operation,
        message = "SQLite query exceeded the ${queryTimeout.toMinutes()} minute deadline",
        retryable = true,
        resourceId = snapshot.snapshotId.value,
    )
}

private object ReadOnlySqlPolicy {
    private val forbidden = setOf(
        "INSERT", "UPDATE", "DELETE", "REPLACE", "CREATE", "ALTER", "DROP",
        "ATTACH", "DETACH", "VACUUM", "REINDEX", "ANALYZE", "BEGIN", "COMMIT",
        "ROLLBACK", "SAVEPOINT", "RELEASE", "END",
    )
    private val safePragma = Regex(
        """(?is)^\s*PRAGMA\s+(table_info|table_xinfo|index_list|index_info|index_xinfo|foreign_key_list|database_list|compile_options|integrity_check|quick_check)\b.*$""",
    )

    fun statements(sql: String, operation: String): List<String> {
        val statements = split(sql)
        if (statements.isEmpty()) throw DatabaseException("SQL_SYNTAX_ERROR", operation, "SQL query must contain a statement")
        statements.forEach { statement ->
            val tokens = tokens(statement)
            val allowedStart = tokens.firstOrNull() in setOf("SELECT", "WITH", "EXPLAIN") || safePragma.matches(statement)
            if (!allowedStart || tokens.any(forbidden::contains) || (tokens.firstOrNull() == "PRAGMA" && !safePragma.matches(statement))) {
                throw DatabaseException(
                    code = "SQL_READ_ONLY_VIOLATION",
                    operation = operation,
                    message = "Only read-only SQL statements are allowed",
                )
            }
        }
        return statements
    }

    private fun tokens(sql: String): List<String> {
        val unquoted = stripQuotedAndComments(sql)
        return Regex("[A-Za-z_]+").findAll(unquoted).map { it.value.uppercase() }.toList()
    }

    private fun split(sql: String): List<String> {
        val result = mutableListOf<String>()
        val current = StringBuilder()
        var index = 0
        var quote: Char? = null
        var lineComment = false
        var blockComment = false
        while (index < sql.length) {
            val char = sql[index]
            val next = sql.getOrNull(index + 1)
            when {
                lineComment -> {
                    current.append(char)
                    if (char == '\n') lineComment = false
                }
                blockComment -> {
                    current.append(char)
                    if (char == '*' && next == '/') {
                        current.append('/')
                        index++
                        blockComment = false
                    }
                }
                quote != null -> {
                    current.append(char)
                    if (char == quote) {
                        if (next == quote) {
                            current.append(next)
                            index++
                        } else quote = null
                    }
                }
                char == '-' && next == '-' -> {
                    current.append("--")
                    index++
                    lineComment = true
                }
                char == '/' && next == '*' -> {
                    current.append("/*")
                    index++
                    blockComment = true
                }
                char in charArrayOf('\'', '"', '`') -> {
                    quote = char
                    current.append(char)
                }
                char == '[' -> {
                    quote = ']'
                    current.append(char)
                }
                char == ';' -> {
                    current.toString().trim().takeIf(String::isNotEmpty)?.let(result::add)
                    current.clear()
                }
                else -> current.append(char)
            }
            index++
        }
        if (quote != null || blockComment) throw DatabaseException("SQL_SYNTAX_ERROR", "db.query", "Unterminated SQL quote or comment")
        current.toString().trim().takeIf(String::isNotEmpty)?.let(result::add)
        return result
    }

    private fun stripQuotedAndComments(sql: String): String {
        val output = StringBuilder(sql.length)
        var index = 0
        var quote: Char? = null
        var lineComment = false
        var blockComment = false
        while (index < sql.length) {
            val char = sql[index]
            val next = sql.getOrNull(index + 1)
            when {
                lineComment -> if (char == '\n') { lineComment = false; output.append(' ') }
                blockComment -> if (char == '*' && next == '/') { blockComment = false; index++; output.append(' ') }
                quote != null -> if (char == quote) {
                    if (next == quote) index++ else { quote = null; output.append(' ') }
                }
                char == '-' && next == '-' -> { lineComment = true; index++ }
                char == '/' && next == '*' -> { blockComment = true; index++ }
                char in charArrayOf('\'', '"', '`') -> { quote = char; output.append(' ') }
                char == '[' -> { quote = ']'; output.append(' ') }
                else -> output.append(char)
            }
            index++
        }
        return output.toString()
    }
}
