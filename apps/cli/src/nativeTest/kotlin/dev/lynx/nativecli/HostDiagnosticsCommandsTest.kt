package dev.lynx.nativecli

import dev.lynx.nativehost.DeviceEntry
import dev.lynx.nativehost.DeviceInventory
import dev.lynx.nativehost.HostToolResolver
import dev.lynx.nativehost.ResolvedTool
import dev.lynx.nativehost.ToolStatus
import kotlin.test.Test
import kotlin.test.assertContains
import kotlin.test.assertFalse

class HostDiagnosticsCommandsTest {
    @Test
    fun devicesJsonIsOnlyStructuredInventory() {
        val output = commands().devices(json = true)

        assertContains(output, "\"type\":\"devices\"")
        assertContains(output, "\"id\":\"emulator-5554\"")
        assertFalse(output.contains("Android Debug Bridge"))
    }

    @Test
    fun doctorJsonContainsToolDiagnostics() {
        val output = commands().doctor(json = true)

        assertContains(output, "\"type\":\"doctor\"")
        assertContains(output, "\"tool\":\"ADB\"")
        assertContains(output, "\"status\":\"AVAILABLE\"")
    }

    private fun commands() = HostDiagnosticsCommands(
        tools = HostToolResolver { ResolvedTool(it, ToolStatus.AVAILABLE, "/tools/${it.name.lowercase()}", "v1", null) },
        inventory = { DeviceInventory(listOf(DeviceEntry("emulator-5554", "android", "emulator", "Pixel", "device", true, null, null, null)), emptyList(), emptyList()) },
    )
}
