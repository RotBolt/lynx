package dev.lynx.nativehost

import kotlin.test.Test
import kotlin.test.assertEquals

class NativeHostContractTest {
    @Test
    fun commandResultPreservesBinarySafeTextContract() {
        assertEquals(0, NativeCommandResult(0, "ok").exitCode)
        assertEquals("ok", NativeCommandResult(0, "ok").stdout)
    }
}
