package dev.lynx.nativecli

import dev.lynx.nativehost.NativeProcessRunner
import dev.lynx.nativehost.PosixProcessRunner

actual fun nativeProcessRunner(): NativeProcessRunner = PosixProcessRunner()
