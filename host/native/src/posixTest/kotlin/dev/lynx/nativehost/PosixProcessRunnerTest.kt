package dev.lynx.nativehost

import kotlin.test.Test
import kotlin.test.assertEquals

class PosixProcessRunnerTest {
    private val runner = PosixProcessRunner(HostToolResolver { ResolvedTool(it, ToolStatus.MISSING, null, null, null) })

    @Test
    fun keepsStdoutStderrAndExactExitStatusSeparate() {
        val result = runner.run(listOf("sh", "-c", "printf ok; printf diagnostic >&2; exit 7"))

        assertEquals("ok", result.stdout)
        assertEquals("diagnostic", result.stderr)
        assertEquals(7, result.exitCode)
    }

    @Test
    fun preservesLiteralArgumentsWithoutRewritingAdbSubstrings() {
        val literal = "literal adb \$ and 'quotes' with Spaces and AdB"
        val result = runner.run(listOf("printf", "%s", literal))

        assertEquals(literal, result.stdout)
    }

    @Test
    fun findsAndroidSdkAndPathCandidatesIncludingSpaces() {
        val cases = listOf(
            mapOf("ANDROID_HOME" to "/sdk") to "/sdk/platform-tools/adb",
            mapOf("ANDROID_SDK_ROOT" to "/sdk-root") to "/sdk-root/platform-tools/adb",
            mapOf("PATH" to "/tools with spaces:/other") to "/tools with spaces/adb",
        )
        cases.forEach { (environment, expected) ->
            val resolver = PosixHostToolResolver(
                environment = environment,
                userHome = "/home/tester",
                hostOs = "Darwin",
                execute = { command ->
                    if (command.first() == expected && command.drop(1) == listOf("version")) NativeCommandResult(0, "adb version\n")
                    else NativeCommandResult(127, "", "not found")
                },
            )
            assertEquals(expected, resolver.resolve(HostTool.ADB).path)
        }
    }

    @Test
    fun findsMacLinuxAndHomebrewDefaults() {
        val cases = listOf(
            "/home/tester/Library/Android/sdk/platform-tools/adb",
            "/home/tester/Android/Sdk/platform-tools/adb",
            "/opt/homebrew/bin/adb",
        )
        cases.forEach { expected ->
            val resolver = PosixHostToolResolver(
                environment = emptyMap(),
                userHome = "/home/tester",
                hostOs = "Darwin",
                execute = { command ->
                    if (command.first() == expected) NativeCommandResult(0, "adb version\n") else NativeCommandResult(127, "", "not found")
                },
            )
            assertEquals(expected, resolver.resolve(HostTool.ADB).path)
        }
    }

    @Test
    fun linuxMarksXcodeToolsNotApplicable() {
        val resolver = PosixHostToolResolver(emptyMap(), "/home/tester", { error("must not probe") }, "Linux")

        assertEquals(ToolStatus.NOT_APPLICABLE, resolver.resolve(HostTool.SIMCTL).status)
    }
}
