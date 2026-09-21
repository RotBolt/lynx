package dev.lynx.session

import dev.lynx.model.SessionStatus
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertNull

class SessionRegistryTest {
    @Test
    fun attachCreatesAnActiveSessionWithStableIdentity() {
        val registry = SessionRegistry()

        val session = registry.attach(
            deviceSerial = "emulator-5554",
            packageName = "com.example.app",
            pid = 1234,
            protocolVersion = 1,
        )

        assertEquals(SessionStatus.ACTIVE, session.status)
        assertEquals("emulator-5554", session.deviceSerial)
        assertEquals("com.example.app", session.packageName)
        assertEquals(1234, session.pid)
        assertEquals(session, registry.get(session.id))
    }

    @Test
    fun detachRemovesTheSessionAndIsIdempotent() {
        val registry = SessionRegistry()
        val session = registry.attach("emulator-5554", "com.example.app", 1234, 1)

        registry.detach(session.id)
        registry.detach(session.id)

        assertNull(registry.get(session.id))
    }

    @Test
    fun rebindChangesPidButKeepsSessionIdentity() {
        val registry = SessionRegistry()
        val session = registry.attach("emulator-5554", "com.example.app", 1234, 1)

        val rebound = registry.rebind(session.id, 5678)

        assertEquals(session.id, rebound?.id)
        assertEquals(5678, rebound?.pid)
        assertEquals(5678, registry.get(session.id)?.pid)
    }
}
