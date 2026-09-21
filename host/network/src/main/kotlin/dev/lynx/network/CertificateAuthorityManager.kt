package dev.lynx.network

import org.bouncycastle.asn1.x500.X500Name
import org.bouncycastle.asn1.x509.BasicConstraints
import org.bouncycastle.asn1.x509.Extension
import org.bouncycastle.asn1.x509.GeneralName
import org.bouncycastle.asn1.x509.GeneralNames
import org.bouncycastle.asn1.x509.ExtendedKeyUsage
import org.bouncycastle.asn1.x509.KeyPurposeId
import org.bouncycastle.asn1.x509.KeyUsage
import org.bouncycastle.cert.jcajce.JcaX509CertificateConverter
import org.bouncycastle.cert.jcajce.JcaX509v3CertificateBuilder
import org.bouncycastle.cert.jcajce.JcaX509ExtensionUtils
import org.bouncycastle.jce.provider.BouncyCastleProvider
import org.bouncycastle.operator.jcajce.JcaContentSignerBuilder
import org.bouncycastle.util.io.pem.PemObject
import org.bouncycastle.util.io.pem.PemWriter
import java.io.StringWriter
import java.math.BigInteger
import java.nio.file.Files
import java.nio.file.Path
import java.security.KeyPair
import java.security.KeyPairGenerator
import java.security.KeyStore
import java.security.MessageDigest
import java.security.Security
import java.security.SecureRandom
import java.security.cert.X509Certificate
import java.util.Date
import javax.net.ssl.KeyManagerFactory
import javax.net.ssl.SSLContext

data class CertificateAuthorityState(
    val configured: Boolean,
    val fingerprint: String? = null,
    val pemPath: String? = null,
    /** Host-side status only; Android user-trust stores are not readable via public ADB APIs. */
    val trustStatus: String = if (configured) "unknown" else "not_created",
    val instructions: List<String> = emptyList(),
)

data class CertificateAuthorityRemoval(val removed: Boolean, val pemPath: String? = null)

