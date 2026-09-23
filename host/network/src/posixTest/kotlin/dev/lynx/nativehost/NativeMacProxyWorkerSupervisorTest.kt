package dev.lynx.nativehost

import kotlin.test.Test
import kotlin.test.assertFalse
import kotlin.test.assertFailsWith
import kotlin.test.assertTrue

class NativeMacProxyWorkerSupervisorTest {
    @Test
    fun restoresAndClearsStateWhenRecordedWorkerIdentityIsNoLongerLive() {
        var restored = false
        var cleared = false
        val supervisor = NativeMacProxyWorkerSupervisor(
            worker = NativeWorkerIdentity(pid = 42, startIdentity = "start-1"),
            identityForPid = { null },
            listenerHealthy = { false },
            restoreOwnedProxy = { restored = true },
            clearRuntime = { cleared = true },
        )

        assertFalse(supervisor.checkOnce())
        assertTrue(restored)
        assertTrue(cleared)
    }

    @Test
    fun keepsCaptureWhenRecordedWorkerAndListenerAreHealthy() {
        var restored = false
        val supervisor = NativeMacProxyWorkerSupervisor(
            worker = NativeWorkerIdentity(pid = 42, startIdentity = "start-1"),
            identityForPid = { "start-1" },
            listenerHealthy = { true },
            restoreOwnedProxy = { restored = true },
            clearRuntime = {},
        )

        assertTrue(supervisor.checkOnce())
        assertFalse(restored)
    }

    @Test
    fun retainsRuntimeStateWhenRestorationFails() {
        var cleared = false
        val supervisor = NativeMacProxyWorkerSupervisor(
            worker = NativeWorkerIdentity(pid = 42, startIdentity = "start-1"),
            identityForPid = { null },
            listenerHealthy = { false },
            restoreOwnedProxy = { error("PROXY_OWNERSHIP_CONFLICT") },
            clearRuntime = { cleared = true },
        )

        assertFailsWith<IllegalStateException> { supervisor.checkOnce() }
        assertFalse(cleared)
    }
}
