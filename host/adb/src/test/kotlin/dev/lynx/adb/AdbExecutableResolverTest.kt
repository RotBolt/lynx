package dev.lynx.adb

import java.nio.file.Files
import kotlin.test.Test
import kotlin.test.assertEquals

class AdbExecutableResolverTest {
    @Test
    fun prefersSdkEnvironmentOverStandardLocations() {
        val sdk = Files.createTempDirectory("lynx-sdk")
        val adb = sdk.resolve("platform-tools/adb")
        Files.createDirectories(adb.parent)
        Files.createFile(adb)
        adb.toFile().setExecutable(true)

        assertEquals(
            adb.toString(),
            AdbExecutableResolver.resolve(
                environment = mapOf("ANDROID_HOME" to sdk.toString()),
                userHome = "/does-not-exist",
                path = "",
            ),
        )
    }

    @Test
    fun usesAdbFromPathWhenSdkVariablesAreMissing() {
        val bin = Files.createTempDirectory("lynx-bin")
        val adb = bin.resolve("adb")
        Files.createFile(adb)
        adb.toFile().setExecutable(true)

        assertEquals(
            adb.toString(),
            AdbExecutableResolver.resolve(emptyMap(), "/does-not-exist", bin.toString()),
        )
    }
}
