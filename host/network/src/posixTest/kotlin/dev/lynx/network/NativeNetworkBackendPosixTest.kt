package dev.lynx.network

import dev.lynx.model.NetworkCommand
import dev.lynx.model.NetworkCommandResult
import kotlin.test.Test
import kotlin.test.assertIs
import kotlin.test.assertTrue

class NativeNetworkBackendPosixTest {
    @Test
    fun nativeActualReportsTheSupportedNetworkProtocols() {
        val result = nativeNetworkBackend().execute(NetworkCommand.Doctor)
        val diagnostics = assertIs<NetworkCommandResult.Diagnostics>(result)
        assertTrue("HTTP/1.1" in diagnostics.capabilities.supportedProtocols)
        assertTrue("HTTP/2" in diagnostics.capabilities.supportedProtocols)
    }
}
