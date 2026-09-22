package dev.lynx.nativehost

import dev.lynx.model.EvidenceId
import dev.lynx.model.EvidenceMeta
import dev.lynx.model.EvidenceSource
import dev.lynx.model.SessionId

/** Attaches proxy evidence to the app session persisted by the CLI before the worker starts. */
internal fun nativeNetworkEvidenceMeta(id: EvidenceId, session: NativeSession?): EvidenceMeta = EvidenceMeta(
    id = id,
    sessionId = SessionId(session?.id ?: "native"),
    observedAt = kotlin.time.Clock.System.now(),
    source = EvidenceSource.NETWORK,
    deviceSerial = session?.deviceSerial ?: "native",
    packageName = session?.packageName ?: "unknown",
    processId = session?.processId,
)
