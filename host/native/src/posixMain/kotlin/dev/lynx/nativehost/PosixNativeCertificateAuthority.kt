package dev.lynx.nativehost

import kotlinx.cinterop.ExperimentalForeignApi
import kotlinx.cinterop.toKString
import platform.posix.getenv

/** Host-owned CA/leaf material for the native TLS adapter. OpenSSL is invoked only
 * for certificate generation; TLS I/O remains in the linked native adapter. */
@OptIn(ExperimentalForeignApi::class)
class PosixNativeCertificateAuthority(
    private val runner: NativeProcessRunner = PosixProcessRunner(),
    private val root: String = (getenv("HOME")?.toKString()?.takeIf(String::isNotBlank) ?: "/tmp") + "/.lynx/certs",
) {
    data class Leaf(val certificate: String, val privateKey: String)

    fun ensureCa(): String {
        val ca = "$root/daemon.pem"
        val key = "$root/daemon.key"
        PosixProcessRunner().run(listOf("mkdir", "-p", root))
        if (!exists(ca) || !exists(key)) {
            require(runner.run(listOf("openssl", "req", "-quiet", "-x509", "-newkey", "rsa:2048", "-nodes", "-keyout", key, "-out", ca, "-subj", "/CN=Lynx Local CA", "-days", "365", "-addext", "basicConstraints=critical,CA:TRUE", "-addext", "keyUsage=critical,keyCertSign,cRLSign")).exitCode == 0) { "unable to create native CA" }
        }
        return ca
    }

    fun ensureLeaf(host: String): Leaf {
        ensureCa()
        val safe = host.replace(Regex("[^A-Za-z0-9_.-]"), "_")
        val cert = "$root/leaf-$safe.pem"
        val key = "$root/leaf-$safe.key"
        val csr = "$root/leaf-$safe.csr"
        val ext = "$root/leaf-$safe.ext"
        if (!exists(cert) || !exists(key)) {
            val san = if (host.matches(Regex("[0-9.]+"))) "IP:$host" else "DNS:$host"
            PosixProcessRunner().run(listOf("sh", "-c", "printf '%s\\n' 'basicConstraints=critical,CA:FALSE' 'keyUsage=critical,digitalSignature,keyEncipherment' 'extendedKeyUsage=serverAuth' 'subjectAltName=$san' > '$ext'"))
            require(runner.run(listOf("openssl", "req", "-quiet", "-new", "-newkey", "rsa:2048", "-nodes", "-keyout", key, "-out", csr, "-subj", "/CN=$host")).exitCode == 0) { "unable to create native leaf key" }
            require(runner.run(listOf("openssl", "x509", "-req", "-in", csr, "-CA", "$root/daemon.pem", "-CAkey", "$root/daemon.key", "-CAcreateserial", "-out", cert, "-days", "365", "-extfile", ext)).exitCode == 0) { "unable to sign native leaf" }
        }
        return Leaf(cert, key)
    }

    fun fingerprint(): String? = runner.run(listOf("openssl", "x509", "-in", ensureCa(), "-noout", "-fingerprint", "-sha256")).stdout.trim().substringAfter("=", "").replace(":", ":").takeIf(String::isNotBlank)
    private fun exists(path: String) = runner.run(listOf("test", "-f", path)).exitCode == 0
}
