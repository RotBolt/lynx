package dev.lynx.daemon

import dev.lynx.model.DatabaseId
import dev.lynx.model.EvidenceSource
import dev.lynx.model.SessionId
import kotlin.test.Test
import kotlin.test.assertEquals

class SourceContractsTest {
    @Test
    fun capabilitiesIdentifySupportedEvidenceSources() {
        val network = NetworkCapabilities(EvidenceSource.NETWORK, httpsMitm = true, maxBodyBytes = 4096, limitations = listOf("pinning may fail"))
        val database = DatabaseCapabilities(EvidenceSource.DATABASE, supportsWal = true, readOnly = true, limitations = emptyList())
        assertEquals(EvidenceSource.NETWORK, network.source)
        assertEquals(EvidenceSource.DATABASE, database.source)
        assertEquals(true, database.readOnly)
    }

    @Test
    fun databaseDescriptorCarriesSessionContext() {
        val descriptor = DatabaseDescriptor(SessionId("s"), DatabaseId("db"), "app.db", 123L)
        assertEquals("app.db", descriptor.name)
        assertEquals(123L, descriptor.sizeBytes)
    }
}
