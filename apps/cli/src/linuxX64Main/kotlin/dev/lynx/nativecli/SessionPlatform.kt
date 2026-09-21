package dev.lynx.nativecli

import dev.lynx.nativehost.NativeSessionStore
import dev.lynx.nativehost.PosixNativeSessionStore
import dev.lynx.nativehost.NativeCertificateManager
import dev.lynx.nativehost.PosixNativeCertificateAuthority

actual fun nativeSessionStore(): NativeSessionStore = PosixNativeSessionStore()
actual fun nativeCertificateManager(): NativeCertificateManager = PosixNativeCertificateAuthority()
