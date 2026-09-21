package dev.lynx.dummyapp

import kotlinx.coroutines.flow.Flow
import io.ktor.client.HttpClient
import io.ktor.client.request.get
import io.ktor.client.statement.bodyAsText
import io.ktor.client.plugins.websocket.webSocket
import io.ktor.websocket.Frame
import io.ktor.websocket.readText
import io.ktor.websocket.send

enum class TransportKind {
    HTTP_1_1,
    HTTP_2,
    WEBSOCKET,
}

data class ExchangeResult(
    val transport: TransportKind,
    val method: String,
    val url: String,
    val status: Int?,
    val requestBody: String?,
    val responseBody: String?,
    val error: String?,
    val startedAtEpochMillis: Long,
    val completedAtEpochMillis: Long,
) {
    val durationMillis: Long
        get() = completedAtEpochMillis - startedAtEpochMillis
}

interface ExchangeStore {
    suspend fun append(result: ExchangeResult)
    fun observe(): Flow<ExchangeResult>
}

interface DummyScenario {
    suspend fun runOnce(): List<ExchangeResult>
}

data class ScenarioEndpoints(
    val http1: String = "http://10.0.2.2:8080/health",
    val http2: String = "https://jsonplaceholder.typicode.com/todos/1",
    val websocket: String = "ws://10.0.2.2:8080/ws",
)

class KtorDummyScenario(
    private val client: HttpClient,
    private val store: ExchangeStore,
    private val endpoints: ScenarioEndpoints = ScenarioEndpoints(),
) : DummyScenario {
    override suspend fun runOnce(): List<ExchangeResult> = listOf(
        executeHttp(TransportKind.HTTP_1_1, endpoints.http1),
        executeHttp(TransportKind.HTTP_2, endpoints.http2),
        executeWebSocket(endpoints.websocket),
    )

    private suspend fun executeHttp(transport: TransportKind, url: String): ExchangeResult {
        val started = currentEpochMillis()
        return try {
            val response = client.get(url)
            val body = response.bodyAsText()
            ExchangeResult(transport, "GET", url, response.status.value, null, body, null, started, currentEpochMillis())
        } catch (error: Throwable) {
            ExchangeResult(transport, "GET", url, null, null, null, error.message ?: error::class.simpleName, started, currentEpochMillis())
        }.also { store.append(it) }
    }

    private suspend fun executeWebSocket(url: String): ExchangeResult {
        val started = currentEpochMillis()
        return try {
            var echoed = ""
            client.webSocket(urlString = url) {
                send("lynx-dummy-ping")
                echoed = (incoming.receive() as? Frame.Text)?.readText().orEmpty()
            }
            ExchangeResult(TransportKind.WEBSOCKET, "GET", url, 101, "lynx-dummy-ping", echoed, null, started, currentEpochMillis())
        } catch (error: Throwable) {
            ExchangeResult(TransportKind.WEBSOCKET, "GET", url, null, "lynx-dummy-ping", null, error.message ?: error::class.simpleName, started, currentEpochMillis())
        }.also { store.append(it) }
    }

}
