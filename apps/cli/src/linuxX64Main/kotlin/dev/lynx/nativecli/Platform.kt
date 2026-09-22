package dev.lynx.nativecli

import dev.lynx.nativehost.NativeProcessRunner
import dev.lynx.nativehost.PosixHostToolResolver
import dev.lynx.nativehost.PosixProcessRunner
import dev.lynx.nativehost.HostToolResolver

actual fun nativeProcessRunner(): NativeProcessRunner = PosixProcessRunner(PosixHostToolResolver())
actual fun nativeHostToolResolver(): HostToolResolver = PosixHostToolResolver()
