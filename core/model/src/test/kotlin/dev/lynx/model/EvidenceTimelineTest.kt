package dev.lynx.model

import java.time.Instant
import kotlin.test.Test
import kotlin.test.assertEquals

class EvidenceTimelineTest {
    @Test
    fun retainsNewestItemsAndFiltersBySource() {
        val timeline = InMemoryEvidenceTimeline(maxItems = 2)
        val first = network("ev_1", "2026-08-29T10:00:00Z")
        val second = database("ev_2", "2026-08-29T10:00:02Z")
        val third = network("ev_3", "2026-08-29T10:00:01Z")
        timeline.append(first)
        timeline.append(second)
        timeline.append(third)

        assertEquals(listOf("ev_2", "ev_3"), timeline.query(EvidenceFilter()).map { it.meta.id.value })
        assertEquals(listOf("ev_3"), timeline.query(EvidenceFilter(source = EvidenceSource.NETWORK)).map { it.meta.id.value })
    }

    private fun meta(id: String, time: String, source: EvidenceSource) = EvidenceMeta(
        EvidenceId(id), SessionId("s"), Instant.parse(time), source, "d", "p", null,
    )

    private fun network(id: String, time: String) = NetworkExchange(
        meta(id = id, time = time, source = EvidenceSource.NETWORK), RequestId(id),
        NetworkRequest("GET", "https://example.test", emptyMap(), null), null, null,
        NetworkTiming(0, null, null), NetworkCaptureMetadata(false, 0, null),
    )

    private fun database(id: String, time: String) = DatabaseSnapshot(
        meta(id = id, time = time, source = EvidenceSource.DATABASE), SnapshotId(id),
        DatabaseId("db"), DatabaseFingerprint("f", emptyList()), "/tmp/db", true, "test",
    )
}
