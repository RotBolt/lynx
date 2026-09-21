package dev.lynx.model

import java.time.Instant
import kotlin.test.Test
import kotlin.test.assertEquals

class EvidenceModelTest {
    @Test
    fun networkExchangeCarriesSharedEvidenceMetadata() {
        val meta = EvidenceMeta(
            id = EvidenceId("ev_1"),
            sessionId = SessionId("session_1"),
            observedAt = Instant.parse("2026-08-29T10:15:30Z"),
            source = EvidenceSource.NETWORK,
            deviceSerial = "emulator-5554",
            packageName = "com.example.app",
            processId = 42,
        )

        val exchange = NetworkExchange(
            meta = meta,
            requestId = RequestId("req_1"),
            request = NetworkRequest("GET", "https://example.test/users", emptyMap(), null),
            response = NetworkResponse(200, emptyMap(), null),
            failure = null,
            timing = NetworkTiming(1, 2, 3),
            capture = NetworkCaptureMetadata(false, 0, null),
        )

        assertEquals(EvidenceSource.NETWORK, exchange.meta.source)
        assertEquals("req_1", exchange.requestId.value)
    }
}
