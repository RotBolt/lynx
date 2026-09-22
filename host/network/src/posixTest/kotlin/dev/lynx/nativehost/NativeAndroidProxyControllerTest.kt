package dev.lynx.nativehost

import kotlin.test.Test
import kotlin.test.assertEquals

class NativeAndroidProxyControllerTest {
    @Test
    fun appliesAndRestoresEveryAndroidProxySetting() {
        val runner = RecordingRunner(listOf(
            "null", "null", "null", "null", // previous settings
            "", "", "", "", // apply
            "", "", "", "", // restore
        ))
        val controller = NativeAndroidProxyController(runner)

        val previous = controller.apply("emulator-5554", "10.0.2.2", 62006)
        controller.restore("emulator-5554", previous)

        val keys = listOf("http_proxy", "https_proxy", "global_http_proxy_host", "global_http_proxy_port")
        assertEquals(
            keys.map { listOf("adb", "-s", "emulator-5554", "shell", "settings", "get", "global", it) },
            runner.commands.take(4),
        )
        assertEquals(
            listOf("10.0.2.2:62006", "10.0.2.2:62006", "10.0.2.2", "62006"),
            runner.commands.drop(4).take(4).map { it.last() },
        )
        assertEquals(
            keys.map { listOf("adb", "-s", "emulator-5554", "shell", "settings", "delete", "global", it) },
            runner.commands.drop(8),
        )
    }

    private class RecordingRunner(outputs: List<String>) : NativeProcessRunner {
        private val results = outputs.toMutableList()
        val commands = mutableListOf<List<String>>()
        override fun run(command: List<String>): NativeCommandResult {
            commands += command
            return NativeCommandResult(0, "${results.removeFirst()}\n")
        }
    }
}
