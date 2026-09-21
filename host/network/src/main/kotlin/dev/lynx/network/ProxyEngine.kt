package dev.lynx.network

import dev.lynx.daemon.NetworkCaptureConfig
import dev.lynx.daemon.NetworkCapabilities
import dev.lynx.model.NetworkExchange
import dev.lynx.model.RequestId

/** Protocol-neutral boundary for replaceable host-side proxy implementations. */
internal interface ProxyEngine {
    val endpoint: String?
    suspend fun start(config: NetworkCaptureConfig)
    suspend fun stop()
    suspend fun capabilities(): NetworkCapabilities
    fun exchanges(): List<NetworkExchange>
    fun exchange(requestId: RequestId): NetworkExchange?
}
