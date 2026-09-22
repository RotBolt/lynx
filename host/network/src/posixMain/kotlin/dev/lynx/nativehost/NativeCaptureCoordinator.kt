package dev.lynx.nativehost

import dev.lynx.model.CaptureSession
import dev.lynx.model.CaptureState
import dev.lynx.model.CaptureTarget

/** One active capture protects proxy leases from cross-target replacement. */
class NativeCaptureCoordinator(
    private val repository: CaptureRepository,
    private val idProvider: () -> String,
    private val now: () -> Long = { kotlin.time.Clock.System.now().toEpochMilliseconds() },
) {
    fun start(attachmentId: String, target: CaptureTarget): CaptureSession {
        val active = repository.sessions().firstOrNull { it.state in setOf(CaptureState.STARTING, CaptureState.RUNNING, CaptureState.STOPPING) }
        if (active != null) {
            if (active.target == target) return active
            throw IllegalStateException("CAPTURE_ACTIVE")
        }
        val session = CaptureSession(idProvider(), attachmentId, target, CaptureState.STARTING, now())
        repository.create(session)
        check(repository.transition(session.id, CaptureState.STARTING, CaptureState.RUNNING))
        return repository.session(session.id)!!
    }

    fun stop(id: String): CaptureSession? {
        val current = repository.session(id) ?: return null
        if (current.state == CaptureState.RUNNING) repository.transition(id, CaptureState.RUNNING, CaptureState.STOPPING)
        if (repository.session(id)?.state == CaptureState.STOPPING) repository.transition(id, CaptureState.STOPPING, CaptureState.STOPPED)
        return repository.session(id)
    }

    fun interrupt(id: String): CaptureSession? {
        val current = repository.session(id) ?: return null
        if (current.state in setOf(CaptureState.STARTING, CaptureState.RUNNING, CaptureState.STOPPING)) repository.transition(id, current.state, CaptureState.INTERRUPTED)
        return repository.session(id)
    }
}
