package dev.lynx.nativecli

import dev.lynx.nativehost.NativeSessionStore
import dev.lynx.nativehost.InMemoryNativeSessionStore
import dev.lynx.nativehost.NativeNetworkInspector
import dev.lynx.model.NetworkCommand
import dev.lynx.model.NetworkCommandResult
import dev.lynx.model.NetworkCapabilities
import dev.lynx.nativehost.NativeCertificateManager
import dev.lynx.nativehost.NativeCertificateState

actual fun nativeSessionStore(): NativeSessionStore = InMemoryNativeSessionStore()
actual fun nativeNetworkInspector(): NativeNetworkInspector = NativeNetworkInspector { command ->
    when (command) {
        is NetworkCommand.Doctor -> NetworkCommandResult.Diagnostics(NetworkCapabilities(httpsMitm = false, limitations = listOf("native Windows proxy is under construction")))
        else -> error("native Windows networking is under construction")
    }
}
actual fun nativeNetworkWorker(port: Int) { error("native Windows networking is under construction") }
actual fun nativeCertificateManager(): NativeCertificateManager = object : NativeCertificateManager {
    override fun show() = errorState()
    override fun install() = errorState()
    override fun remove() = errorState()
    private fun errorState() = NativeCertificateState(false, "", trustStatus = "under_construction", instructions = listOf("Windows native CA support is under construction"))
}
