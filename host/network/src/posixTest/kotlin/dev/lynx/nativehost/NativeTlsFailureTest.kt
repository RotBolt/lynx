package dev.lynx.nativehost

import kotlin.test.Test
import kotlin.test.assertEquals

class NativeTlsFailureTest {
    @Test
    fun preservesTheSpecificTlsStageForAgentVisibleFailures() {
        val failure = NativeTlsFailure("TLS_CLIENT_REJECTED_CERTIFICATE", IllegalStateException("certificate unknown"))

        assertEquals("TLS_CLIENT_REJECTED_CERTIFICATE", nativeTlsFailureKind(failure))
    }

    @Test
    fun keepsUnexpectedFailuresInTheGenericMitmBucket() {
        assertEquals("HTTPS_MITM_ERROR", nativeTlsFailureKind(IllegalStateException("unexpected")))
    }
}
