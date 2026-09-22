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
    fun defaultScenarioUsesPublicInternetEndpointsForEveryTransport() {
        val endpoints = ScenarioEndpoints()

        assertEquals("http://httpbin.org/get?source=lynx-dummy-http1", endpoints.http1)
        assertEquals("https://jsonplaceholder.typicode.com/todos/1?source=lynx-dummy-http2", endpoints.http2)
        assertEquals("wss://ws.postman-echo.com/raw", endpoints.websocket)
        assertEquals(false, listOf(endpoints.http1, endpoints.http2, endpoints.websocket)
            .any { it.contains("10.0.2.2") || it.contains("localhost") || it.contains("127.0.0.1") })
    }

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
    fun eachHttpActionRecordsOnlyItsOwnExchange() = runBlocking {
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

        val http1 = scenario.requestHttp1()
        assertEquals(1, store.values.size)
        assertEquals(TransportKind.HTTP_1_1, store.values.single().transport)

        val http2 = scenario.requestHttp2()
        client.close()

        assertEquals(TransportKind.HTTP_1_1, http1.transport)
        assertEquals(200, http1.status)
        assertEquals("{\"path\":\"/http1\"}", http1.responseBody)
        assertEquals(TransportKind.HTTP_2, http2.transport)
        assertEquals(200, http2.status)
        assertEquals("{\"path\":\"/http2\"}", http2.responseBody)
        assertEquals(listOf(TransportKind.HTTP_1_1, TransportKind.HTTP_2), store.values.map { it.transport })
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
