package dev.lynx.nativehost

import kotlinx.serialization.json.Json
import kotlinx.serialization.json.jsonArray
import kotlinx.serialization.json.jsonObject
import kotlinx.serialization.json.jsonPrimitive

class AndroidDeviceProvider(private val runner: NativeProcessRunner) : DeviceProvider {
    override fun discover(): DeviceProviderResult {
        val result = runner.run(listOf("adb", "devices", "-l"))
        if (result.exitCode != 0) return DeviceProviderResult(emptyList(), "Android discovery: ${result.stderr.ifBlank { "adb exit ${result.exitCode}" }}")
        return DeviceProviderResult(result.stdout.lineSequence().drop(1).mapNotNull(::parse).toList())
    }

    private fun parse(line: String): DeviceEntry? {
        val values = line.trim().split(Regex("\\s+")).filter(String::isNotBlank)
        if (values.size < 2) return null
        val details = values.drop(2).mapNotNull { token -> token.substringBefore(':').takeIf { ':' in token }?.let { it to token.substringAfter(':') } }.toMap()
        val state = values[1]
        return DeviceEntry(
            id = values[0], platform = "android", kind = if (values[0].startsWith("emulator-")) "emulator" else "device",
            name = details["model"] ?: details["device"] ?: values[0], state = state,
            attachable = state == "device", attachmentId = null, applicationId = null, captureId = null,
        )
    }
}

class IosSimulatorDeviceProvider(private val runner: NativeProcessRunner, private val tools: HostToolResolver) : DeviceProvider {
    override fun discover(): DeviceProviderResult {
        val simctl = tools.resolve(HostTool.SIMCTL)
        if (simctl.status != ToolStatus.AVAILABLE) return DeviceProviderResult(emptyList(), "iOS discovery: ${simctl.reason ?: simctl.status.name.lowercase()}")
        val result = runner.run(listOf("xcrun", "simctl", "list", "devices", "--json"))
        if (result.exitCode != 0) return DeviceProviderResult(emptyList(), "iOS discovery: ${result.stderr.ifBlank { "simctl exit ${result.exitCode}" }}")
        return runCatching {
            val root = Json.parseToJsonElement(result.stdout).jsonObject
            root["devices"]!!.jsonObject.values.flatMap { runtime -> runtime.jsonArray.map { item -> item.jsonObject } }.map { device ->
                val state = device["state"]?.jsonPrimitive?.content ?: "unknown"
                val available = device["isAvailable"]?.jsonPrimitive?.content?.toBooleanStrictOrNull() ?: true
                DeviceEntry(
                    id = device["udid"]!!.jsonPrimitive.content, platform = "ios", kind = "simulator",
                    name = device["name"]!!.jsonPrimitive.content, state = state,
                    attachable = available && state == "Booted", attachmentId = null, applicationId = null, captureId = null,
                )
            }
        }.fold(
            onSuccess = { DeviceProviderResult(it) },
            onFailure = { DeviceProviderResult(emptyList(), "iOS discovery: invalid simctl JSON") },
        )
    }
}
