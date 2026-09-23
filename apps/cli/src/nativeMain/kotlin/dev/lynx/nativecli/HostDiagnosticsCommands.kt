package dev.lynx.nativecli

import dev.lynx.nativehost.DeviceInventory
import dev.lynx.nativehost.HostTool
import dev.lynx.nativehost.HostToolResolver
import kotlinx.serialization.json.JsonArray
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.JsonPrimitive
import kotlinx.serialization.json.buildJsonObject

class HostDiagnosticsCommands(
    private val tools: HostToolResolver,
    private val inventory: () -> DeviceInventory,
) {
    fun devices(json: Boolean, platform: String? = null): String {
        val state = currentInventory().let { current ->
            platform?.let { requested -> current.copy(devices = current.devices.filter { it.platform == requested.lowercase() }) } ?: current
        }
        if (!json) return state.devices.joinToString("\n") { "${it.id}\t${it.platform}\t${it.state}\t${it.name}" }
        return render("devices", state, includeDevices = true)
    }

    fun doctor(json: Boolean): String {
        val state = currentInventory()
        if (!json) return state.tools.joinToString("\n") { "${it.tool}\t${it.status}\t${it.path ?: it.reason.orEmpty()}" }
        return render("doctor", state, includeDevices = false)
    }

    private fun render(type: String, state: DeviceInventory, includeDevices: Boolean): String {
        val fields = buildMap<String, kotlinx.serialization.json.JsonElement> {
            put("type", JsonPrimitive(type))
            put("tools", JsonArray(state.tools.map { tool -> buildJsonObject {
                put("tool", JsonPrimitive(tool.tool.name)); put("status", JsonPrimitive(tool.status.name)); tool.path?.let { put("path", JsonPrimitive(it)) }; tool.version?.let { put("version", JsonPrimitive(it)) }; tool.reason?.let { put("reason", JsonPrimitive(it)) }
            } }))
            put("diagnostics", JsonArray(state.diagnostics.map(::JsonPrimitive)))
            if (includeDevices) put("devices", JsonArray(state.devices.map { device -> buildJsonObject {
                put("id", JsonPrimitive(device.id)); put("platform", JsonPrimitive(device.platform)); put("kind", JsonPrimitive(device.kind)); put("name", JsonPrimitive(device.name)); put("state", JsonPrimitive(device.state)); put("attachable", JsonPrimitive(device.attachable)); device.attachmentId?.let { put("attachmentId", JsonPrimitive(it)) }; device.applicationId?.let { put("applicationId", JsonPrimitive(it)) }; device.captureId?.let { put("captureId", JsonPrimitive(it)) }
            } }))
        }
        return JsonObject(fields).toString()
    }

    private fun currentInventory(): DeviceInventory = inventory().copy(tools = HostTool.entries.map(tools::resolve))
}
