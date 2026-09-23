package dev.lynx.nativehost

import kotlin.concurrent.atomics.AtomicInt
import kotlin.concurrent.atomics.ExperimentalAtomicApi
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertNotNull
import kotlin.test.assertFailsWith
import kotlin.test.assertTrue

@OptIn(ExperimentalAtomicApi::class)
class PosixNativeCertificateAuthorityTest {
    @Test
    fun concurrentLeafIssuanceKeepsEveryCertificateValid() {
        val root = "/tmp/lynx-native-ca-concurrent-${kotlin.time.Clock.System.now().toEpochMilliseconds()}"
        val authority = PosixNativeCertificateAuthority(root = root)
        val completed = AtomicInt(0)
        val failed = AtomicInt(0)
        val dispatcher = PosixNativeConnectionDispatcher()

        repeat(32) { index ->
            dispatcher.dispatch {
                runCatching { authority.ensureLeaf("host-$index.example.test") }.onFailure { failed.fetchAndAdd(1) }
                completed.fetchAndAdd(1)
            }
        }
        var waits = 0
        while (completed.load() != 32 && waits++ < 30_000) {
            platform.posix.usleep(1_000u)
        }

        assertEquals(32, completed.load(), "concurrent issuance did not finish")
        assertEquals(0, failed.load(), "concurrent issuance failed")
        repeat(32) { index ->
            val cert = "$root/leaf-host-$index.example.test.pem"
            assertEquals(0, PosixProcessRunner().run(listOf("openssl", "x509", "-in", cert, "-noout")).exitCode)
        }
    }

    @Test
    fun reportsLeafSigningFailureWithStageAndStderr() {
        val authority = PosixNativeCertificateAuthority(
            root = "/tmp/lynx-native-ca-failure-${kotlin.time.Clock.System.now().toEpochMilliseconds()}",
            runner = object : NativeProcessRunner {
                override fun run(command: List<String>): NativeCommandResult = when {
                    command.firstOrNull() == "test" && command.last().contains("daemon.") -> NativeCommandResult(0, "")
                    command.firstOrNull() == "test" -> NativeCommandResult(1, "")
                    command.take(2) == listOf("openssl", "x509") -> NativeCommandResult(1, "", "serial collision")
                    else -> NativeCommandResult(0, "")
                }
            },
        )

        val failure = assertFailsWith<IllegalArgumentException> { authority.ensureLeaf("api.example.test") }

        assertTrue(failure.message!!.contains("CA_LEAF_SIGN_FAILED"))
        assertTrue(failure.message!!.contains("serial collision"))
    }

    @Test
    fun generatesAnAndroidCompatibleRootAndLeaf() {
        val root = "/tmp/lynx-native-ca-test-${kotlin.time.Clock.System.now().toEpochMilliseconds()}"
        val authority = PosixNativeCertificateAuthority(root = root)
        val ca = authority.ensureCa()
        val leaf = authority.ensureLeaf("127.0.0.1")
        assertTrue(ca.endsWith("daemon.pem"))
        assertTrue(leaf.certificate.endsWith("leaf-127.0.0.1.pem"))
        assertEquals(0, PosixProcessRunner().run(listOf("sh", "-c", "test \"$(grep -c 'BEGIN CERTIFICATE' '${leaf.certificate}')\" -ge 2")).exitCode)
        assertNotNull(authority.fingerprint())
    }

    @Test
    fun regeneratesCachedLeafWhenRootCaWasReplaced() {
        val root = "/tmp/lynx-native-ca-rotation-${kotlin.time.Clock.System.now().toEpochMilliseconds()}"
        val authority = PosixNativeCertificateAuthority(root = root)
        val first = authority.ensureLeaf("api.example.test")
        val firstFingerprint = PosixProcessRunner().run(
            listOf("openssl", "x509", "-in", first.certificate, "-noout", "-fingerprint", "-sha256"),
        ).stdout

        // Simulate `network ca remove` followed by a new CA while the old leaf cache
        // is still present. The next handshake must not serve that stale leaf.
        PosixProcessRunner().run(listOf("rm", "-f", "$root/daemon.pem", "$root/daemon.key"))
        authority.ensureCa()
        val second = authority.ensureLeaf("api.example.test")
        val secondFingerprint = PosixProcessRunner().run(
            listOf("openssl", "x509", "-in", second.certificate, "-noout", "-fingerprint", "-sha256"),
        ).stdout

        assertTrue(firstFingerprint.isNotBlank())
        assertTrue(secondFingerprint.isNotBlank())
        assertTrue(firstFingerprint != secondFingerprint, "cached leaf was reused after CA rotation")
        assertEquals(0, PosixProcessRunner().run(listOf("openssl", "verify", "-CAfile", "$root/daemon.pem", second.certificate)).exitCode)
    }
}
