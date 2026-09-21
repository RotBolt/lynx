package dev.lynx.session

import dev.lynx.model.InspectorSession
import dev.lynx.model.SessionId
import dev.lynx.model.SessionStatus
import java.util.UUID
import java.util.concurrent.ConcurrentHashMap

class SessionRegistry {
    private val sessions = ConcurrentHashMap<SessionId, InspectorSession>()

    fun attach(
        deviceSerial: String,
        packageName: String,
        pid: Int,
        protocolVersion: Int,
    ): InspectorSession {
        require(deviceSerial.isNotBlank()) { "deviceSerial must not be blank" }
        require(packageName.isNotBlank()) { "packageName must not be blank" }
        require(pid > 0) { "pid must be positive" }
        require(protocolVersion > 0) { "protocolVersion must be positive" }

        val session = InspectorSession(
            id = SessionId("session_${UUID.randomUUID()}"),
            deviceSerial = deviceSerial,
            packageName = packageName,
            pid = pid,
            protocolVersion = protocolVersion,
            status = SessionStatus.ACTIVE,
        )
        sessions[session.id] = session
        return session
    }

    fun get(id: SessionId): InspectorSession? = sessions[id]

    /**
     * Updates the process binding without creating a new logical session.
     * A package restart changes the PID, but the agent-facing session identity
     * must remain stable for the lifetime of an attach.
     */
    fun rebind(id: SessionId, pid: Int): InspectorSession? {
        require(pid > 0) { "pid must be positive" }
        var updated: InspectorSession? = null
        sessions.computeIfPresent(id) { _, current ->
            current.copy(pid = pid).also { updated = it }
        }
        return updated
    }

    fun activeSessions(): List<InspectorSession> = sessions.values.toList()

    fun detach(id: SessionId) {
        sessions.remove(id)
    }
}
