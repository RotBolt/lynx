package dev.lynx.nativehost

import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFailsWith
import kotlin.test.assertTrue

class NativeMacProxyRecoveryTest {
    @Test
    fun restoresSecureProxyEvenWhenWebRestorationFailsAndRetainsLease() {
        val runner = StatefulNetworksetupRunner(
            web = ProxyFixture(enabled = true, server = "127.0.0.1", port = "62006"),
            secure = ProxyFixture(enabled = true, server = "127.0.0.1", port = "62006"),
            failures = mutableMapOf("-setwebproxystate" to "web restore failed"),
        )
        val store = InMemoryMacProxyLeaseStore(
            macLease(
                webOriginal = ProxyFixture(enabled = false, server = null, port = null),
                secureOriginal = ProxyFixture(enabled = false, server = null, port = null),
            ),
        )
        val recovery = NativeMacProxyRecovery(NativeMacSystemProxyController(runner = runner), store)

        val failure = assertFailsWith<IllegalStateException> { recovery.restoreOwnedLease() }

        assertTrue(failure.message!!.contains("web restore failed"))
        assertEquals(false, runner.secure.enabled)
        assertEquals(false, store.load()!!.webRestored)
        assertEquals(true, store.load()!!.secureRestored)
    }

    @Test
    fun preservesUserEditedProxySettingAndRestoresStillOwnedSetting() {
        val runner = StatefulNetworksetupRunner(
            web = ProxyFixture(enabled = true, server = "user.proxy", port = "9000"),
            secure = ProxyFixture(enabled = true, server = "127.0.0.1", port = "62006"),
        )
        val store = InMemoryMacProxyLeaseStore(
            macLease(
                webOriginal = ProxyFixture(enabled = false, server = null, port = null),
                secureOriginal = ProxyFixture(enabled = false, server = null, port = null),
            ),
        )
        val recovery = NativeMacProxyRecovery(NativeMacSystemProxyController(runner = runner), store)

        val failure = assertFailsWith<IllegalStateException> { recovery.restoreOwnedLease() }

        assertTrue(failure.message!!.contains("PROXY_OWNERSHIP_CONFLICT"))
        assertEquals(ProxyFixture(enabled = true, server = "user.proxy", port = "9000"), runner.web)
        assertEquals(false, runner.secure.enabled)
        assertEquals(false, store.load()!!.webRestored)
        assertEquals(true, store.load()!!.secureRestored)
    }

    @Test
    fun repeatedPrepareKeepsTheOriginalUserProxySnapshot() {
        val original = ProxyFixture(enabled = true, server = "proxy.example", port = "8888")
        val store = InMemoryMacProxyLeaseStore(
            macLease(webOriginal = original, secureOriginal = original),
        )
        val runner = StatefulNetworksetupRunner(
            web = ProxyFixture(enabled = true, server = "127.0.0.1", port = "62006"),
            secure = ProxyFixture(enabled = true, server = "127.0.0.1", port = "62006"),
        )
        val recovery = NativeMacProxyRecovery(NativeMacSystemProxyController(runner = runner), store)

        val lease = recovery.prepareLease("127.0.0.1:62006", 62006)

        assertEquals("proxy.example", lease.webOriginal.server)
        assertEquals("8888", lease.secureOriginal.port)
    }

    @Test
    fun activationFailureAfterWebServerMutationKeepsIntentAndCanRestore() {
        val runner = StatefulNetworksetupRunner(
            failures = mutableMapOf("-setwebproxystate" to "web state failed"),
        )
        val store = InMemoryMacProxyLeaseStore()
        val recovery = NativeMacProxyRecovery(NativeMacSystemProxyController(runner = runner), store)
        val lease = recovery.prepareLease("127.0.0.1:62006", 62006)

        assertFailsWith<IllegalStateException> { recovery.activatePreparedLease(lease) }
        recovery.restoreOwnedLease()

        assertEquals(false, runner.web.enabled)
        assertEquals(null, store.load())
    }

    private fun macLease(
        webOriginal: ProxyFixture,
        secureOriginal: ProxyFixture,
    ) = NativeMacProxyLease(
        leaseId = "lease-1",
        service = "Wi-Fi",
        endpoint = "127.0.0.1:62006",
        webOriginal = webOriginal.setting(),
        secureOriginal = secureOriginal.setting(),
        webInstalled = NativeMacProxySetting(enabled = true, server = "127.0.0.1", port = "62006"),
        secureInstalled = NativeMacProxySetting(enabled = true, server = "127.0.0.1", port = "62006"),
        phase = NativeMacProxyLeasePhase.ACTIVE,
        webApplied = true,
        secureApplied = true,
    )
}
