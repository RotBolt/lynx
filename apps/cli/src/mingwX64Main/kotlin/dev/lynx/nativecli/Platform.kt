package dev.lynx.nativecli

import dev.lynx.nativehost.NativeProcessRunner
import dev.lynx.nativehost.WindowsNativeProcessRunner

actual fun nativeProcessRunner(): NativeProcessRunner = WindowsNativeProcessRunner()
