package dev.lynx.nativehost

import dev.lynx.model.CaptureTarget
import dev.lynx.model.ProcessIdentity
import dev.lynx.model.SocketTuple
import dev.lynx.model.VerifiedOrigin
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertIs

class ConnectionAdmissionTest {
    private val target = CaptureTarget("ios", "SIM-1", "dev.lynx.dummyapp")
    private val socket = SocketTuple("127.0.0.1", 62006, "127.0.0.1", 51001)

    @Test
    fun targetOwnershipAdmitsInterceptionWithTheVerifiedOrigin() {
        val origin = VerifiedOrigin(target, ProcessIdentity(42, "start-42"), method = "libproc")
        val admission = ConnectionAdmission(ConnectionOwnerResolver { _, _ -> OwnershipDecision.Target(origin) })

        val result = admission.decide(target, socket)

        assertIs<ConnectionAdmission.Decision.Intercept>(result)
        assertEquals(origin, result.origin)
    }

    @Test
    fun foreignAndUnknownOwnershipNeverAdmitInterception() {
        val other = ConnectionAdmission(ConnectionOwnerResolver { _, _ -> OwnershipDecision.Other })
        val unknown = ConnectionAdmission(ConnectionOwnerResolver { _, _ -> OwnershipDecision.Unknown("socket closed") })

        assertIs<ConnectionAdmission.Decision.PassThrough>(other.decide(target, socket))
        assertIs<ConnectionAdmission.Decision.PassThrough>(unknown.decide(target, socket))
    }
}
