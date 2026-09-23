package dev.lynx.network

import dev.lynx.model.NetworkCommand
import dev.lynx.model.NetworkCommandResult

/** KMP boundary for the shipped native network implementation. */
interface NativeNetworkBackend {
    fun execute(command: NetworkCommand): NetworkCommandResult
    fun worker(port: Int)
    fun supervisor(port: Int) = Unit
}

expect fun nativeNetworkBackend(): NativeNetworkBackend
