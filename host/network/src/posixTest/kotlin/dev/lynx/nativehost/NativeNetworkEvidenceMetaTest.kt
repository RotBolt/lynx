package dev.lynx.nativehost

import dev.lynx.model.EvidenceId
import dev.lynx.model.EvidenceSource
import kotlin.test.Test
import kotlin.test.assertEquals

class NativeNetworkEvidenceMetaTest {
    @Test
    fun attachedSessionIdentityIsPreservedInNetworkEvidence() {
        val session = NativeSession(
            id = "session_android_1",
            deviceSerial = "emulator-5554",
            packageName = "dev.lynx.dummyapp",
            processId = 4812,
        )

        val meta = nativeNetworkEvidenceMeta(EvidenceId("ev_test"), session)

        assertEquals("session_android_1", meta.sessionId.value)
        assertEquals(EvidenceSource.NETWORK, meta.source)
        assertEquals("emulator-5554", meta.deviceSerial)
        assertEquals("dev.lynx.dummyapp", meta.packageName)
        assertEquals(4812, meta.processId)
    }

    @Test
    fun iosSimulatorSessionIdentityIsPreservedInNetworkEvidence() {
        val session = NativeSession(
            id = "session_ios_1",
            deviceSerial = "ios-simulator:25CD22C1-E1F2-417F-87BA-09D7600F3B93",
            packageName = "dev.lynx.dummyapp",
            processId = 9123,
        )

        val meta = nativeNetworkEvidenceMeta(EvidenceId("ev_test"), session)

        assertEquals("session_ios_1", meta.sessionId.value)
        assertEquals("ios-simulator:25CD22C1-E1F2-417F-87BA-09D7600F3B93", meta.deviceSerial)
        assertEquals("dev.lynx.dummyapp", meta.packageName)
        assertEquals(9123, meta.processId)
    }
}
