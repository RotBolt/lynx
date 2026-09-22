package dev.lynx.daemon

import dev.lynx.model.SessionId
import dev.lynx.model.Evidence
import dev.lynx.model.EvidenceFilter
import dev.lynx.model.EvidenceTimeline
import dev.lynx.model.InMemoryEvidenceTimeline
import dev.lynx.session.SessionRegistry
import com.google.gson.JsonObject
import com.google.gson.GsonBuilder
import com.google.gson.JsonSerializer
import com.google.gson.JsonPrimitive
import dev.lynx.model.DatabaseValue
import kotlin.time.Instant

/**
 * Small line-oriented control surface for the future local Unix-socket daemon.
 * The transport is deliberately separate so it can be replaced without changing
 * session semantics.
 */
class DaemonService(
    private val sessions: SessionRegistry = SessionRegistry(),
    private val timeline: EvidenceTimeline = InMemoryEvidenceTimeline(),
    private val networkFactory: NetworkSourceFactory? = null,
    private val databaseFactory: DatabaseSourceFactory? = null,
    private val targetResolver: TargetResolver = AdbTargetResolver(),
) {
    private val gson = GsonBuilder()
        .registerTypeAdapter(Instant::class.java, JsonSerializer<Instant> { value, _, _ -> JsonPrimitive(value.toString()) })
        .registerTypeHierarchyAdapter(DatabaseValue::class.java, JsonSerializer<DatabaseValue> { value, _, _ ->
        JsonObject().apply {
            when (value) {
                DatabaseValue.NullValue -> addProperty("kind", "null")
                is DatabaseValue.IntegerValue -> { addProperty("kind", "integer"); addProperty("value", value.value) }
                is DatabaseValue.RealValue -> { addProperty("kind", "real"); addProperty("value", value.value) }
                is DatabaseValue.TextValue -> { addProperty("kind", "text"); addProperty("value", value.value) }
                is DatabaseValue.BlobValue -> { addProperty("kind", "blob"); addProperty("value", value.data); addProperty("encoding", value.encoding) }
            }
        }
    }).create()
    private var activeTarget: dev.lynx.adb.ResolvedTarget? = null
    private var networkSource: NetworkCaptureSource? = null
    private var databaseSource: DatabaseSource? = null
    private var detached = false
    fun evidence(filter: EvidenceFilter = EvidenceFilter()): List<Evidence> = timeline.query(filter)

    fun handle(command: String): String {
        val parts = command.trim().split(Regex("\\s+"))
        return when (parts.firstOrNull()) {
            "ATTACH" -> attach(parts)
            "STATUS" -> status(parts)
            "DETACH" -> detach(parts)
            "PING" -> "OK PONG"
            "NETWORK_START" -> networkStart()
            "NETWORK_STOP" -> networkStop()
            "NETWORK_LIST" -> networkList()
            "NETWORK_GET" -> networkGet(parts.getOrNull(1))
            "NETWORK_WATCH" -> networkList()
            "NETWORK_DOCTOR" -> networkDoctor()
            "DB_LIST" -> dbList()
            "DB_SNAPSHOT" -> dbSnapshot(parts)
            "DB_TABLES" -> dbTables(parts)
            "DB_SCHEMA" -> dbSchema(parts.getOrNull(1))
            else -> "ERROR INVALID_COMMAND"
        }
    }

    fun handle(command: String, arguments: JsonObject): String = when (command.uppercase()) {
        "NETWORK_GET" -> networkGet(arguments.get("request_id")?.asString)
        "NETWORK_WATCH" -> networkList()
        "NETWORK_DOCTOR" -> networkDoctor()
        "DB_QUERY" -> dbQuery(arguments.get("snapshot_id")?.asString, arguments.get("sql")?.asString)
        "DB_TABLES" -> dbTables(arguments.get("snapshot_id")?.asString)
        "DB_SCHEMA" -> dbSchema(arguments.get("snapshot_id")?.asString)
        "DB_SNAPSHOT" -> dbSnapshot(arguments.get("database_id")?.asString?.let { listOf("DB_SNAPSHOT", it) } ?: emptyList())
        else -> handle(command)
    }

    private fun networkGet(requestId: String?): String {
        if (requestId.isNullOrBlank()) return "ERROR INVALID_ARGUMENTS"
        val exchange = networkSource?.exchange(dev.lynx.model.RequestId(requestId))
            ?: timeline.network().firstOrNull { it.requestId.value == requestId }
            ?: return "ERROR REQUEST_NOT_FOUND"
        return "OK_JSON ${gson.toJson(exchange)}"
    }

    private fun networkList(): String {
        val exchanges = networkSource?.exchanges() ?: timeline.network()
        return "OK_JSON ${gson.toJson(exchanges)}"
    }

    private fun networkDoctor(): String {
        val source = networkSource ?: return "ERROR NETWORK_NOT_RUNNING"
        return "OK_JSON ${gson.toJson(kotlinx.coroutines.runBlocking { source.capabilities() })}"
    }

    private fun attach(parts: List<String>): String {
        if (parts.size != 3) return "ERROR INVALID_ARGUMENTS"
        return runCatching {
            val target = targetResolver.resolve(parts[1].takeUnless { it == "-" }, parts[2])
            activeTarget = target
            detached = false
            // Re-attaching the same device/package is a reconnect operation,
            // not a new logical session. This keeps the agent-facing session
            // identity stable when an app process is restarted.
            val existing = sessions.activeSessions().singleOrNull {
                it.deviceSerial == target.deviceSerial && it.packageName == target.packageName
            }
            val session = if (existing != null) {
                if (existing.pid != target.pid) {
                    sessions.rebind(existing.id, target.pid)
                    invalidateProcessBoundSources()
                }
                sessions.get(existing.id) ?: existing
            } else {
                sessions.attach(
                    deviceSerial = target.deviceSerial,
                    packageName = target.packageName,
                    pid = target.pid,
                    protocolVersion = 1,
                )
            }
            "OK ATTACHED id=${session.id.value} device=${session.deviceSerial} package=${session.packageName} pid=${session.pid}"
        }.getOrElse { error ->
            if (error is dev.lynx.adb.AdbException) "ERROR ${error.code} ${error.message}" else "ERROR INVALID_ARGUMENTS"
        }
    }

    private fun networkStart(): String {
        val target = liveTarget() ?: return lastLifecycleError
        val factory = networkFactory ?: return "ERROR NETWORK_UNAVAILABLE"
        val source = factory.create(target, timeline)
        kotlinx.coroutines.runBlocking { source.start(NetworkCaptureConfig()) }
        networkSource = source
        val capabilities = kotlinx.coroutines.runBlocking { source.capabilities() }
        return "OK NETWORK_STARTED endpoint=${source.endpoint ?: "unknown"} ca=${source.caCertificatePath ?: "unavailable"} fingerprint=${capabilities.caFingerprint ?: "unavailable"} trust=${capabilities.caTrustStatus ?: "unknown"}"
    }

    private fun networkStop(): String {
        val source = networkSource ?: return "ERROR NETWORK_NOT_RUNNING"
        kotlinx.coroutines.runBlocking { source.stop() }
        networkSource = null
        return "OK NETWORK_STOPPED"
    }

    private fun dbList(): String {
        val target = liveTarget() ?: return lastLifecycleError
        val source = databaseSource ?: databaseFactory?.create(target)?.also { databaseSource = it } ?: return "ERROR DATABASE_UNAVAILABLE"
        val names = kotlinx.coroutines.runBlocking { source.discover() }.joinToString(",") { it.name }
        return "OK DB $names"
    }

    private fun dbSnapshot(parts: List<String>): String {
        if (parts.size != 2) return "ERROR INVALID_ARGUMENTS"
        val target = liveTarget() ?: return lastLifecycleError
        val source = databaseSource ?: databaseFactory?.create(target)?.also { databaseSource = it } ?: return "ERROR DATABASE_UNAVAILABLE"
        val snapshot = kotlinx.coroutines.runBlocking { source.snapshot(dev.lynx.model.DatabaseId(parts[1])) }
        timeline.append(snapshot)
        return "OK DB_SNAPSHOT id=${snapshot.snapshotId.value} path=${snapshot.localPath} consistent=${snapshot.consistent}"
    }

    private fun dbTables(parts: List<String>): String = dbTables(parts.getOrNull(1))
    private fun dbTables(snapshotId: String?): String {
        if (snapshotId.isNullOrBlank()) return "ERROR INVALID_ARGUMENTS"
        if (detached) return "ERROR SESSION_DETACHED Snapshot access ended with the attached session"
        val target = liveTarget() ?: return lastLifecycleError
        val source = databaseSource ?: databaseFactory?.create(target)?.also { databaseSource = it } ?: return "ERROR DATABASE_UNAVAILABLE"
        return runCatching { kotlinx.coroutines.runBlocking { source.tables(dev.lynx.model.SnapshotId(snapshotId)) } }
            .fold({ "OK_JSON ${gson.toJson(it)}" }, { "ERROR ${databaseErrorCode(it)} ${it.message}" })
    }

    private fun dbSchema(snapshotId: String?): String {
        if (snapshotId.isNullOrBlank()) return "ERROR INVALID_ARGUMENTS"
        if (detached) return "ERROR SESSION_DETACHED Snapshot access ended with the attached session"
        val target = liveTarget() ?: return lastLifecycleError
        val source = databaseSource ?: databaseFactory?.create(target)?.also { databaseSource = it } ?: return "ERROR DATABASE_UNAVAILABLE"
        return runCatching { kotlinx.coroutines.runBlocking { source.schema(dev.lynx.model.SnapshotId(snapshotId)) } }
            .fold({ "OK_JSON ${gson.toJson(it)}" }, { "ERROR ${databaseErrorCode(it)} ${it.message}" })
    }

    private fun dbQuery(snapshotId: String?, sql: String?): String {
        if (snapshotId.isNullOrBlank() || sql.isNullOrBlank()) return "ERROR INVALID_ARGUMENTS"
        if (detached) return "ERROR SESSION_DETACHED Snapshot access ended with the attached session"
        val target = liveTarget() ?: return lastLifecycleError
        val source = databaseSource ?: databaseFactory?.create(target)?.also { databaseSource = it } ?: return "ERROR DATABASE_UNAVAILABLE"
        return runCatching { kotlinx.coroutines.runBlocking { source.query(dev.lynx.model.SnapshotId(snapshotId), sql) } }
            .fold({ "OK_JSON ${gson.toJson(it)}" }, { "ERROR ${databaseErrorCode(it)} ${it.message}" })
    }

    private fun databaseErrorCode(error: Throwable): String = runCatching {
        error.javaClass.getMethod("getCode").invoke(error) as? String
    }.getOrNull()?.takeIf { it.isNotBlank() } ?: "DATABASE_QUERY_FAILED"

    private fun status(parts: List<String>): String {
        val session = when (parts.size) {
            1 -> sessions.activeSessions().singleOrNull()
            2 -> sessions.get(SessionId(parts[1]))
            else -> return "ERROR INVALID_ARGUMENTS"
        } ?: return "ERROR NOT_FOUND"
        val target = rebindIfNeeded(session) ?: return lastLifecycleError
        val liveSession = sessions.get(session.id) ?: session
        return "OK ${liveSession.status} id=${liveSession.id.value} device=${target.deviceSerial} package=${target.packageName} pid=${target.pid}"
    }

    private fun detach(parts: List<String>): String {
        val id = when (parts.size) {
            1 -> sessions.activeSessions().singleOrNull()?.id
            2 -> SessionId(parts[1])
            else -> return "ERROR INVALID_ARGUMENTS"
        } ?: return "ERROR NOT_FOUND"
        if (sessions.get(id) == null) return "ERROR NOT_FOUND"
        val cleanupError = networkSource?.let { source ->
            runCatching { kotlinx.coroutines.runBlocking { source.stop() } }.exceptionOrNull()
        }
        if (cleanupError != null) {
            return "ERROR NETWORK_CLEANUP_FAILED ${cleanupError.message ?: cleanupError::class.simpleName}"
        }
        networkSource = null
        sessions.detach(id)
        detached = true
        databaseSource?.detach()
        databaseSource = null
        return "OK DETACHED"
    }

    private var lastLifecycleError: String = "ERROR NOT_ATTACHED"

    /** Resolve the package again before an operation, rebinding a restarted app. */
    private fun liveTarget(): dev.lynx.adb.ResolvedTarget? {
        val session = sessions.activeSessions().singleOrNull()
        if (session == null) {
            lastLifecycleError = "ERROR NOT_ATTACHED"
            return null
        }
        return rebindIfNeeded(session)
    }

    private fun rebindIfNeeded(session: dev.lynx.model.InspectorSession): dev.lynx.adb.ResolvedTarget? {
        val target = runCatching {
            targetResolver.resolve(session.deviceSerial, session.packageName)
        }.getOrElse { error ->
            val detail = (error as? dev.lynx.adb.AdbException)?.message ?: error.message ?: "process is no longer available"
            lastLifecycleError = "ERROR PROCESS_LOST $detail"
            return null
        }
        if (target.pid != session.pid) {
            sessions.rebind(session.id, target.pid)
            invalidateProcessBoundSources()
        }
        activeTarget = target
        lastLifecycleError = "ERROR NOT_ATTACHED"
        return target
    }

    /** Sources carry the old PID and must not be reused after a process restart. */
    private fun invalidateProcessBoundSources() {
        networkSource?.let { kotlinx.coroutines.runBlocking { it.stop() } }
        networkSource = null
        databaseSource?.detach()
        databaseSource = null
    }
}
