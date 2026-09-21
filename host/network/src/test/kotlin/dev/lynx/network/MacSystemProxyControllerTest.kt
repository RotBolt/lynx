package dev.lynx.network

import dev.lynx.adb.CommandResult
import dev.lynx.adb.CommandRunner
import kotlin.test.Test
import kotlin.test.assertTrue

class MacSystemProxyControllerTest {
    @Test
    fun appliesAndRestoresProxyState() {
        val runner = RecordingRunner()
        val controller = MacSystemProxyController(runner = runner)
        val lease = controller.apply("127.0.0.1:50123")
        lease.restore()
        assertTrue(runner.commands.any { "-setwebproxy" in it })
        assertTrue(runner.commands.any { "-setsecurewebproxy" in it })
        assertTrue(runner.commands.count { "-setwebproxystate" in it } >= 2)
    }

    private class RecordingRunner : CommandRunner {
        val commands = mutableListOf<List<String>>()
        override fun run(arguments: List<String>): CommandResult {
            commands += arguments
            return if (arguments.any { it.startsWith("-get") }) {
                CommandResult(0, "Enabled: No\nServer: \nPort: 0\n", "")
            } else CommandResult(0, "", "")
        }
    }
}
