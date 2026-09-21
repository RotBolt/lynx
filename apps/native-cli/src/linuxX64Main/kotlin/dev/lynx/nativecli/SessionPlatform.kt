package dev.lynx.nativecli

import dev.lynx.nativehost.NativeSessionStore
import dev.lynx.nativehost.PosixNativeSessionStore

actual fun nativeSessionStore(): NativeSessionStore = PosixNativeSessionStore()
