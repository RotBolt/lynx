package dev.lynx.dummyapp

import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertNotNull
import io.ktor.client.HttpClient
import io.ktor.client.engine.mock.MockEngine
import io.ktor.client.engine.mock.respond
import io.ktor.client.plugins.websocket.WebSockets
import io.ktor.http.HttpStatusCode
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.MutableSharedFlow
import kotlinx.coroutines.runBlocking

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

    @Test
    fun runOnceRecordsHttpOneHttpTwoAndWebSocketOutcomes() = runBlocking {
        val store = RecordingExchangeStore()
        val client = HttpClient(MockEngine) {
            install(WebSockets)
            engine {
                addHandler { request ->
                    respond(
                        content = "{\"path\":\"${request.url.encodedPath}\"}",
                        status = HttpStatusCode.OK,
                    )
                }
            }
        }
        val scenario = KtorDummyScenario(
            client = client,
            store = store,
            endpoints = ScenarioEndpoints(
                http1 = "http://fixture.test/http1",
                http2 = "https://fixture.test/http2",
                websocket = "ws://fixture.test/socket",
            ),
        )

        val results = scenario.runOnce()
        client.close()

        assertEquals(listOf(TransportKind.HTTP_1_1, TransportKind.HTTP_2, TransportKind.WEBSOCKET), results.map { it.transport })
        assertEquals(results, store.values)
        assertEquals(200, results[0].status)
        assertEquals("{\"path\":\"/http1\"}", results[0].responseBody)
        assertEquals(200, results[1].status)
        assertEquals("{\"path\":\"/http2\"}", results[1].responseBody)
        assertNotNull(results[2].error)
        assertEquals("lynx-dummy-ping", results[2].requestBody)
    }

    private class RecordingExchangeStore : ExchangeStore {
        val values = mutableListOf<ExchangeResult>()
        private val events = MutableSharedFlow<ExchangeResult>()

        override suspend fun append(result: ExchangeResult) {
            values += result
            events.emit(result)
        }

        override fun observe(): Flow<ExchangeResult> = events
    }
}
