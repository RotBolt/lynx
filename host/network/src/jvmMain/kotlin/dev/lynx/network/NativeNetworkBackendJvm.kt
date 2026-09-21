package dev.lynx.network

import dev.lynx.model.NetworkCommand
import dev.lynx.model.NetworkCommandResult

actual fun nativeNetworkBackend(): NativeNetworkBackend = object : NativeNetworkBackend {
    override fun execute(command: NetworkCommand): NetworkCommandResult =
        error("native network backend is only available in the standalone KMP executable")

    override fun worker(port: Int): Unit =
        error("native network worker is only available in the standalone KMP executable")
}
