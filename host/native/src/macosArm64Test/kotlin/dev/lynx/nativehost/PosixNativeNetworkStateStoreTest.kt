package dev.lynx.nativehost

import dev.lynx.model.*
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertTrue

class PosixNativeNetworkStateStoreTest {
    @Test
    fun evidenceSurvivesASecondStoreInstanceAndFilters() {
        val root = "/tmp/lynx-native-store-test-${kotlin.time.Clock.System.now().toEpochMilliseconds()}"
        val first = PosixNativeNetworkStateStore(root)
        val exchange = NetworkExchange(
            EvidenceMeta(EvidenceId("ev-test"), SessionId("native"), kotlin.time.Clock.System.now(), EvidenceSource.NETWORK, "native", "test", null),
            RequestId("req-test"), NetworkRequest("GET", "http://example.test/health", emptyMap(), null),
            NetworkResponse(200, mapOf("Content-Type" to "text/plain"), "ok"), null,
            NetworkTiming(1, 2, 1), NetworkCaptureMetadata(false, 0, false), protocol = "HTTP/1.1",
        )
        first.setRunning("127.0.0.1:62006", NetworkCapabilities(httpsMitm = false, supportedProtocols = listOf("HTTP/1.1")), "10.0.2.2:8080")
        first.append(exchange)
        val second = PosixNativeNetworkStateStore(root)
        assertTrue(second.isRunning())
        assertEquals("10.0.2.2:8080", second.previousProxy())
        assertEquals("req-test", second.list(NetworkFilter(status = 200)).single().requestId.value)
        assertEquals(exchange, second.get(RequestId("req-test")))
        second.clearRunning()
    }
}
