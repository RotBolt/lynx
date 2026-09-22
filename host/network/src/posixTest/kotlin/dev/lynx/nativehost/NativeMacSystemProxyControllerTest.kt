package dev.lynx.nativehost

import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertTrue

class NativeMacSystemProxyControllerTest {
    @Test
    fun selectsTheNetworkServiceForTheActiveRouteInsteadOfTheFirstHardwarePort() {
        val runner = StatefulNetworksetupRunner(
            activeInterface = "en0",
            networkServiceOrder = """
                (1) Thunderbolt Bridge
                (Hardware Port: Thunderbolt Bridge, Device: bridge0)

                (2) Wi-Fi
                (Hardware Port: Wi-Fi, Device: en0)
            """.trimIndent(),
        )

        val snapshot = NativeMacSystemProxyController(runner = runner, service = null).inspect()

        assertEquals("Wi-Fi", snapshot.service)
    }

    @Test
    fun appliesLoopbackProxyAndRestoresExactPriorWebAndSecureProxyState() {
        val runner = RecordingRunner(
            listOf(
                "Enabled: No\nServer: 127.0.0.1\nPort: 54272\n",
                "Enabled: No\nServer: 127.0.0.1\nPort: 54272\n",
                "", "", "", "", "", "", "", "",
            ),
        )
        val controller = NativeMacSystemProxyController(runner = runner)

        val previous = controller.apply(62006)
        controller.restore(previous)

        assertEquals("macos", previous["controller"])
        assertEquals("127.0.0.1", previous["webServer"])
        assertEquals("54272", previous["webPort"])
        assertEquals("No", previous["webEnabled"])
        assertTrue(runner.commands.contains(listOf("/usr/sbin/networksetup", "-setwebproxy", "Wi-Fi", "127.0.0.1", "62006")))
        assertTrue(runner.commands.contains(listOf("/usr/sbin/networksetup", "-setsecurewebproxy", "Wi-Fi", "127.0.0.1", "62006")))
        assertTrue(runner.commands.contains(listOf("/usr/sbin/networksetup", "-setwebproxy", "Wi-Fi", "127.0.0.1", "54272")))
        assertTrue(runner.commands.contains(listOf("/usr/sbin/networksetup", "-setsecurewebproxy", "Wi-Fi", "127.0.0.1", "54272")))
        assertTrue(runner.commands.contains(listOf("/usr/sbin/networksetup", "-setwebproxystate", "Wi-Fi", "off")))
        assertTrue(runner.commands.contains(listOf("/usr/sbin/networksetup", "-setsecurewebproxystate", "Wi-Fi", "off")))
    }

    @Test
    fun rollsBackWebProxyWhenApplyingSecureProxyFails() {
        val runner = RecordingRunner(
            listOf(
                "Enabled: Yes\nServer: proxy.example\nPort: 8888\n",
                "Enabled: No\nServer: \nPort: 0\n",
                "",
                "networksetup failed",
                "",
                "",
            ),
            exitCodes = listOf(0, 0, 0, 1, 0, 0),
        )
        val controller = NativeMacSystemProxyController(runner = runner)

        val failure = runCatching { controller.apply(62006) }.exceptionOrNull()

        assertTrue(failure != null)
        assertTrue(runner.commands.contains(listOf("/usr/sbin/networksetup", "-setwebproxy", "Wi-Fi", "proxy.example", "8888")))
        assertTrue(runner.commands.contains(listOf("/usr/sbin/networksetup", "-setwebproxystate", "Wi-Fi", "on")))
    }

    private class RecordingRunner(
        outputs: List<String>,
        exitCodes: List<Int> = emptyList(),
    ) : NativeProcessRunner {
        private val responses = outputs.toMutableList()
        private val statuses = exitCodes.toMutableList()
        val commands = mutableListOf<List<String>>()

        override fun run(command: List<String>): NativeCommandResult {
            commands += command
            return NativeCommandResult(
                exitCode = if (statuses.isEmpty()) 0 else statuses.removeAt(0),
                stdout = responses.removeAt(0),
            )
        }
    }
}
