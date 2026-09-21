package dev.lynx.daemon

import java.nio.file.Files
import kotlinx.datetime.Instant
import dev.lynx.model.*
import kotlin.test.Test
import kotlin.test.assertContains
import kotlin.test.assertEquals
import kotlin.test.assertFailsWith

class ProtocolEnvelopeTest {
    @Test
    fun serializesNetworkListWithInstantMetadata() {
        val timeline = InMemoryEvidenceTimeline()
        val meta = EvidenceMeta(EvidenceId("ev-1"), SessionId("s-1"), Instant.parse("2026-09-21T10:15:30Z"), EvidenceSource.NETWORK, "device", "pkg", 42)
        timeline.append(NetworkExchange(meta, RequestId("req-1"), NetworkRequest("GET", "https://example.test", emptyMap(), null), NetworkResponse(200, emptyMap(), "ok"), null, NetworkTiming(1, 2, 1), NetworkCaptureMetadata(false, 0, false)))

        val response = DaemonProtocol.handle(
            """{"protocol_version":1,"schema_version":"lynx.v1","request_id":"req-list","command":"NETWORK_LIST","arguments":{}}""",
            DaemonService(timeline = timeline),
        )

        assertContains(response, "2026-09-21T10:15:30Z")
        assertContains(response, "\"type\":\"network_list\"")
    }

    @Test
    fun parsesAndSerializesVersionedRequest() {
        val request = DaemonProtocol.parseRequest(
            """{"protocol_version":1,"schema_version":"lynx.v1","request_id":"req-1","command":"PING","arguments":{}}"""
        )

        assertEquals(1, request.protocolVersion)
        assertEquals("lynx.v1", request.schemaVersion)
        assertEquals("req-1", request.requestId)
        assertEquals("PING", request.command)
    }

    @Test
    fun normalizesCommandAndPreservesArbitraryArguments() {
        val response = DaemonProtocol.handle(
            """{"protocol_version":1,"schema_version":"lynx.v1","request_id":"req-args","command":"ping","arguments":{"future_option":{"enabled":true},"limit":7}}""",
            DaemonService()
        )
        assertContains(response, "\"command\":\"PING\"")
        assertContains(response, "\"future_option\":{\"enabled\":true}")
        assertContains(response, "\"limit\":7")
    }

    @Test
    fun rejectsBlankEnvelopeFieldsAndNonObjectArguments() {
        assertFailsWith<IllegalArgumentException> {
            DaemonProtocol.parseRequest("""{"protocol_version":1,"schema_version":"lynx.v1","request_id":" ","command":"PING"}""")
        }
        assertFailsWith<IllegalArgumentException> {
            DaemonProtocol.parseRequest("""{"protocol_version":1,"schema_version":"lynx.v1","request_id":"r","command":"PING","arguments":[]}""")
        }
    }

    @Test
    fun rejectsUnsupportedProtocolVersionDeterministically() {
        val response = DaemonProtocol.handle(
            """{"protocol_version":99,"schema_version":"lynx.v1","request_id":"req-1","command":"PING","arguments":{}}""",
            DaemonService()
        )

        assertContains(response, "\"type\":\"error\"")
        assertContains(response, "\"code\":\"UNSUPPORTED_PROTOCOL_VERSION\"")
        assertContains(response, "\"operation\":\"PING\"")
    }

    @Test
    fun socketRoundTripReturnsStructuredResponse() {
        val directory = Files.createTempDirectory("lynx-protocol-test")
        val socketPath = directory.resolve("daemon.sock")
        val server = LocalDaemonServer(socketPath)
        server.start()
        try {
            val response = LocalDaemonClient(socketPath).executeJson(
                """{"protocol_version":1,"schema_version":"lynx.v1","request_id":"req-2","command":"PING","arguments":{}}"""
            )
            assertContains(response, "\"type\":\"pong\"")
            assertContains(response, "\"request_id\":\"req-2\"")
            assertContains(response, "\"schema_version\":\"lynx.v1\"")
        } finally {
            server.close()
            Files.deleteIfExists(socketPath)
            Files.deleteIfExists(directory)
        }
    }
}
