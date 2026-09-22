package dev.lynx.nativehost

import dev.lynx.model.NetworkCommand
import dev.lynx.model.NetworkCommandResult
import dev.lynx.model.NetworkCapabilities
import dev.lynx.model.NetworkExchange
import dev.lynx.model.NetworkFilter
import dev.lynx.model.RequestId

/** Platform-neutral boundary used by the native CLI and native proxy adapters. */
fun interface NativeNetworkInspector {
    fun execute(command: NetworkCommand): NetworkCommandResult
}

/** Persistent boundary shared by independent native CLI invocations. */
interface NativeNetworkStateStore {
    fun isRunning(): Boolean
    fun endpoint(): String?
    fun capabilities(): NetworkCapabilities?
    fun setRunning(endpoint: String, capabilities: NetworkCapabilities, previousProxy: Map<String, String?>? = null)
    fun previousProxy(): Map<String, String?>?
    fun clearRunning()
    fun append(exchange: NetworkExchange)
    fun list(filter: NetworkFilter = NetworkFilter()): List<NetworkExchange>
    fun get(requestId: RequestId): NetworkExchange?
}
