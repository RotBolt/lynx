package dev.lynx.daemon

import dev.lynx.adb.CommandResult
import dev.lynx.adb.CommandRunner
import kotlin.test.Test
import kotlin.test.assertEquals

class IosSimulatorTargetResolverTest {
    @Test
    fun resolvesBootedSimulatorAndLaunchPid() {
        val udid = "25CD22C1-E1F2-417F-87BA-09D7600F3B93"
        val runner = FakeRunner(
            CommandResult(0, "iPhone 17 Pro ($udid) (Booted)\n", ""),
            CommandResult(0, "dev.lynx.dummyapp: 12345\n", ""),
        )
        val target = IosSimulatorTargetResolver(runner).resolve(udid, "dev.lynx.dummyapp")
        assertEquals("ios-simulator:$udid", target.deviceSerial)
        assertEquals(12345, target.pid)
    }

    private class FakeRunner(private vararg val results: CommandResult) : CommandRunner {
        private var index = 0
        override fun run(arguments: List<String>): CommandResult = results[index++]
    }
}
