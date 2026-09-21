package dev.lynx.network

import java.nio.file.Files
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFalse
import kotlin.test.assertNotEquals
import kotlin.test.assertTrue
import org.bouncycastle.asn1.x509.KeyUsage
import org.bouncycastle.asn1.x509.Extension

class CertificateAuthorityManagerTest {
    @Test
    fun materialIsStableAndShowIsNonMutating() {
        val directory = Files.createTempDirectory("lynx-ca-test")
        val first = CertificateAuthorityManager(directory).ensure()
        val shown = CertificateAuthorityManager(directory).show()
        assertTrue(first.configured)
        assertEquals(first.fingerprint, shown.fingerprint)
        assertEquals(first.pemPath, shown.pemPath)
        assertTrue(Files.exists(directory.resolve("ca.p12")))
    }

    @Test
    fun createsAnAndroidCompatibleSigningCa() {
        val manager = CertificateAuthorityManager(Files.createTempDirectory("lynx-ca-test"))
        val certificate = manager.caMaterial().certificate
        val usage = certificate.getExtensionValue(Extension.keyUsage.id)
        assertTrue(usage != null)
        assertTrue(certificate.basicConstraints >= 0)
        assertTrue(certificate.keyUsage[0], "CA must advertise digitalSignature")
        assertTrue(certificate.keyUsage[5], "CA must advertise keyCertSign")
        assertTrue(certificate.getExtensionValue(Extension.subjectKeyIdentifier.id) != null)
        assertTrue(certificate.getExtensionValue(Extension.authorityKeyIdentifier.id) != null)
        assertTrue(usage.isNotEmpty())
    }

    @Test
    fun installIsIdempotentAndReportsExplicitConfirmation() {
        val manager = CertificateAuthorityManager(Files.createTempDirectory("lynx-ca-test"))
        val first = manager.install()
        val second = manager.install()
        assertEquals(first.fingerprint, second.fingerprint)
        assertEquals("awaiting_user_confirmation", first.trustStatus)
    }

    @Test
    fun removeIsIdempotentAndClearsManagedMaterial() {
        val manager = CertificateAuthorityManager(Files.createTempDirectory("lynx-ca-test"))
        manager.ensure()
        assertTrue(manager.remove().removed)
        assertFalse(manager.show().configured)
        assertFalse(manager.remove().removed)
    }

    @Test
    fun removeClearsManagedIosProfileArtifact() {
        val directory = Files.createTempDirectory("lynx-ca-test")
        val manager = CertificateAuthorityManager(directory)
        Files.writeString(directory.resolve("lynx-ca.mobileconfig"), "profile")
        assertTrue(manager.remove().removed)
        assertFalse(Files.exists(directory.resolve("lynx-ca.mobileconfig")))
    }
}
