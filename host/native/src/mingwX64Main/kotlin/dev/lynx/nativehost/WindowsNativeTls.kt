package dev.lynx.nativehost

actual fun nativeTlsProvider(): NativeTlsProvider = error("native Windows TLS is under construction")
