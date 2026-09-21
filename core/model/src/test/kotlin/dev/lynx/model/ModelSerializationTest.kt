package dev.lynx.model

import kotlinx.serialization.json.Json
import kotlin.test.Test
import kotlin.test.assertEquals

class ModelSerializationTest {
    @Test
    fun databaseValueUsesTheStableMachineReadableShape() {
        val json = Json { encodeDefaults = true }

        val encoded = json.encodeToString<DatabaseValue>(DatabaseValue.BlobValue("AAE="))
        assertEquals("{\"kind\":\"blob\",\"data\":\"AAE=\",\"encoding\":\"base64\"}", encoded)
    }

    @Test
    fun networkListCommandRoundTripsWithStableMachineReadableShape() {
        val json = Json { encodeDefaults = true }
        val command: NetworkCommand = NetworkCommand.List(
            filter = NetworkFilter(
                method = "GET",
                status = 200,
                urlSubstring = "/health",
                sinceEpochMillis = 1_800_000_000_000,
                limit = 25,
            ),
        )

        val encoded = json.encodeToString<NetworkCommand>(command)

        assertEquals(
            "{\"command\":\"list\",\"filter\":{\"method\":\"GET\",\"status\":200,\"urlSubstring\":\"/health\",\"sinceEpochMillis\":1800000000000,\"limit\":25}}",
            encoded,
        )
        assertEquals(command, json.decodeFromString<NetworkCommand>(encoded))
    }

    @Test
    fun networkExchangeRoundTripsWithoutJvmSpecificTypes() {
        val json = Json { encodeDefaults = true }
        val exchange = NetworkExchange(
            meta = EvidenceMeta(
                id = EvidenceId("ev_1"),
                sessionId = SessionId("session_1"),
                observedAt = kotlinx.datetime.Instant.parse("2026-09-21T10:15:30Z"),
                source = EvidenceSource.NETWORK,
                deviceSerial = "emulator-5554",
                packageName = "dev.lynx.fixture",
                processId = 42,
            ),
            requestId = RequestId("req_1"),
            request = NetworkRequest(
                method = "GET",
                url = "https://example.test/health",
                headers = mapOf("Accept" to "application/json"),
                body = null,
            ),
            response = NetworkResponse(
                status = 200,
                headers = mapOf("Content-Type" to "application/json"),
                body = "{\"ok\":true}",
            ),
            failure = null,
            timing = NetworkTiming(100, 125, 25),
            capture = NetworkCaptureMetadata(false, 0, false),
            protocol = "HTTP/2",
        )

        val encoded = json.encodeToString(exchange)

        assertEquals(exchange, json.decodeFromString<NetworkExchange>(encoded))
    }
}
