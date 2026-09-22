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
    fun iosListUsesTheSameListCommandWithSimulatorAndBundleIdFlags() {
        val runner = RecordingRunner()
        cli(runner).run(
            listOf(
                "db", "list", "--platform", "ios", "--simulator", "25CD22C1-E1F2-417F-87BA-09D7600F3B93",
                "--bundle-id", "dev.lynx.dummyapp",
            ),
        )

        assertEquals(
            listOf("xcrun", "simctl", "get_app_container", "25CD22C1-E1F2-417F-87BA-09D7600F3B93", "dev.lynx.dummyapp", "data"),
            runner.commands[0],
        )
        assertEquals(listOf("find", "/containers/dummyapp", "-name", "*.db", "-type", "f"), runner.commands[1])
    }

    @Test
    fun iosSnapshotUsesTheSameSnapshotCommandWithSimulatorAndBundleIdFlags() {
        val runner = RecordingRunner()
        cli(runner).run(
            listOf(
                "db", "snapshot", "Documents/dummyapp.db", "--platform", "ios",
                "--simulator", "25CD22C1-E1F2-417F-87BA-09D7600F3B93", "--bundle-id", "dev.lynx.dummyapp",
            ),
        )

        assertEquals(
            listOf("xcrun", "simctl", "get_app_container", "25CD22C1-E1F2-417F-87BA-09D7600F3B93", "dev.lynx.dummyapp", "data"),
            runner.commands[0],
        )
        assertEquals(listOf("cp", "/containers/dummyapp/Documents/dummyapp.db", "/tmp/lynx-native-Documents_dummyapp.db.db"), runner.commands[1])
    }

    @Test
    fun androidSnapshotStreamsAdbBytesToTheSnapshotFile() {
        val runner = RecordingRunner()
        cli(runner).run(
            listOf(
                "db", "snapshot", "databases/dummyapp.db", "--platform", "android",
                "--device", "emulator-5554", "--package", "dev.lynx.dummyapp",
            ),
        )

        assertEquals(
            listOf("adb", "-s", "emulator-5554", "exec-out", "run-as", "dev.lynx.dummyapp", "cat", "databases/dummyapp.db"),
            runner.fileCommands.single().first,
        )
        assertEquals("/tmp/lynx-native-databases_dummyapp.db.db", runner.fileCommands.single().second)
    }

    private fun cli(runner: RecordingRunner) = NativeCli(runner, NoopNetworkBackend)

    private class RecordingRunner : NativeProcessRunner {
        val commands = mutableListOf<List<String>>()
        val fileCommands = mutableListOf<Pair<List<String>, String>>()

        override fun run(command: List<String>): NativeCommandResult {
            commands += command
            return if (command.firstOrNull() == "xcrun") NativeCommandResult(0, "/containers/dummyapp\n") else NativeCommandResult(0, "databases/dummyapp.db\n")
        }

        override fun runToFile(command: List<String>, outputPath: String): NativeCommandResult {
            fileCommands += command to outputPath
            return NativeCommandResult(0, "")
        }
    }

    private data object NoopNetworkBackend : NativeNetworkBackend {
        override fun execute(command: NetworkCommand): NetworkCommandResult = NetworkCommandResult.Stopped
        override fun worker(port: Int) = Unit
    }
}
