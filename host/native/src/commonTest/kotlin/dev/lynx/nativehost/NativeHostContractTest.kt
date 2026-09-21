package dev.lynx.nativehost

import dev.lynx.model.NetworkCommand
import dev.lynx.model.NetworkCommandResult
import kotlin.test.Test
import kotlin.test.assertEquals

class NativeHostContractTest {
    @Test
    fun commandResultPreservesBinarySafeTextContract() {
        assertEquals(0, NativeCommandResult(0, "ok").exitCode)
        assertEquals("ok", NativeCommandResult(0, "ok").stdout)
    }

    @Test
    fun networkInspectorConsumesPortableCommandsAndReturnsPortableResults() {
        val inspector = object : NativeNetworkInspector {
            override fun execute(command: NetworkCommand): NetworkCommandResult =
                NetworkCommandResult.Stopped
        }

        assertEquals(NetworkCommandResult.Stopped, inspector.execute(NetworkCommand.Stop))
    }
}
