package dev.lynx.network

import dev.lynx.model.NetworkCommand
import dev.lynx.model.NetworkCommandResult
import dev.lynx.model.NetworkFilter
import kotlin.test.Test
import kotlin.test.assertEquals

class NativeNetworkBackendTest {
    @Test
    fun backendIsACommonKmpBoundaryAndPreservesCommands() {
        val expected = NetworkCommandResult.Exchanges(emptyList())
        val backend = object : NativeNetworkBackend {
            override fun execute(command: NetworkCommand): NetworkCommandResult {
                assertEquals(NetworkCommand.List(NetworkFilter(method = "GET")), command)
                return expected
            }

            override fun worker(port: Int) {
                assertEquals(62006, port)
            }
        }

        assertEquals(expected, backend.execute(NetworkCommand.List(NetworkFilter(method = "GET"))))
        backend.worker(62006)
    }
}
