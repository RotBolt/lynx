package dev.lynx.network

import dev.lynx.adb.CommandResult
import dev.lynx.adb.CommandRunner
import java.nio.file.Path
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertTrue

class CertificateInstallersTest {
    private class RecordingRunner : CommandRunner {
        val commands = mutableListOf<List<String>>()
        override fun run(arguments: List<String>): CommandResult {
            commands += arguments
            return CommandResult(0, "", "")
        }
    }

    @Test
    fun androidStagesAndLaunchesExplicitCertificateInstaller() {
        val runner = RecordingRunner()
        val result = AndroidCertificateInstaller(runner, "adb").install("emulator-5554", Path.of("/tmp/lynx-ca.pem"))
        assertEquals("awaiting_user_confirmation", result.status)
        assertTrue(runner.commands.any { it.contains("push") })
        assertTrue(runner.commands.any { it.contains("android.credentials.INSTALL") })
        assertTrue(runner.commands.any { it.contains("ca") })
    }

    @Test
    fun appleSimulatorUsesSimctlKeychain() {
        val runner = RecordingRunner()
        val result = AppleCertificateInstaller(runner, "xcrun").installSimulator("SIM-1", Path.of("/tmp/lynx-ca.pem"))
        assertEquals("awaiting_user_confirmation", result.status)
        assertEquals(listOf("xcrun", "simctl", "keychain", "SIM-1", "add-root-cert", "/tmp/lynx-ca.pem"), runner.commands.single())
    }
}
