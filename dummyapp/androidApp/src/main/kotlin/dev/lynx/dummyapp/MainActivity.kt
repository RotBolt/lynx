package dev.lynx.dummyapp

import android.app.Activity
import android.os.Bundle
import android.view.ViewGroup
import android.view.Window
import android.widget.Button
import android.widget.LinearLayout
import android.widget.TextView
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.Job
import kotlinx.coroutines.launch
import kotlinx.coroutines.SupervisorJob

class MainActivity : Activity() {
    private var handle: AndroidScenarioHandle? = null
    private val activityJob = SupervisorJob()
    private val scope = CoroutineScope(activityJob + Dispatchers.Main.immediate)
    private lateinit var status: TextView
    private lateinit var websocketStart: Button
    private lateinit var websocketClose: Button

    override fun onCreate(savedInstanceState: Bundle?) {
        requestWindowFeature(Window.FEATURE_NO_TITLE)
        super.onCreate(savedInstanceState)
        handle = AndroidScenarioHandle.create(this)
        status = TextView(this).apply { text = "Lynx Dummy App — no requests run automatically" }
        websocketStart = Button(this).apply {
            text = "WebSocket Start"
            setOnClickListener { scope.launch { startWebSocket() } }
        }
        websocketClose = Button(this).apply {
            text = "WebSocket Close"
            isEnabled = false
            setOnClickListener { scope.launch { closeWebSocket() } }
        }
        val layout = LinearLayout(this).apply {
            orientation = LinearLayout.VERTICAL
            setPadding(32, 48, 32, 32)
            addView(action("HTTP/1.1 Call") { requestHttp1() }, match())
            addView(action("HTTP/2 Call") { requestHttp2() }, match())
            addView(websocketStart, match())
            addView(websocketClose, match())
            addView(status, match())
        }
        setContentView(layout)
    }

    override fun onDestroy() {
        activityJob.cancel()
        handle?.close()
        handle = null
        super.onDestroy()
    }

    private fun action(label: String, call: suspend () -> Unit) = Button(this).apply {
        text = label
        setOnClickListener {
            isEnabled = false
            scope.launch {
                try { call() } finally { isEnabled = true }
            }
        }
    }

    private suspend fun requestHttp1() {
        status.text = outcome(handle!!.scenario.requestHttp1())
    }

    private suspend fun requestHttp2() {
        status.text = outcome(handle!!.scenario.requestHttp2())
    }

    private suspend fun startWebSocket() {
        websocketStart.isEnabled = false
        val result = handle!!.scenario.startWebSocket()
        status.text = outcome(result)
        websocketStart.isEnabled = result.error != null
        websocketClose.isEnabled = result.error == null
    }

    private suspend fun closeWebSocket() {
        handle!!.scenario.closeWebSocket()
        websocketClose.isEnabled = false
        websocketStart.isEnabled = true
        status.text = "WebSocket closed"
    }

    private fun outcome(result: ExchangeResult) = buildString {
        append("${result.transport}: ")
        append(result.status?.let { "HTTP $it" } ?: "FAILED")
        result.responseBody?.let { append("\n$it") }
        result.error?.let { append("\n$it") }
    }

    private fun match() = LinearLayout.LayoutParams(ViewGroup.LayoutParams.MATCH_PARENT, ViewGroup.LayoutParams.WRAP_CONTENT)
}
