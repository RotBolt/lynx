package dev.lynx.nativehost

import kotlin.test.Test
import kotlin.test.assertNotNull
import kotlin.test.assertTrue

class PosixNativeCertificateAuthorityTest {
    @Test
    fun generatesAnAndroidCompatibleRootAndLeaf() {
        val root = "/tmp/lynx-native-ca-test-${kotlin.time.Clock.System.now().toEpochMilliseconds()}"
        val authority = PosixNativeCertificateAuthority(root = root)
        val ca = authority.ensureCa()
        val leaf = authority.ensureLeaf("127.0.0.1")
        assertTrue(ca.endsWith("daemon.pem"))
        assertTrue(leaf.certificate.endsWith("leaf-127.0.0.1.pem"))
        assertNotNull(authority.fingerprint())
    }
}
