package dev.lynx.nativehost

import kotlinx.cinterop.ExperimentalForeignApi
import kotlinx.cinterop.toKString
import platform.posix.getenv
import platform.posix.getpid
import platform.posix.usleep

/** Host-owned CA/leaf material for the native TLS adapter. OpenSSL is invoked only
 * for certificate generation; TLS I/O remains in the linked native adapter. */
@OptIn(ExperimentalForeignApi::class)
class PosixNativeCertificateAuthority(
    private val runner: NativeProcessRunner = PosixProcessRunner(),
    private val root: String = (getenv("HOME")?.toKString()?.takeIf(String::isNotBlank) ?: "/tmp") + "/.lynx/certs",
) : NativeCertificateManager {
    data class Leaf(val certificate: String, val privateKey: String)

    fun ensureCa(): String {
        val ca = "$root/daemon.pem"
        val key = "$root/daemon.key"
        val config = "$root/ca.cnf"
        PosixProcessRunner().run(listOf("mkdir", "-p", root))
        withLock("ca") {
            if (!exists(ca) || !exists(key)) {
                writeConfig(config, listOf(
                    "[req]", "prompt = no", "distinguished_name = distinguished_name", "x509_extensions = v3_ca",
                    "[distinguished_name]", "CN = Lynx Local CA", "[v3_ca]", "subjectKeyIdentifier = hash",
                    "authorityKeyIdentifier = keyid:always,issuer", "basicConstraints = critical, CA:true",
                    "keyUsage = critical, keyCertSign, cRLSign",
                ))
                val result = runner.run(listOf("openssl", "req", "-x509", "-newkey", "rsa:2048", "-nodes", "-keyout", key, "-out", ca, "-days", "365", "-config", config))
                runner.run(listOf("rm", "-f", config))
                require(result.exitCode == 0) { "CA_ROOT_CREATE_FAILED: ${result.stderr}" }
            }
        }
        return ca
    }

    fun ensureLeaf(host: String): Leaf {
        ensureCa()
        val safe = host.replace(Regex("[^A-Za-z0-9_.-]"), "_")
        val cert = "$root/leaf-$safe.pem"
        val key = "$root/leaf-$safe.key"
        withLock("leaf-issuance") {
            if (!exists(cert) || !exists(key)) {
                val temporary = "$root/.leaf-$safe.${getpid()}.${kotlin.time.Clock.System.now().toEpochMilliseconds()}"
                val temporaryCert = "$temporary.pem"
                val temporaryKey = "$temporary.key"
                val csr = "$temporary.csr"
                val ext = "$temporary.ext"
                val san = if (host.matches(Regex("[0-9.]+"))) "IP:$host" else "DNS:$host"
                try {
                    PosixProcessRunner().run(listOf("sh", "-c", "printf '%s\\n' 'basicConstraints=critical,CA:FALSE' 'keyUsage=critical,digitalSignature,keyEncipherment' 'extendedKeyUsage=serverAuth' 'subjectKeyIdentifier=hash' 'authorityKeyIdentifier=keyid,issuer' 'subjectAltName=$san' > '$ext'"))
                    val keyResult = runner.run(listOf("openssl", "req", "-new", "-newkey", "rsa:2048", "-nodes", "-keyout", temporaryKey, "-out", csr, "-subj", "/CN=$host"))
                    require(keyResult.exitCode == 0) { "CA_LEAF_KEYGEN_FAILED host=$host: ${keyResult.stderr}" }
                    val signResult = runner.run(listOf("openssl", "x509", "-req", "-in", csr, "-CA", "$root/daemon.pem", "-CAkey", "$root/daemon.key", "-CAcreateserial", "-out", temporaryCert, "-days", "365", "-extfile", ext))
                    require(signResult.exitCode == 0) { "CA_LEAF_SIGN_FAILED host=$host: ${signResult.stderr}" }
                    val publish = runner.run(listOf("sh", "-c", "mv -f '$temporaryCert' '$cert' && mv -f '$temporaryKey' '$key'"))
                    require(publish.exitCode == 0) { "CA_LEAF_PUBLISH_FAILED host=$host: ${publish.stderr}" }
                } finally {
                    runner.run(listOf("rm", "-f", temporaryCert, temporaryKey, csr, ext))
                }
            }
        }
        return Leaf(cert, key)
    }

    fun fingerprint(): String? = runner.run(listOf("openssl", "x509", "-in", ensureCa(), "-noout", "-fingerprint", "-sha256")).stdout.trim().substringAfter("=", "").replace(":", ":").takeIf(String::isNotBlank)

    override fun show(): NativeCertificateState = NativeCertificateState(
        configured = exists("$root/daemon.pem") && exists("$root/daemon.key"),
        pemPath = "$root/daemon.pem",
        fingerprint = if (exists("$root/daemon.pem")) fingerprint() else null,
        instructions = installationInstructions(),
    )

    override fun install(): NativeCertificateState = show().let {
        ensureCa()
        it.copy(configured = true, fingerprint = fingerprint(), trustStatus = "user_installation_required")
    }

    override fun remove(): NativeCertificateState {
        runner.run(listOf("sh", "-c", "rm -f '$root'/daemon.pem '$root'/daemon.key '$root'/leaf-*.pem '$root'/leaf-*.key '$root'/leaf-*.csr '$root'/leaf-*.ext '$root'/ca-*.srl"))
        return NativeCertificateState(false, "$root/daemon.pem", instructions = installationInstructions())
    }

    private fun installationInstructions() = listOf(
        "Install daemon.pem explicitly in the debuggable app or device trust store.",
        "For Android debug builds, enable user CAs through Network Security Config.",
        "The native CLI never mutates a device trust store implicitly.",
    )

    private fun writeConfig(path: String, lines: List<String>) {
        val command = "printf '%s\\n' " + lines.joinToString(" ") { "'${it.replace("'", "'\\\''")}'" } + " > '$path'"
        runner.run(listOf("sh", "-c", command))
    }
    private fun exists(path: String) = runner.run(listOf("test", "-f", path)).exitCode == 0

    private fun <T> withLock(name: String, action: () -> T): T {
        val lock = "$root/.$name.lock"
        repeat(30_000) {
            if (PosixProcessRunner().run(listOf("mkdir", lock)).exitCode == 0) {
                try { return action() } finally { PosixProcessRunner().run(listOf("rmdir", lock)) }
            }
            usleep(1_000u)
        }
        error("CA_LOCK_TIMEOUT lock=$name")
    }
}
