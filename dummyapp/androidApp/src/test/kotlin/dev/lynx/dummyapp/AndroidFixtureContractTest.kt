package dev.lynx.dummyapp

import org.junit.Assert.assertEquals
import org.junit.Test

class AndroidFixtureContractTest {
    @Test
    fun fixtureCoversAllInspectorTransports() {
        assertEquals(
            listOf(TransportKind.HTTP_1_1, TransportKind.HTTP_2, TransportKind.WEBSOCKET),
            TransportKind.entries,
        )
    }
}
