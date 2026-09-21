package dev.lynx.daemon

import com.google.gson.Gson
import com.google.gson.GsonBuilder
import com.google.gson.JsonSerializer
import dev.lynx.model.DatabaseValue
import com.google.gson.JsonObject
import com.google.gson.JsonParser
import com.google.gson.JsonPrimitive
import kotlin.time.Instant

data class DaemonRequest(
    val protocolVersion: Int,
    val schemaVersion: String,
    val requestId: String,
    val command: String,
    val arguments: JsonObject,
)

/** Versioned JSONL envelope. Text commands remain supported by DaemonService. */
object DaemonProtocol {
    const val PROTOCOL_VERSION = 1
    const val SCHEMA_VERSION = "lynx.v1"
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

    fun parseRequest(line: String): DaemonRequest {
        val json = JsonParser.parseString(line).asJsonObject
        require(line.isNotBlank()) { "request must not be blank" }
        require(json.get("arguments") == null || json.get("arguments").isJsonObject) { "arguments must be a JSON object" }
        fun required(name: String): String = json.get(name)?.asString?.trim()?.takeIf { it.isNotEmpty() }
            ?: throw IllegalArgumentException("$name must be a non-blank string")
        return DaemonRequest(
            json.get("protocol_version")?.asInt ?: throw IllegalArgumentException("protocol_version is required"),
            required("schema_version"),
            required("request_id"),
            canonicalCommand(required("command")),
            json.getAsJsonObject("arguments") ?: JsonObject(),
        )
    }

    fun handle(line: String, service: DaemonService): String {
        val request = try {
            parseRequest(line)
        } catch (error: Exception) {
            return errorResponse("", "INVALID_REQUEST", error.message ?: "invalid JSON request", false)
        }
        if (request.protocolVersion != PROTOCOL_VERSION) {
            return errorResponse(request.requestId, "UNSUPPORTED_PROTOCOL_VERSION", "Unsupported protocol version: ${request.protocolVersion}", false, request.command)
        }
        if (request.schemaVersion != SCHEMA_VERSION) {
            return errorResponse(request.requestId, "UNSUPPORTED_SCHEMA_VERSION", "Unsupported schema version: ${request.schemaVersion}", false, request.command)
        }
        val command = commandLine(request)
        val text = if (request.command in setOf("DB_QUERY", "DB_TABLES", "DB_SCHEMA", "DB_SNAPSHOT", "NETWORK_GET", "NETWORK_WATCH")) {
            service.handle(request.command, request.arguments)
        } else service.handle(command)
        return response(request, text)
    }

    fun internalError(error: Throwable): String = errorResponse(
        requestId = "",
        code = "INTERNAL_ERROR",
        message = error.message ?: "command failed",
        retryable = true,
    )

    private fun commandLine(request: DaemonRequest): String = when (request.command) {
        "ATTACH" -> "ATTACH ${request.arguments.string("device") ?: "-"} ${request.arguments.string("package") ?: ""}"
        "STATUS" -> request.arguments.string("session_id")?.let { "STATUS $it" } ?: "STATUS"
        "DETACH" -> request.arguments.string("session_id")?.let { "DETACH $it" } ?: "DETACH"
        else -> request.command
    }

    private fun response(request: DaemonRequest, text: String): String {
        val out = JsonObject()
        out.addProperty("protocol_version", PROTOCOL_VERSION)
        out.addProperty("schema_version", SCHEMA_VERSION)
        out.addProperty("request_id", request.requestId)
        out.addProperty("command", request.command)
        out.add("arguments", request.arguments)
        if (text.startsWith("ERROR ")) {
            val parts = text.split(" ", limit = 3)
            val code = parts.getOrNull(1) ?: "INTERNAL_ERROR"
            out.addProperty("type", "error")
            out.addProperty("code", code)
            out.addProperty("message", parts.getOrNull(2) ?: code)
            out.addProperty("operation", request.command)
            out.addProperty("retryable", code in setOf("NOT_ATTACHED", "NETWORK_NOT_RUNNING", "QUERY_TIMEOUT", "PROCESS_LOST", "STORAGE_EXHAUSTED"))
        } else {
            out.addProperty("type", responseType(request.command, text))
            if (text.startsWith("OK_JSON ")) {
                out.add("payload", JsonParser.parseString(text.removePrefix("OK_JSON ")))
            } else out.addProperty("message", text)
            out.addProperty("ok", true)
        }
        return gson.toJson(out)
    }

    private fun responseType(command: String, text: String): String = when {
        command == "PING" -> "pong"
        command == "ATTACH" -> "attached"
        command == "STATUS" -> "status"
        command == "DETACH" -> "detached"
        else -> command.lowercase()
    }

    private fun canonicalCommand(command: String): String = when (command.trim().uppercase()) {
        "ATTACH", "STATUS", "DETACH", "PING", "NETWORK_START", "NETWORK_STOP", "NETWORK_LIST", "DB_LIST", "DB_SNAPSHOT" -> command.trim().uppercase()
        else -> command.trim().uppercase()
    }

    private fun errorResponse(requestId: String, code: String, message: String, retryable: Boolean, operation: String = "unknown"): String {
        val out = JsonObject()
        out.addProperty("protocol_version", PROTOCOL_VERSION)
        out.addProperty("schema_version", SCHEMA_VERSION)
        out.addProperty("request_id", requestId)
        out.addProperty("type", "error")
        out.addProperty("code", code)
        out.addProperty("message", message)
        out.addProperty("operation", operation)
        out.addProperty("retryable", retryable)
        return gson.toJson(out)
    }

    private fun JsonObject.string(name: String): String? = get(name)?.takeUnless { it.isJsonNull }?.asString
}
