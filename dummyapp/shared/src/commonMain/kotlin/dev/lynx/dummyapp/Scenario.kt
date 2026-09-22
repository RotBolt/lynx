package dev.lynx.dummyapp

import kotlinx.coroutines.flow.Flow
import io.ktor.client.HttpClient
import io.ktor.client.request.get
import io.ktor.client.statement.bodyAsText
import io.ktor.client.plugins.websocket.webSocketSession
import io.ktor.websocket.Frame
import io.ktor.websocket.WebSocketSession
import io.ktor.websocket.close
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
    suspend fun requestHttp1(): ExchangeResult
    suspend fun requestHttp2(): ExchangeResult
    suspend fun startWebSocket(): ExchangeResult
    suspend fun closeWebSocket()
}

data class ScenarioEndpoints(
    // These are public services, not the local fixture server. The first URL
    // intentionally stays cleartext to exercise HTTP/1.1; the second exercises
    // HTTPS/HTTP/2, and Postman Echo provides a public TLS WebSocket echo.
    val http1: String = "http://httpbin.org/get?source=lynx-dummy-http1",
    val http2: String = "https://jsonplaceholder.typicode.com/todos/1?source=lynx-dummy-http2",
    val websocket: String = "wss://ws.postman-echo.com/raw",
)

class KtorDummyScenario(
    private val client: HttpClient,
    private val store: ExchangeStore,
    private val endpoints: ScenarioEndpoints = ScenarioEndpoints(),
) : DummyScenario {
    private var websocket: WebSocketSession? = null

    override suspend fun requestHttp1(): ExchangeResult = executeHttp(TransportKind.HTTP_1_1, endpoints.http1)

    override suspend fun requestHttp2(): ExchangeResult = executeHttp(TransportKind.HTTP_2, endpoints.http2)

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

    override suspend fun startWebSocket(): ExchangeResult {
        closeWebSocket()
        val url = endpoints.websocket
        val started = currentEpochMillis()
        return try {
            val session = client.webSocketSession(urlString = url)
            websocket = session
            session.send("lynx-dummy-ping")
            val echoed = (session.incoming.receive() as? Frame.Text)?.readText().orEmpty()
            ExchangeResult(TransportKind.WEBSOCKET, "GET", url, 101, "lynx-dummy-ping", echoed, null, started, currentEpochMillis())
        } catch (error: Throwable) {
            ExchangeResult(TransportKind.WEBSOCKET, "GET", url, null, "lynx-dummy-ping", null, error.message ?: error::class.simpleName, started, currentEpochMillis())
        }.also { store.append(it) }
    }

    override suspend fun closeWebSocket() {
        websocket?.close()
        websocket = null
    }

}
