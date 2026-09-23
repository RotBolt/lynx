package dev.lynx.nativehost

import dev.lynx.model.CaptureSession
import dev.lynx.model.CaptureState
import kotlinx.cinterop.ExperimentalForeignApi
import kotlinx.cinterop.toKString
import kotlinx.serialization.Serializable
import kotlinx.serialization.json.Json
import platform.posix.EOF
import platform.posix.fclose
import platform.posix.fgetc
import platform.posix.fopen
import platform.posix.fputs
import platform.posix.getenv
import platform.posix.getpid
import platform.posix.remove
import platform.posix.rename

@Serializable
private data class StoredExchange(val sequence: Long, val exchange: VerifiedExchangeSurrogate)

/** Serialization surrogate keeps capture data independent from legacy evidence JSONL. */
@Serializable
private data class VerifiedExchangeSurrogate(
    val captureId: String,
    val connectionId: String,
    val acceptedAtEpochMillis: Long,
    val originPlatform: String,
    val originDeviceId: String,
    val originApplicationId: String,
    val originAndroidUserId: Int?,
    val originPid: Int,
    val originStartIdentity: String,
    val originUid: Int?,
    val originMethod: String,
    val exchangeJson: String,
)

@OptIn(ExperimentalForeignApi::class)
class PosixCaptureRepository(
    private val root: String = (getenv("HOME")?.toKString()?.takeIf(String::isNotBlank) ?: "/tmp") + "/.lynx/captures",
    private val json: Json = Json { ignoreUnknownKeys = true; encodeDefaults = true },
) : CaptureRepository {
    private val sessionsPath get() = "$root/sessions"
    private val recordsPath get() = "$root/verified-records.jsonl"
    private val appendLockPath get() = "$root/.verified-records.lock"

    override fun create(session: CaptureSession) {
        require(session.id.matches(Regex("[A-Za-z0-9_-]+"))) { "capture ID must be a safe identifier" }
        require(session(session.id) == null) { "capture already exists: ${session.id}" }
        ensureRoot()
        writeAtomically("$sessionsPath/${session.id}.json", json.encodeToString(CaptureSession.serializer(), session))
    }

    override fun session(id: String): CaptureSession? = readText("$sessionsPath/$id.json")?.let {
        runCatching { json.decodeFromString(CaptureSession.serializer(), it) }.getOrNull()
    }

    override fun sessions(): List<CaptureSession> {
        val names = PosixProcessRunner().run(listOf("find", sessionsPath, "-name", "*.json", "-type", "f")).stdout.lineSequence()
        return names.mapNotNull { path -> readText(path)?.let { raw -> runCatching { json.decodeFromString(CaptureSession.serializer(), raw) }.getOrNull() } }.sortedBy(CaptureSession::startedAtEpochMillis).toList()
    }

    override fun transition(id: String, expected: CaptureState, next: CaptureState): Boolean {
        val current = session(id) ?: return false
        if (current.state != expected) return false
        writeAtomically("$sessionsPath/$id.json", json.encodeToString(CaptureSession.serializer(), current.copy(state = next)))
        return true
    }

    override fun append(exchange: VerifiedExchange): Long {
        val capture = session(exchange.context.captureId) ?: error("capture not found: ${exchange.context.captureId}")
        require(capture.target == exchange.context.origin.target) { "verified origin does not match capture target" }
        ensureRoot()
        return withAppendLock {
            val sequence = records().maxOfOrNull(StoredExchange::sequence)?.plus(1) ?: 1
            val stored = StoredExchange(sequence, exchange.toSurrogate())
            val file = fopen(recordsPath, "a") ?: error("unable to append verified capture record")
            try { check(fputs(json.encodeToString(StoredExchange.serializer(), stored) + "\n", file) >= 0) } finally { fclose(file) }
            sequence
        }
    }

    override fun read(id: String, throughSequence: Long?): CaptureRead {
        val capture = session(id) ?: error("capture not found: $id")
        val all = records().filter { it.exchange.captureId == id }
        val watermark = throughSequence ?: all.maxOfOrNull(StoredExchange::sequence) ?: 0
        val exchanges = all.filter { it.sequence <= watermark }.map { it.exchange.toVerified(json) }
        return CaptureRead(capture, watermark, exchanges, 0)
    }

    private fun records(): List<StoredExchange> = readLines(recordsPath).mapIndexedNotNull { index, line ->
        if (line.isBlank()) null else runCatching { json.decodeFromString(StoredExchange.serializer(), line) }.getOrElse {
            error("corrupt verified capture record ${index + 1}")
        }
    }

    private fun VerifiedExchange.toSurrogate() = VerifiedExchangeSurrogate(
        context.captureId, context.connectionId, context.acceptedAtEpochMillis,
        context.origin.target.platform, context.origin.target.deviceId, context.origin.target.applicationId, context.origin.target.androidUserId,
        context.origin.process.pid, context.origin.process.startIdentity, context.origin.uid, context.origin.method,
        json.encodeToString(dev.lynx.model.NetworkExchange.serializer(), exchange),
    )

    private fun VerifiedExchangeSurrogate.toVerified(json: Json): VerifiedExchange {
        val target = dev.lynx.model.CaptureTarget(originPlatform, originDeviceId, originApplicationId, originAndroidUserId)
        val origin = dev.lynx.model.VerifiedOrigin(target, dev.lynx.model.ProcessIdentity(originPid, originStartIdentity), originUid, originMethod)
        return VerifiedExchange(dev.lynx.model.ConnectionContext(captureId, connectionId, origin, acceptedAtEpochMillis), json.decodeFromString(dev.lynx.model.NetworkExchange.serializer(), exchangeJson))
    }

    private fun ensureRoot() { PosixProcessRunner().run(listOf("mkdir", "-p", sessionsPath)) }
    private fun <T> withAppendLock(block: () -> T): T {
        repeat(10_000) {
            if (PosixProcessRunner().run(listOf("mkdir", appendLockPath)).exitCode == 0) {
                try { return block() } finally { PosixProcessRunner().run(listOf("rmdir", appendLockPath)) }
            }
        }
        error("timed out acquiring verified capture append lock")
    }
    private fun writeAtomically(path: String, text: String) {
        val temporary = "$path.tmp.${getpid()}.${kotlin.time.Clock.System.now().toEpochMilliseconds()}"
        val file = fopen(temporary, "w") ?: error("unable to write capture metadata")
        val status = try { fputs(text, file) } finally { fclose(file) }
        if (status < 0 || rename(temporary, path) != 0) { remove(temporary); error("unable to replace capture metadata") }
    }

    private fun readText(path: String): String? = readLines(path).takeIf { it.isNotEmpty() }?.joinToString("\n")
    private fun readLines(path: String): List<String> {
        val file = fopen(path, "r") ?: return emptyList()
        return try {
            val lines = mutableListOf<String>(); val current = StringBuilder()
            while (true) {
                val next = fgetc(file)
                if (next == EOF) break
                if (next == '\n'.code) { lines += current.toString(); current.clear() } else current.append(next.toChar())
            }
            if (current.isNotEmpty()) lines += current.toString()
            lines
        } finally { fclose(file) }
    }
}
