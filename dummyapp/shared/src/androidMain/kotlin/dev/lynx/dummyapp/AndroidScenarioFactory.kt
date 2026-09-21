package dev.lynx.dummyapp

import android.content.Context
import io.ktor.client.HttpClient
import io.ktor.client.engine.okhttp.OkHttp
import io.ktor.client.plugins.websocket.WebSockets

class AndroidScenarioHandle private constructor(
    val scenario: DummyScenario,
    private val client: HttpClient,
    private val store: AndroidExchangeStore,
) {
    fun close() {
        client.close()
        store.close()
    }

    companion object {
        fun create(context: Context): AndroidScenarioHandle {
            val client = HttpClient(OkHttp) { install(WebSockets) }
            val store = AndroidExchangeStore(context)
            return AndroidScenarioHandle(KtorDummyScenario(client, store), client, store)
        }
    }
}
