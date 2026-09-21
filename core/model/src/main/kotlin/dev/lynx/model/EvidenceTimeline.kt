package dev.lynx.model

import java.time.Instant

data class EvidenceFilter(
    val sessionId: SessionId? = null,
    val source: EvidenceSource? = null,
    val from: Instant? = null,
    val to: Instant? = null,
    val limit: Int? = null,
) {
    init { require(limit == null || limit > 0) { "limit must be positive" } }
}

data class EvidenceSummary(
    val id: EvidenceId,
    val sessionId: SessionId,
    val observedAt: Instant,
    val source: EvidenceSource,
    val description: String,
)

interface EvidenceTimeline {
    fun append(evidence: Evidence)
    fun query(filter: EvidenceFilter = EvidenceFilter()): List<Evidence>
    fun network(filter: EvidenceFilter = EvidenceFilter(source = EvidenceSource.NETWORK)): List<NetworkExchange>
    fun snapshots(databaseId: DatabaseId? = null, filter: EvidenceFilter = EvidenceFilter(source = EvidenceSource.DATABASE)): List<DatabaseSnapshot>
    fun between(from: Instant, to: Instant): List<EvidenceSummary>
}

class InMemoryEvidenceTimeline(private val maxItems: Int = 10_000) : EvidenceTimeline {
    init { require(maxItems > 0) { "maxItems must be positive" } }
    private val items = ArrayDeque<Evidence>()

    @Synchronized override fun append(evidence: Evidence) {
        items.addLast(evidence)
        while (items.size > maxItems) items.removeFirst()
    }

    @Synchronized override fun query(filter: EvidenceFilter): List<Evidence> = items.asSequence()
        .filter { filter.sessionId == null || it.meta.sessionId == filter.sessionId }
        .filter { filter.source == null || it.meta.source == filter.source }
        .filter { filter.from == null || !it.meta.observedAt.isBefore(filter.from) }
        .filter { filter.to == null || !it.meta.observedAt.isAfter(filter.to) }
        .sortedByDescending { it.meta.observedAt }
        .let { sequence -> if (filter.limit == null) sequence.toList() else sequence.take(filter.limit).toList() }

    override fun network(filter: EvidenceFilter): List<NetworkExchange> = query(filter).filterIsInstance<NetworkExchange>()

    override fun snapshots(databaseId: DatabaseId?, filter: EvidenceFilter): List<DatabaseSnapshot> =
        query(filter).filterIsInstance<DatabaseSnapshot>().filter { databaseId == null || it.databaseId == databaseId }

    override fun between(from: Instant, to: Instant): List<EvidenceSummary> = query(EvidenceFilter(from = from, to = to)).map {
        EvidenceSummary(it.meta.id, it.meta.sessionId, it.meta.observedAt, it.meta.source, it.description())
    }

    private fun Evidence.description(): String = when (this) {
        is NetworkExchange -> "${request.method} ${request.url}"
        is DatabaseSnapshot -> "database snapshot ${snapshotId.value}"
        is RuntimeAttribution -> "runtime attribution"
    }
}
