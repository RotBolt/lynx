package dev.lynx.nativecli

import dev.lynx.nativehost.NativeSessionStore
import dev.lynx.nativehost.InMemoryNativeSessionStore

actual fun nativeSessionStore(): NativeSessionStore = InMemoryNativeSessionStore()
