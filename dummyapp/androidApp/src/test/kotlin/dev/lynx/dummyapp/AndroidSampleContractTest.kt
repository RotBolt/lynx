package dev.lynx.dummyapp

import org.junit.Assert.assertEquals
import org.junit.Test

class AndroidSampleContractTest {
    @Test
    fun sampleProvidesExplicitActionsForAllInspectorTransports() {
        assertEquals(
            listOf(TransportKind.HTTP_1_1, TransportKind.HTTP_2, TransportKind.WEBSOCKET),
            TransportKind.entries,
        )
    }
}
