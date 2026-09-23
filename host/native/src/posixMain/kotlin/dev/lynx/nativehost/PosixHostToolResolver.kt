package dev.lynx.nativehost

import kotlinx.cinterop.toKString
import platform.posix.getenv

@OptIn(kotlinx.cinterop.ExperimentalForeignApi::class)
class PosixHostToolResolver(
    private val environment: Map<String, String> = posixEnvironment(),
    private val userHome: String? = getenv("HOME")?.toKString(),
    private val execute: (List<String>) -> NativeCommandResult = ::runPosixCommand,
    private val hostOs: String = posixHostOs(),
) : HostToolResolver {
    private val delegate = CandidateHostToolResolver(::candidates, ::probe)

    override fun resolve(tool: HostTool): ResolvedTool {
        if (tool in setOf(HostTool.XCRUN, HostTool.SIMCTL) && hostOs != "Darwin") {
            return ResolvedTool(tool, ToolStatus.NOT_APPLICABLE, null, null, "iOS simulator tools require macOS")
        }
        if (tool == HostTool.SIMCTL) return resolveSimctl()
        if (tool == HostTool.XCRUN) {
            val selected = execute(listOf("xcode-select", "-p"))
            if (selected.exitCode != 0) return ResolvedTool(tool, ToolStatus.UNUSABLE, null, null, xcodeReason(selected))
        }
        return delegate.resolve(tool)
    }

    private fun resolveSimctl(): ResolvedTool {
        val selected = execute(listOf("xcode-select", "-p"))
        if (selected.exitCode != 0) return ResolvedTool(HostTool.SIMCTL, ToolStatus.UNUSABLE, null, null, xcodeReason(selected))
        val found = execute(listOf("xcrun", "--find", "simctl"))
        if (found.exitCode != 0) return ResolvedTool(HostTool.SIMCTL, ToolStatus.UNUSABLE, null, null, xcodeReason(found))
        val path = found.stdout.trim()
        if (path.isEmpty()) return ResolvedTool(HostTool.SIMCTL, ToolStatus.UNUSABLE, null, null, "xcrun did not return simctl")
        val probe = execute(listOf(path, "list", "devices", "--json"))
        return if (probe.exitCode == 0) ResolvedTool(HostTool.SIMCTL, ToolStatus.AVAILABLE, path, selected.stdout.trim(), null)
        else ResolvedTool(HostTool.SIMCTL, ToolStatus.UNUSABLE, path, null, xcodeReason(probe))
    }

    private fun xcodeReason(result: NativeCommandResult): String {
        val detail = result.stderr.ifBlank { result.stdout }.trim().ifBlank { "exit ${result.exitCode}" }
        return when {
            detail.contains("license", ignoreCase = true) -> "Xcode license/setup incomplete: $detail"
            detail.contains("runtime", ignoreCase = true) -> "iOS simulator runtime unavailable: $detail"
            detail.contains("CommandLineTools", ignoreCase = true) -> "Xcode Command Line Tools selected; full Xcode is required: $detail"
            else -> "Xcode developer directory unavailable: $detail"
        }
    }

    private fun candidates(tool: HostTool): List<ToolCandidate> {
        val binary = when (tool) {
            HostTool.ADB -> "adb"
            HostTool.XCRUN -> "xcrun"
            HostTool.SIMCTL -> "simctl"
            HostTool.SQLITE3 -> "sqlite3"
            HostTool.OPENSSL -> "openssl"
        }
        val override = environment["LYNX_${tool.name}_PATH"]?.takeIf { it.isNotBlank() }
        if (override != null) return listOf(ToolCandidate(override, "LYNX_${tool.name}_PATH", explicit = true))
        val candidates = mutableListOf<ToolCandidate>()
        if (tool == HostTool.ADB) {
            environment["ANDROID_HOME"]?.takeIf { it.isNotBlank() }?.let { candidates += ToolCandidate("$it/platform-tools/adb", "ANDROID_HOME") }
            environment["ANDROID_SDK_ROOT"]?.takeIf { it.isNotBlank() }?.let { candidates += ToolCandidate("$it/platform-tools/adb", "ANDROID_SDK_ROOT") }
            userHome?.let {
                candidates += ToolCandidate("$it/Library/Android/sdk/platform-tools/adb", "macOS SDK default")
                candidates += ToolCandidate("$it/Android/Sdk/platform-tools/adb", "Linux SDK default")
            }
        }
        environment["PATH"].orEmpty().split(':').filter { it.isNotBlank() }.forEach {
            candidates += ToolCandidate("$it/$binary", "PATH")
        }
        if (tool == HostTool.ADB) {
            candidates += ToolCandidate("/opt/homebrew/bin/adb", "Homebrew")
            candidates += ToolCandidate("/usr/local/bin/adb", "Homebrew")
        }
        if (tool == HostTool.XCRUN) candidates += ToolCandidate("/usr/bin/xcrun", "Xcode")
        return candidates.distinctBy { it.path }
    }

    private fun probe(path: String, tool: HostTool): ToolProbe {
        val command = when (tool) {
            HostTool.SIMCTL -> listOf("xcrun", "--find", "simctl")
            HostTool.ADB -> listOf(path, "version")
            HostTool.XCRUN -> listOf(path, "--version")
            HostTool.SQLITE3 -> listOf(path, "--version")
            HostTool.OPENSSL -> listOf(path, "version")
        }
        val result = execute(command)
        return if (result.exitCode == 0) ToolProbe.available(result.stdout.lineSequence().firstOrNull()?.trim())
        else if (result.exitCode == 127) ToolProbe.missing(result.stderr.ifBlank { "not found" })
        else ToolProbe.unusable(result.stderr.ifBlank { "exit ${result.exitCode}" })
    }
}

@OptIn(kotlinx.cinterop.ExperimentalForeignApi::class)
private fun posixEnvironment(): Map<String, String> = listOf(
    "ANDROID_HOME", "ANDROID_SDK_ROOT", "PATH", "LYNX_ADB_PATH", "LYNX_XCRUN_PATH", "LYNX_SIMCTL_PATH", "LYNX_SQLITE3_PATH", "LYNX_OPENSSL_PATH",
).mapNotNull { key -> getenv(key)?.toKString()?.let { key to it } }.toMap()

private fun posixHostOs(): String = runPosixCommand(listOf("uname", "-s")).stdout.trim()
