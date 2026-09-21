package dev.lynx.nativecli

import dev.lynx.nativehost.NativeSessionStore
import dev.lynx.nativehost.InMemoryNativeSessionStore
import dev.lynx.nativehost.NativeNetworkInspector
import dev.lynx.model.NetworkCommand
import dev.lynx.model.NetworkCommandResult
import dev.lynx.model.NetworkCapabilities

actual fun nativeSessionStore(): NativeSessionStore = InMemoryNativeSessionStore()
actual fun nativeNetworkInspector(): NativeNetworkInspector = NativeNetworkInspector { command ->
    when (command) {
        is NetworkCommand.Doctor -> NetworkCommandResult.Diagnostics(NetworkCapabilities(httpsMitm = false, limitations = listOf("native Windows proxy is under construction")))
        else -> error("native Windows networking is under construction")
    }
}
actual fun nativeNetworkWorker(port: Int) { error("native Windows networking is under construction") }
