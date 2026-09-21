package dev.lynx.nativecli

import dev.lynx.nativehost.NativeSessionStore
import dev.lynx.nativehost.InMemoryNativeSessionStore
import dev.lynx.nativehost.NativeCertificateManager
import dev.lynx.nativehost.NativeCertificateState

actual fun nativeSessionStore(): NativeSessionStore = InMemoryNativeSessionStore()
actual fun nativeCertificateManager(): NativeCertificateManager = object : NativeCertificateManager {
    override fun show() = errorState()
    override fun install() = errorState()
    override fun remove() = errorState()
    private fun errorState() = NativeCertificateState(false, "", trustStatus = "under_construction", instructions = listOf("Windows native CA support is under construction"))
}
