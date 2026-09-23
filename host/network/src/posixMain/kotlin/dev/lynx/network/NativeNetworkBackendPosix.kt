package dev.lynx.network

import dev.lynx.model.NetworkCommand
import dev.lynx.model.NetworkCommandResult
import dev.lynx.nativehost.PosixNativeNetworkInspector

private class PosixNativeNetworkBackend(
    private val delegate: PosixNativeNetworkInspector = PosixNativeNetworkInspector(),
) : NativeNetworkBackend {
    override fun execute(command: NetworkCommand): NetworkCommandResult = delegate.execute(command)
    override fun worker(port: Int) = delegate.worker(port)
    override fun supervisor(port: Int) = delegate.supervisor(port)
}

actual fun nativeNetworkBackend(): NativeNetworkBackend = PosixNativeNetworkBackend()
