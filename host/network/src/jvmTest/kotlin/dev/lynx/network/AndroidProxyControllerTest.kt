package dev.lynx.network

import dev.lynx.adb.CommandResult
import dev.lynx.adb.CommandRunner
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFailsWith
import kotlin.test.assertFalse
import kotlin.test.assertTrue

class AndroidProxyControllerTest {
    @Test
    fun configuresAllAndroidProxyKeysForAppTraffic() {
        val runner = FakeRunner(
            // inspect() reads the complete proxy state
            CommandResult(0, "null\n", ""),
            CommandResult(0, "null\n", ""),
            CommandResult(0, "null\n", ""),
            CommandResult(0, "null\n", ""),
        )
        val controller = AdbAndroidProxyController("emulator-5554", runner, "adb")
        controller.apply("10.0.2.2:4321")

        assertEquals(
            listOf(
                "http_proxy" to "10.0.2.2:4321",
                "https_proxy" to "10.0.2.2:4321",
                "global_http_proxy_host" to "10.0.2.2",
                "global_http_proxy_port" to "4321",
            ),
            runner.calls.drop(4).filter { it[5] == "put" }.map { it[7] to it[8] },
        )
    }

    @Test
    fun capturesAndRestoresExistingProxy() {
        val runner = FakeRunner(
            CommandResult(0, "old.proxy:8080\n", ""),
            CommandResult(0, "null\n", ""),
            CommandResult(0, "null\n", ""),
            CommandResult(0, "null\n", ""),
        )
        val controller = AdbAndroidProxyController("emulator-5554", runner, "adb")
        val lease = controller.apply("10.0.2.2:4321")
        controller.restore(lease)
        assertEquals(
            listOf(
                listOf("adb", "-s", "emulator-5554", "shell", "settings", "get", "global", "http_proxy"),
                listOf("adb", "-s", "emulator-5554", "shell", "settings", "get", "global", "https_proxy"),
                listOf("adb", "-s", "emulator-5554", "shell", "settings", "get", "global", "global_http_proxy_host"),
                listOf("adb", "-s", "emulator-5554", "shell", "settings", "get", "global", "global_http_proxy_port"),
                listOf("adb", "-s", "emulator-5554", "shell", "settings", "put", "global", "http_proxy", "10.0.2.2:4321"),
                listOf("adb", "-s", "emulator-5554", "shell", "settings", "put", "global", "https_proxy", "10.0.2.2:4321"),
                listOf("adb", "-s", "emulator-5554", "shell", "settings", "put", "global", "global_http_proxy_host", "10.0.2.2"),
                listOf("adb", "-s", "emulator-5554", "shell", "settings", "put", "global", "global_http_proxy_port", "4321"),
                listOf("adb", "-s", "emulator-5554", "shell", "settings", "put", "global", "http_proxy", "old.proxy:8080"),
                listOf("adb", "-s", "emulator-5554", "shell", "settings", "delete", "global", "https_proxy"),
                listOf("adb", "-s", "emulator-5554", "shell", "settings", "delete", "global", "global_http_proxy_host"),
                listOf("adb", "-s", "emulator-5554", "shell", "settings", "delete", "global", "global_http_proxy_port"),
            ), runner.calls,
        )
    }

    @Test
    fun restoreIsIdempotentAndApplySameEndpointDoesNotOverwriteOriginal() {
        val runner = FakeRunner(
            CommandResult(0, "null\n", ""), CommandResult(0, "null\n", ""),
            CommandResult(0, "null\n", ""), CommandResult(0, "null\n", ""),
        )
        val controller = AdbAndroidProxyController("emulator-5554", runner, "adb")
        val first = controller.apply("10.0.2.2:4321")
        assertTrue(first === controller.apply("10.0.2.2:4321"))
        controller.restore(first)
        controller.restore(first)
        assertEquals(12, runner.calls.size)
        assertEquals(
            listOf("adb", "-s", "emulator-5554", "shell", "settings", "put", "global", "http_proxy", ":0"),
            runner.calls[8],
        )
        assertTrue(first.isRestored)
    }

    @Test
    fun failedApplyAttemptsToRestorePriorState() {
        val runner = FakeRunner(
            CommandResult(0, "old.proxy:8080\n", ""),
            CommandResult(0, "null\n", ""), CommandResult(0, "null\n", ""), CommandResult(0, "null\n", ""),
            CommandResult(1, "", "permission denied"),
            CommandResult(0, "", ""), CommandResult(0, "", ""), CommandResult(0, "", ""),
            CommandResult(0, "", ""),
        )
        val controller = AdbAndroidProxyController("device", runner, "adb")
        assertFailsWith<ProxyControllerException> { controller.apply("10.0.2.2:4321") }
        assertTrue(runner.calls.any { it.getOrNull(7) == "http_proxy" && it.getOrNull(8) == "old.proxy:8080" })
    }

    @Test
    fun emulatorAndPhysicalEndpointRulesAreExplicit() {
        assertEquals("10.0.2.2:1234", AndroidProxyEndpoint.forDevice("emulator-5554", "0.0.0.0", 1234))
        assertEquals("192.168.1.2:1234", AndroidProxyEndpoint.forDevice("device", "192.168.1.2", 1234))
        assertFailsWith<ProxyControllerException> { AndroidProxyEndpoint.forDevice("device", "0.0.0.0", 1234) }
    }
}

private class FakeRunner(private val results: MutableList<CommandResult>) : CommandRunner {
    constructor(vararg results: CommandResult) : this(results.toMutableList())
    val calls = mutableListOf<List<String>>()
    override fun run(arguments: List<String>): CommandResult {
        calls += arguments
        return results.removeFirstOrNull() ?: CommandResult(0, "", "")
    }
}
