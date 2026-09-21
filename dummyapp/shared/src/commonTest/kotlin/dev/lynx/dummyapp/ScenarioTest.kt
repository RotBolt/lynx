package dev.lynx.dummyapp

import kotlin.test.Test
import kotlin.test.assertEquals

class ScenarioTest {
    @Test
    fun durationIsDerivedFromTimestamps() {
        val result = ExchangeResult(
            transport = TransportKind.HTTP_2,
            method = "GET",
            url = "https://example.test/health",
            status = 200,
            requestBody = null,
            responseBody = "ok",
            error = null,
            startedAtEpochMillis = 10,
            completedAtEpochMillis = 42,
        )

        assertEquals(32, result.durationMillis)
    }
}
