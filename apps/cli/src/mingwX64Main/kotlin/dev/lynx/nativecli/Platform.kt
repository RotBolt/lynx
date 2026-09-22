package dev.lynx.nativecli

import dev.lynx.nativehost.NativeProcessRunner
import dev.lynx.nativehost.HostToolResolver
import dev.lynx.nativehost.ResolvedTool
import dev.lynx.nativehost.ToolStatus
import dev.lynx.nativehost.WindowsNativeProcessRunner

actual fun nativeProcessRunner(): NativeProcessRunner = WindowsNativeProcessRunner()
actual fun nativeHostToolResolver(): HostToolResolver = HostToolResolver { ResolvedTool(it, ToolStatus.NOT_APPLICABLE, null, null, "native Windows discovery not implemented") }
