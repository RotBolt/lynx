package dev.lynx.nativecli

import dev.lynx.model.NetworkCommand
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFailsWith

class NetworkInspectionCommandsTest {
    @Test
    fun listWithoutSessionIsSessionCatalog() {
        assertEquals(NetworkCommand.Sessions, NetworkInspectionCommands.parse(listOf("list", "--json")))
    }

    @Test
    fun listAcceptsBothSessionSpellings() {
        assertEquals(
            NetworkCommand.SessionList("capture_a"),
            NetworkInspectionCommands.parse(listOf("list", "--session=capture_a")),
        )
        assertEquals(
            NetworkCommand.SessionList("capture_a"),
            NetworkInspectionCommands.parse(listOf("list", "--session", "capture_a")),
        )
    }

    @Test
    fun catalogRejectsExchangeFiltersWithoutSession() {
        assertFailsWith<IllegalStateException> {
            NetworkInspectionCommands.parse(listOf("list", "--method", "GET"))
        }
    }

    @Test
    fun snapshotIsAnExplicitCommand() {
        assertEquals(NetworkCommand.Snapshot, NetworkInspectionCommands.parse(listOf("snapshot")))
    }
}
