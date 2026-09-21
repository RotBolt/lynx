package dev.lynx.nativecli

import dev.lynx.nativehost.NativeSessionStore
import dev.lynx.nativehost.PosixNativeSessionStore
import dev.lynx.nativehost.NativeNetworkInspector
import dev.lynx.nativehost.PosixNativeNetworkInspector

actual fun nativeSessionStore(): NativeSessionStore = PosixNativeSessionStore()
actual fun nativeNetworkInspector(): NativeNetworkInspector = PosixNativeNetworkInspector()
actual fun nativeNetworkWorker(port: Int) { PosixNativeNetworkInspector().worker(port) }
