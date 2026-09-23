package dev.lynx.nativehost

import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertTrue

class DeviceInventoryTest {
    @Test
    fun androidProviderRetainsOfflineAndUnauthorizedRows() {
        val provider = AndroidDeviceProvider(object : NativeProcessRunner {
            override fun run(command: List<String>) = NativeCommandResult(0, "List of devices attached\nemulator-5554 device model:Pixel\nR58 unauthorized\nZX offline\n")
        })

        val entries = provider.discover().devices

        assertEquals(listOf("device", "unauthorized", "offline"), entries.map(DeviceEntry::state))
        assertEquals(listOf(true, false, false), entries.map(DeviceEntry::attachable))
    }
    @Test
    fun mergesProvidersWithoutLettingOneFailureHideOtherDevices() {
        val android = DeviceEntry("emulator-5554", "android", "emulator", "Pixel", "device", true, "attach-1", "dev.lynx.dummyapp", null)
        val inventory = DeviceInventoryService(
            providers = listOf(
                DeviceProvider { DeviceProviderResult(listOf(android)) },
                DeviceProvider { DeviceProviderResult(emptyList(), "Xcode runtime unavailable") },
            ),
            tools = HostToolResolver { ResolvedTool(it, ToolStatus.AVAILABLE, "/tools/${it.name.lowercase()}", "v1", null) },
        ).discover()

        assertEquals(listOf(android), inventory.devices)
        assertTrue(inventory.diagnostics.single().contains("Xcode runtime unavailable"))
    }

    @Test
    fun attachmentOnlyJoinsExactNormalizedTarget() {
        val devices = listOf(
            DeviceEntry("emulator-5554", "android", "emulator", "Pixel", "device", true, null, null, null),
            DeviceEntry("emulator-5556", "android", "emulator", "Pixel", "device", true, null, null, null),
        )
        val inventory = DeviceInventoryService(
            providers = listOf(DeviceProvider { DeviceProviderResult(devices) }),
            tools = HostToolResolver { ResolvedTool(it, ToolStatus.MISSING, null, null, "missing") },
            attachment = NativeSession("attach-1", "emulator-5554", "dev.lynx.dummyapp", 42),
        ).discover()

        assertEquals("attach-1", inventory.devices[0].attachmentId)
        assertEquals(null, inventory.devices[1].attachmentId)
    }
}
