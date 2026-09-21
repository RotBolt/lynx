package dev.lynx.model

import kotlinx.serialization.SerialName
import kotlinx.serialization.Serializable
import kotlinx.serialization.json.JsonClassDiscriminator

@OptIn(kotlinx.serialization.ExperimentalSerializationApi::class)
@Serializable
@JsonClassDiscriminator("kind")
sealed interface DatabaseValue {
    @Serializable
    @SerialName("null")
    data object NullValue : DatabaseValue

    @Serializable
    @SerialName("integer")
    data class IntegerValue(val value: Long) : DatabaseValue

    @Serializable
    @SerialName("real")
    data class RealValue(val value: Double) : DatabaseValue

    @Serializable
    @SerialName("text")
    data class TextValue(val value: String) : DatabaseValue

    @Serializable
    @SerialName("blob")
    data class BlobValue(
        val data: String,
        val encoding: String = "base64",
    ) : DatabaseValue
}

data class DatabaseColumn(
    val name: String,
    val declaredType: String?,
)

data class DatabaseResultSet(
    val statementIndex: Int,
    val columns: List<DatabaseColumn>,
    val rows: List<List<DatabaseValue>>,
)

data class DatabaseQueryResponse(
    val type: String = "database_query",
    val schemaVersion: String = "lynx.v1",
    val sessionId: SessionId,
    val snapshotId: SnapshotId?,
    val databaseId: DatabaseId,
    val readOnly: Boolean = true,
    val results: List<DatabaseResultSet>,
    val elapsedMillis: Long,
    val truncated: Boolean = false,
)

data class DatabaseTable(
    val name: String,
    val kind: String,
)

data class DatabaseTablesResponse(
    val type: String = "database_tables",
    val schemaVersion: String = "lynx.v1",
    val sessionId: SessionId,
    val snapshotId: SnapshotId?,
    val databaseId: DatabaseId,
    val tables: List<DatabaseTable>,
)

data class DatabaseSchemaObject(
    val type: String,
    val name: String,
    val tableName: String,
    val sql: String?,
)

data class DatabaseSchemaResponse(
    val type: String = "database_schema",
    val schemaVersion: String = "lynx.v1",
    val sessionId: SessionId,
    val snapshotId: SnapshotId?,
    val databaseId: DatabaseId,
    val objects: List<DatabaseSchemaObject>,
)
