package dev.lynx.dummyapp

import android.app.Activity
import android.os.Bundle
import android.widget.TextView
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.Job
import kotlinx.coroutines.delay
import kotlinx.coroutines.launch
import kotlinx.coroutines.SupervisorJob

class MainActivity : Activity() {
    private var handle: AndroidScenarioHandle? = null
    private var scenarioJob: Job? = null

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        setContentView(TextView(this).apply {
            text = "Lynx Dummy App\nRunning HTTP/1.1, HTTP/2, and WebSocket scenario"
        })
        handle = AndroidScenarioHandle.create(this)
        scenarioJob = CoroutineScope(SupervisorJob() + Dispatchers.Main.immediate).launch {
            while (true) {
                handle?.scenario?.runOnce()
                delay(15_000)
            }
        }
    }

    override fun onDestroy() {
        scenarioJob?.cancel()
        scenarioJob = null
        handle?.close()
        handle = null
        super.onDestroy()
    }
}