/** Persistent host-owned CA lifecycle. Trust installation is always explicit. */
class CertificateAuthorityManager(
    private val directory: Path = defaultDirectory(),
) {
    private val storePath get() = directory.resolve("ca.p12")
    private val pemPath get() = directory.resolve("ca.pem")

    init { ensureProvider() }

    @Synchronized
    fun show(): CertificateAuthorityState {
        if (!Files.exists(storePath)) return CertificateAuthorityState(false, pemPath = pemPath.toString())
        return runCatching {
            val material = loadOrCreate()
            CertificateAuthorityState(
                configured = true,
                fingerprint = fingerprint(material.certificate),
                pemPath = pemPath.toString(),
                trustStatus = "unknown",
                instructions = instructions(),
            )
        }.getOrElse { CertificateAuthorityState(false, pemPath = pemPath.toString()) }
    }

    @Synchronized
    fun ensure(): CertificateAuthorityState {
        loadOrCreate()
        return show()
    }

    /** Records readiness, but never mutates a system trust store silently. */
    @Synchronized
    fun install(): CertificateAuthorityState = ensure().copy(
        trustStatus = "awaiting_user_confirmation",
        instructions = instructions() + "Install the PEM explicitly in the debug client or device trust store.",
    )

    @Synchronized
    fun remove(): CertificateAuthorityRemoval {
        val existed = Files.exists(storePath) || Files.exists(pemPath)
        Files.deleteIfExists(storePath)
        Files.deleteIfExists(pemPath)
        return CertificateAuthorityRemoval(existed, pemPath.toString())
    }

    internal fun caMaterial(): Material = loadOrCreate()

    internal fun serverContext(host: String): SSLContext {
        val ca = loadOrCreate()
        val leafKeys = keyPair()
        val leaf = certificate(host, leafKeys, ca.certificate.subjectX500Principal.name.toX500Name(), ca.certificate.publicKey, ca.privateKey, false)
        val keys = KeyStore.getInstance(KeyStore.getDefaultType()).apply {
            load(null, null)
            // Send only the generated host leaf. The installed local CA is the
            // trust anchor; sending the self-signed root as a peer certificate
            // makes Android/Conscrypt treat it as an untrusted intermediate on
            // some emulator images (standard proxies omit the root here).
            setKeyEntry("lynx", leafKeys.private, PASSWORD, arrayOf(leaf))
        }
        val managers = KeyManagerFactory.getInstance(KeyManagerFactory.getDefaultAlgorithm()).apply { init(keys, PASSWORD) }
        return SSLContext.getInstance("TLS").apply { init(managers.keyManagers, null, SecureRandom()) }
    }

    internal fun caCertificatePem(): String = loadOrCreate().certificate.toPem()

    private fun create() {
        Files.createDirectories(directory)
        val keys = keyPair()
        val certificate = certificate("Lynx Local CA", keys, X500Name("CN=Lynx Local CA"), keys.public, keys.private, true)
        val keyStore = KeyStore.getInstance("PKCS12").apply {
            load(null, null)
            setKeyEntry("lynx-ca", keys.private, PASSWORD, arrayOf(certificate))
        }
        Files.newOutputStream(storePath).use { keyStore.store(it, PASSWORD) }
        Files.newBufferedWriter(pemPath).use { writer -> PemWriter(writer).use { it.writeObject(PemObject("CERTIFICATE", certificate.encoded)) } }
    }

    private fun loadOrCreate(): Material {
        if (!Files.exists(storePath)) create()
        else {
            val existing = runCatching { load() }.getOrNull()
            if (existing == null || !isAndroidCompatible(existing.certificate)) {
                // The CA is persistent, so generator changes must not leave a
                // stale, incompatible anchor in place indefinitely.
                Files.deleteIfExists(storePath)
                Files.deleteIfExists(pemPath)
                create()
            }
        }
        return load()
    }

    private fun isAndroidCompatible(certificate: X509Certificate): Boolean =
        certificate.basicConstraints >= 0 &&
            certificate.keyUsage?.let { it.size > 6 && it[0] && it[5] && it[6] } == true &&
            certificate.getExtensionValue(Extension.subjectKeyIdentifier.id) != null &&
            certificate.getExtensionValue(Extension.authorityKeyIdentifier.id) != null

    private fun load(): Material {
        val keyStore = KeyStore.getInstance("PKCS12").apply { Files.newInputStream(storePath).use { load(it, PASSWORD) } }
        val key = keyStore.getKey("lynx-ca", PASSWORD) as java.security.PrivateKey
        return Material(key, keyStore.getCertificate("lynx-ca") as X509Certificate)
    }

    private fun instructions() = listOf(
        "The app must trust user CAs through its debuggable Network Security Config.",
        "Use the PEM path with curl --cacert or install it explicitly on the debug device.",
    )

    private fun fingerprint(certificate: X509Certificate): String = MessageDigest.getInstance("SHA-256").digest(certificate.encoded).joinToString(":") { "%02x".format(it) }
    private fun keyPair(): KeyPair = KeyPairGenerator.getInstance("RSA").apply { initialize(2048, SecureRandom()) }.generateKeyPair()

    private fun certificate(subject: String, keys: KeyPair, issuer: X500Name, issuerKey: java.security.PublicKey, signer: java.security.PrivateKey, ca: Boolean): X509Certificate {
        // Device clocks can lag the host clock (Android emulators commonly do
        // this by tens of seconds). A leaf whose NotBefore is the exact host
        // instant is then rejected by Conscrypt as "certificate_unknown".
        // Backdate the validity window so proxy-issued certificates are valid
        // on slightly skewed debug devices, as desktop proxies do.
        val now = Date(System.currentTimeMillis() - 5L * 60L * 1000L)
        val builder = JcaX509v3CertificateBuilder(issuer, BigInteger(160, SecureRandom()), now, Date(now.time + 365L * 86_400_000L), X500Name("CN=${subject.replace("=", "\\=")}"), keys.public)
        builder.addExtension(Extension.basicConstraints, true, BasicConstraints(ca))
        // RFC 5280 requires key identifiers on conforming CA certificates and
        // recommends them on issued certificates. Android's Conscrypt path
        // builder is stricter than the desktop JCA implementation here.
        val extensionUtils = JcaX509ExtensionUtils()
        builder.addExtension(Extension.subjectKeyIdentifier, false, extensionUtils.createSubjectKeyIdentifier(keys.public))
        builder.addExtension(Extension.authorityKeyIdentifier, false, extensionUtils.createAuthorityKeyIdentifier(issuerKey))
        if (ca) {
            // Android/Conscrypt requires an explicit CA signing usage for a
            // user-installed trust anchor on newer emulator images.
            // Desktop MITM clients commonly mark their signing key for both
            // certificate signing and TLS signature use.  Android's modern
            // Conscrypt path builder is stricter about this usage on a
            // user-installed trust anchor than the desktop JCA provider.
            builder.addExtension(
                Extension.keyUsage,
                true,
                KeyUsage(KeyUsage.digitalSignature or KeyUsage.keyCertSign or KeyUsage.cRLSign),
            )
        } else {
            builder.addExtension(Extension.keyUsage, true, KeyUsage(KeyUsage.digitalSignature or KeyUsage.keyEncipherment))
            builder.addExtension(Extension.extendedKeyUsage, false, ExtendedKeyUsage(KeyPurposeId.id_kp_serverAuth))
        }
        if (!ca) builder.addExtension(Extension.subjectAlternativeName, false, GeneralNames(GeneralName(GeneralName.dNSName, subject)))
        return JcaX509CertificateConverter().setProvider("BC").getCertificate(builder.build(JcaContentSignerBuilder("SHA256withRSA").setProvider("BC").build(signer))).also { it.verify(issuerKey) }
    }

    private fun X509Certificate.toPem(): String = StringWriter().also { writer -> PemWriter(writer).use { it.writeObject(PemObject("CERTIFICATE", encoded)) } }.toString()
    private fun String.toX500Name() = X500Name(this)

    internal data class Material(val privateKey: java.security.PrivateKey, val certificate: X509Certificate)
    companion object {
        private val PASSWORD = "lynx-ca".toCharArray()
        private fun ensureProvider() { if (Security.getProvider("BC") == null) Security.addProvider(BouncyCastleProvider()) }
        fun defaultDirectory(): Path = Path.of(System.getProperty("user.home"), ".lynx", "certs")
    }
}
