package dev.lynx.adb

import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFailsWith

class AdbClientTest {
    @Test
    fun parsesOnlineDevicesAndIgnoresOfflineDevices() {
        val runner = FakeCommandRunner(
            CommandResult(
                exitCode = 0,
                stdout = "List of devices attached\nemulator-5554\tdevice product:sdk_gphone model:Pixel_8\nold\toffline\n",
                stderr = "",
            ),
        )

        assertEquals(
            listOf(AdbDevice("emulator-5554", "device")),
            AdbClient(runner).devices(),
        )
    }

    @Test
    fun resolvesTheOnlyDeviceAndPid() {
        val runner = FakeCommandRunner(
            CommandResult(0, "List of devices attached\nemulator-5554\tdevice\n", ""),
            CommandResult(0, "1234\n", ""),
            CommandResult(0, "uid= u0_a123\n", ""),
        )

        assertEquals(
            ResolvedTarget("emulator-5554", "com.example.app", 1234),
            AdbClient(runner).resolveTarget(null, "com.example.app"),
        )
    }

    @Test
    fun rejectsMissingDevicesInsteadOfInventingATarget() {
        val runner = FakeCommandRunner(CommandResult(0, "List of devices attached\n", ""))

        val error = assertFailsWith<AdbException> {
            AdbClient(runner).resolveTarget(null, "com.example.app")
        }

        assertEquals("DEVICE_NOT_FOUND", error.code)
    }
}

private class FakeCommandRunner(vararg results: CommandResult) : CommandRunner {
    private val responses = ArrayDeque(results.toList())

    override fun run(arguments: List<String>): CommandResult = responses.removeFirst()
}
