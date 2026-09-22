package dev.lynx.nativehost

import dev.lynx.model.CaptureSession
import dev.lynx.model.CaptureState
import dev.lynx.model.CaptureTarget
import dev.lynx.model.ConnectionContext
import dev.lynx.model.EvidenceId
import dev.lynx.model.EvidenceMeta
import dev.lynx.model.EvidenceSource
import dev.lynx.model.NetworkCaptureMetadata
import dev.lynx.model.NetworkExchange
import dev.lynx.model.NetworkRequest
import dev.lynx.model.NetworkTiming
import dev.lynx.model.ProcessIdentity
import dev.lynx.model.RequestId
import dev.lynx.model.VerifiedOrigin
import kotlin.time.Clock
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFailsWith
import kotlin.test.assertTrue

class PosixCaptureRepositoryTest {
    @Test
    fun sessionsAreIsolatedAndWatermarkReadIsStable() {
        val root = temporaryRoot("capture-isolation")
        val repository = PosixCaptureRepository(root)
        val a = session("A")
        val b = session("B")
        repository.create(a); repository.create(b)
        repository.append(verified("A", "first"))
        val snapshot = repository.read("A")
        repository.append(verified("A", "late"))
        repository.append(verified("B", "other"))

        assertEquals(listOf("first"), repository.read("A", snapshot.throughSequence).exchanges.map { it.exchange.request.url })
        assertEquals(listOf("first", "late"), repository.read("A").exchanges.map { it.exchange.request.url })
        assertEquals(listOf("other"), repository.read("B").exchanges.map { it.exchange.request.url })
    }

    @Test
    fun rejectsContextWhoseOriginDoesNotMatchSessionTarget() {
        val repository = PosixCaptureRepository(temporaryRoot("capture-origin"))
        repository.create(session("A"))

        assertFailsWith<IllegalArgumentException> { repository.append(verified("A", "bad", application = "other.app")) }
    }

    @Test
    fun preservesMultiMiBBodyWithoutFixedReadBufferTruncation() {
        val repository = PosixCaptureRepository(temporaryRoot("capture-large"))
        repository.create(session("A"))
        val body = "x".repeat(2 * 1024 * 1024)
        val original = verified("A", "large").let { it.copy(exchange = it.exchange.copy(request = it.exchange.request.copy(body = body))) }

        repository.append(original)

        assertEquals(body, repository.read("A").exchanges.single().exchange.request.body)
    }

    @Test
    fun completeCorruptRecordIsExplicitFailure() {
        val root = temporaryRoot("capture-corrupt")
        val repository = PosixCaptureRepository(root)
        repository.create(session("A"))
        repository.append(verified("A", "good"))
        PosixProcessRunner().run(listOf("sh", "-c", "printf '%s\\n' '{bad-json' >> '$root/verified-records.jsonl'"))

        val failure = assertFailsWith<IllegalStateException> { repository.read("A") }
        assertTrue(failure.message!!.contains("corrupt verified capture record"))
    }

    private fun session(id: String) = CaptureSession(id, "attach-$id", CaptureTarget("android", "emulator-5554", "dev.lynx.dummyapp"), CaptureState.RUNNING, 1)
    private fun verified(capture: String, url: String, application: String = "dev.lynx.dummyapp"): VerifiedExchange {
        val target = CaptureTarget("android", "emulator-5554", application)
        val context = ConnectionContext(capture, "conn-$url", VerifiedOrigin(target, ProcessIdentity(42, "start"), 1000, "test"), 1)
        val exchange = NetworkExchange(EvidenceMeta(EvidenceId("ev-$url"), dev.lynx.model.SessionId("legacy"), Clock.System.now(), EvidenceSource.NETWORK, "emulator-5554", application, 42), RequestId("req-$url"), NetworkRequest("GET", url, emptyMap(), null), null, null, NetworkTiming(1, 1, 0), NetworkCaptureMetadata(false, 0, false))
        return VerifiedExchange(context, exchange)
    }

    private fun temporaryRoot(name: String): String = "/tmp/lynx-$name-${kotlin.time.Clock.System.now().toEpochMilliseconds()}"
}
