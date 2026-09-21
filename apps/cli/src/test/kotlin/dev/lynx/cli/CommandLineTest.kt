package dev.lynx.cli

import kotlin.test.Test
import kotlin.test.assertEquals

class CommandLineTest {
    @Test
    fun parsesVersionAndDoctorCommands() {
        assertEquals(CliCommand.VERSION, CommandLine.parse(arrayOf("--version")))
        assertEquals(CliCommand.DOCTOR, CommandLine.parse(arrayOf("doctor")))
        assertEquals(CliCommand.DAEMON, CommandLine.parse(arrayOf("daemon")))
        assertEquals(CliCommand.DEVICES, CommandLine.parse(arrayOf("devices")))
    }

    @Test
    fun parsesAttachWithExplicitTargetForTheHostSessionSmokeTest() {
        assertEquals(
            CliInvocation.Attach("emulator-5554", "com.example.app"),
            CommandLine.parseInvocation(
                arrayOf("attach", "--device", "emulator-5554", "--package", "com.example.app"),
            ),
        )
    }

    @Test
    fun parsesStatusAndDetach() {
        assertEquals(CliInvocation.Status, CommandLine.parseInvocation(arrayOf("status")))
        assertEquals(CliInvocation.Detach, CommandLine.parseInvocation(arrayOf("detach")))
    }
}
