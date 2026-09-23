package dev.lynx.nativehost

import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertTrue

class HostToolResolverTest {
    @Test
    fun validExplicitOverrideWinsAndRecordsVersion() {
        val result = policy(
            candidates = listOf(ToolCandidate("/tools/adb", "LYNX_ADB_PATH", explicit = true)),
            probes = mapOf("/tools/adb" to ToolProbe.available("Android Debug Bridge version 1.0.41")),
        ).resolve(HostTool.ADB)

        assertEquals(ToolStatus.AVAILABLE, result.status)
        assertEquals("/tools/adb", result.path)
        assertEquals("Android Debug Bridge version 1.0.41", result.version)
    }

    @Test
    fun invalidExplicitOverrideDoesNotSilentlyFallThrough() {
        val result = policy(
            candidates = listOf(
                ToolCandidate("/missing/adb", "LYNX_ADB_PATH", explicit = true),
                ToolCandidate("/sdk/platform-tools/adb", "ANDROID_HOME"),
            ),
            probes = mapOf("/missing/adb" to ToolProbe.unusable("not executable")),
        ).resolve(HostTool.ADB)

        assertEquals(ToolStatus.UNUSABLE, result.status)
        assertTrue(result.reason!!.contains("LYNX_ADB_PATH"))
    }

    @Test
    fun optionalMissingSourceFallsThroughToNextCandidate() {
        val result = policy(
            candidates = listOf(
                ToolCandidate("/sdk/platform-tools/adb", "ANDROID_HOME"),
                ToolCandidate("/path with spaces/adb", "PATH"),
            ),
            probes = mapOf(
                "/sdk/platform-tools/adb" to ToolProbe.missing(),
                "/path with spaces/adb" to ToolProbe.available("adb version"),
            ),
        ).resolve(HostTool.ADB)

        assertEquals(ToolStatus.AVAILABLE, result.status)
        assertEquals("/path with spaces/adb", result.path)
    }

    @Test
    fun nonExecutableAndAbsentCandidatesAreReportedAsMissing() {
        val result = policy(
            candidates = listOf(
                ToolCandidate("/not-executable/sqlite3", "PATH"),
                ToolCandidate("/absent/sqlite3", "Homebrew"),
            ),
            probes = mapOf(
                "/not-executable/sqlite3" to ToolProbe.unusable("not executable"),
                "/absent/sqlite3" to ToolProbe.missing(),
            ),
        ).resolve(HostTool.SQLITE3)

        assertEquals(ToolStatus.MISSING, result.status)
        assertTrue(result.reason!!.contains("not executable"))
    }

    private fun policy(candidates: List<ToolCandidate>, probes: Map<String, ToolProbe>) =
        CandidateHostToolResolver(
            candidates = { candidates },
            probe = { path, _ -> probes[path] ?: ToolProbe.missing() },
        )
}
