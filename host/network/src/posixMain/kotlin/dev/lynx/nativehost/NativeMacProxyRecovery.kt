package dev.lynx.nativehost

import kotlin.time.Clock

class NativeMacProxyRecovery(
    private val controller: NativeMacSystemProxyController,
    private val leases: NativeMacProxyLeaseStore,
) {
    fun prepareLease(endpoint: String, port: Int): NativeMacProxyLease {
        val existing = leases.load()
        if (existing != null && existing.hasAppliedSettings() && !existing.isFullyRestored()) return existing
        val snapshot = controller.inspect()
        val installed = NativeMacProxySetting(enabled = true, server = "127.0.0.1", port = port.toString())
        val lease = NativeMacProxyLease(
            leaseId = "mac_${Clock.System.now().toEpochMilliseconds()}",
            service = snapshot.service,
            endpoint = endpoint,
            webOriginal = snapshot.web,
            secureOriginal = snapshot.secure,
            webInstalled = installed,
            secureInstalled = installed,
        )
        leases.save(lease)
        return lease
    }

    fun activatePreparedLease(lease: NativeMacProxyLease): NativeMacProxyLease {
        var current = lease.copy(phase = NativeMacProxyLeasePhase.APPLYING, lastError = null)
        leases.save(current)
        try {
            if (!current.webApplied) {
                current = current.copy(webApplied = true)
                leases.save(current)
                controller.applyWeb(current)
            }
            if (!current.secureApplied) {
                current = current.copy(secureApplied = true)
                leases.save(current)
                controller.applySecure(current)
            }
            current = current.copy(phase = NativeMacProxyLeasePhase.ACTIVE)
            leases.save(current)
            return current
        } catch (error: Throwable) {
            leases.save(current.copy(lastError = error.message ?: error::class.simpleName))
            throw error
        }
    }

    fun restoreOwnedLease() {
        val lease = leases.load() ?: return
        if (!lease.hasAppliedSettings()) return
        val ownershipLease = lease
        var current = lease.copy(phase = NativeMacProxyLeasePhase.RESTORING, lastError = null)
        leases.save(current)
        val errors = mutableListOf<String>()
        if (current.webApplied && !current.webRestored) {
            runCatching { controller.restoreWebIfOwned(current.copy(phase = ownershipLease.phase)) }
                .onSuccess {
                    current = current.copy(webRestored = true)
                    leases.save(current)
                }
                .onFailure { errors += it.message ?: it::class.simpleName.orEmpty() }
        }
        if (current.secureApplied && !current.secureRestored) {
            runCatching { controller.restoreSecureIfOwned(current.copy(phase = ownershipLease.phase)) }
                .onSuccess {
                    current = current.copy(secureRestored = true)
                    leases.save(current)
                }
                .onFailure { errors += it.message ?: it::class.simpleName.orEmpty() }
        }
        if (errors.isEmpty() && current.isFullyRestored()) {
            leases.clear()
        } else if (errors.isNotEmpty()) {
            leases.save(current.copy(lastError = errors.joinToString("; ")))
            error(errors.joinToString("; "))
        }
    }
}
