package dev.lynx.nativecli

import dev.lynx.model.NetworkCommand
import dev.lynx.model.NetworkCommandResult
import dev.lynx.nativehost.NativeCommandResult
import dev.lynx.nativehost.NativeProcessRunner
import dev.lynx.network.NativeNetworkBackend
import kotlin.test.Test
import kotlin.test.assertContains
import kotlin.test.assertEquals

class NativeCliDatabasePlatformTest {
    @Test
    fun androidListUsesTheCommonPlatformAndDeviceFlags() {
        val runner = RecordingRunner()
        cli(runner).run(
            listOf(
                "db", "list", "--platform", "android", "--device", "emulator-5554",
                "--package", "dev.lynx.dummyapp",
            ),
        )

        assertEquals(
            listOf("adb", "-s", "emulator-5554", "shell", "run-as", "dev.lynx.dummyapp", "find", "databases", "-type", "f"),
            runner.commands.single(),
        )
    }

    @Test
    fun iosListUsesTheSameListCommandWithSimulatorDeviceId() {
        val runner = RecordingRunner()
        cli(runner).run(
            listOf(
                "db", "list", "--platform", "ios", "--device", "25CD22C1-E1F2-417F-87BA-09D7600F3B93",
                "--package", "dev.lynx.dummyapp",
            ),
        )

        val command = runner.commands.single()
        assertEquals("sh", command[0])
        assertEquals("-c", command[1])
        assertContains(command[2], "xcrun simctl get_app_container '25CD22C1-E1F2-417F-87BA-09D7600F3B93' 'dev.lynx.dummyapp' data")
        assertContains(command[2], """cd "${'$'}root" && find . -name '*.db' -type f""")
    }

    @Test
    fun iosSnapshotUsesTheSameSnapshotCommandWithSimulatorDeviceId() {
        val runner = RecordingRunner()
        cli(runner).run(
            listOf(
                "db", "snapshot", "Documents/dummyapp.db", "--platform", "ios",
                "--device", "25CD22C1-E1F2-417F-87BA-09D7600F3B93", "--package", "dev.lynx.dummyapp",
            ),
        )

        val command = runner.commands.single()
        assertEquals("sh", command[0])
        assertEquals("-c", command[1])
        assertContains(command[2], "xcrun simctl get_app_container '25CD22C1-E1F2-417F-87BA-09D7600F3B93' 'dev.lynx.dummyapp' data")
        assertContains(command[2], "Documents/dummyapp.db")
    }

    private fun cli(runner: RecordingRunner) = NativeCli(runner, NoopNetworkBackend)

    private class RecordingRunner : NativeProcessRunner {
        val commands = mutableListOf<List<String>>()

        override fun run(command: List<String>): NativeCommandResult {
            commands += command
            return NativeCommandResult(0, "databases/dummyapp.db\n")
        }
    }

    private data object NoopNetworkBackend : NativeNetworkBackend {
        override fun execute(command: NetworkCommand): NetworkCommandResult = NetworkCommandResult.Stopped
        override fun worker(port: Int) = Unit
    }
}
