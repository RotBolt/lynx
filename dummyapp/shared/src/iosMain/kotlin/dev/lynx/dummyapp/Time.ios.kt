package dev.lynx.dummyapp

import platform.Foundation.NSProcessInfo

internal actual fun currentEpochMillis(): Long = (NSProcessInfo.processInfo.systemUptime * 1000.0).toLong()
