package dev.lynx.nativehost

data class DeviceEntry(
    val id: String,
    val platform: String,
    val kind: String,
    val name: String,
    val state: String,
    val attachable: Boolean,
    val attachmentId: String?,
    val applicationId: String?,
    val captureId: String?,
)

data class DeviceInventory(
    val devices: List<DeviceEntry>,
    val tools: List<ResolvedTool>,
    val diagnostics: List<String>,
)

data class DeviceProviderResult(val devices: List<DeviceEntry>, val diagnostic: String? = null)

fun interface DeviceProvider {
    fun discover(): DeviceProviderResult
}

class DeviceInventoryService(
    private val providers: List<DeviceProvider>,
    private val tools: HostToolResolver,
    private val attachment: NativeSession? = null,
) {
    fun discover(): DeviceInventory {
        val results = providers.map(DeviceProvider::discover)
        return DeviceInventory(
            devices = results.flatMap(DeviceProviderResult::devices).map(::joinAttachment),
            tools = HostTool.entries.map(tools::resolve),
            diagnostics = results.mapNotNull(DeviceProviderResult::diagnostic),
        )
    }

    private fun joinAttachment(device: DeviceEntry): DeviceEntry {
        val current = attachment ?: return device
        val sessionTarget = current.deviceSerial.removePrefix("ios-simulator:")
        if (device.id != sessionTarget) return device
        return device.copy(attachmentId = current.id, applicationId = current.packageName)
    }
}
