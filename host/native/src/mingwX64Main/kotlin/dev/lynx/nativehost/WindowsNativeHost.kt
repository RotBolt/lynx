package dev.lynx.nativehost

/** Windows native process/filesystem adapters are intentionally not claimed yet. */
class WindowsNativeProcessRunner : NativeProcessRunner {
    override fun run(command: List<String>): NativeCommandResult =
        NativeCommandResult(126, "", "Windows native host support is under construction")
}
