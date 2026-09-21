package dev.lynx.nativehost

import platform.Foundation.NSProcessInfo

actual fun nativeExecutablePath(): String? =
    NSProcessInfo.processInfo.arguments.firstOrNull() as? String
