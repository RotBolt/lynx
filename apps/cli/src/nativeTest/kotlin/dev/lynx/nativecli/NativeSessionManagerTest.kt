package dev.lynx.nativecli

import dev.lynx.nativehost.InMemoryNativeSessionStore
import dev.lynx.nativehost.NativeCommandResult
import dev.lynx.nativehost.NativeProcessRunner
import dev.lynx.nativehost.NativeSessionManager
import kotlin.test.Test
import kotlin.test.assertContains
import kotlin.test.assertEquals
import kotlin.test.assertNull

class NativeSessionManagerTest {
    @Test
    fun attachesToIosSimulatorUsingSimctlLaunchPid() {
        val store = InMemoryNativeSessionStore()
        val udid = "25CD22C1-E1F2-417F-87BA-09D7600F3B93"
        val runner = RecordingRunner(listOf("dev.lynx.dummyapp: 12345\n"))
        val manager = NativeSessionManager(runner, store) { "session_ios" }

        val attached = manager.attach("ios-simulator:$udid", "dev.lynx.dummyapp")

        assertContains(attached, "device=ios-simulator:$udid package=dev.lynx.dummyapp pid=12345")
        assertEquals(
            listOf("xcrun", "simctl", "launch", udid, "dev.lynx.dummyapp"),
            runner.commands.single(),
        )
    }

    @Test
    fun attachPersistsMetadataAndRebindsPidDuringStatus() {
        val store = InMemoryNativeSessionStore()
        val runner = RecordingRunner(listOf("4321\n", "8765\n"))
        val manager = NativeSessionManager(runner, store) { "session_fixed" }

        val attached = manager.attach("emulator-5554", "ai.sarvam.prep.app")
        val status = manager.status()

        assertContains(attached, "OK ATTACHED id=session_fixed device=emulator-5554 package=ai.sarvam.prep.app pid=4321")
        assertContains(status, "OK ACTIVE id=session_fixed device=emulator-5554 package=ai.sarvam.prep.app pid=8765")
        assertEquals(8765, store.load()?.processId)
        assertEquals(
            listOf(
                listOf("adb", "-s", "emulator-5554", "shell", "pidof", "ai.sarvam.prep.app"),
                listOf("adb", "-s", "emulator-5554", "shell", "pidof", "ai.sarvam.prep.app"),
            ),
            runner.commands,
        )
    }

    @Test
    fun detachClearsPersistedState() {
        val store = InMemoryNativeSessionStore()
        val manager = NativeSessionManager(RecordingRunner(listOf("4321\n")), store) { "session_fixed" }
        manager.attach("emulator-5554", "ai.sarvam.prep.app")

        assertEquals("OK DETACHED", manager.detach())
        assertEquals("ERROR NOT_ATTACHED", manager.status())
        assertNull(store.load())
    }

    private class RecordingRunner(outputs: List<String>) : NativeProcessRunner {
        private val responses = outputs.toMutableList()
        val commands = mutableListOf<List<String>>()

        override fun run(command: List<String>): NativeCommandResult {
            commands += command
            return NativeCommandResult(0, responses.removeAt(0))
        }
    }
}
