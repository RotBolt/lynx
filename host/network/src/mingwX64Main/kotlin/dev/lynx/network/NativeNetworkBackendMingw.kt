package dev.lynx.network

import dev.lynx.model.NetworkCommand
import dev.lynx.model.NetworkCommandResult

actual fun nativeNetworkBackend(): NativeNetworkBackend = object : NativeNetworkBackend {
    override fun execute(command: NetworkCommand): NetworkCommandResult =
        error("native Windows networking is under construction")

    override fun worker(port: Int): Unit =
        error("native Windows networking is under construction")
}
