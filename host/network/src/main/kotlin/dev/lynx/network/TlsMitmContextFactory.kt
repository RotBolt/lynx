package dev.lynx.network

import javax.net.ssl.SSLContext

/** Compatibility boundary for the proxy; CA material is owned by the persistent manager. */
internal class TlsMitmContextFactory(
    private val authority: CertificateAuthorityManager = CertificateAuthorityManager(),
) {
    fun serverContext(host: String): SSLContext = authority.serverContext(host)
    fun caCertificatePem(): String = authority.caCertificatePem()
    fun caManager(): CertificateAuthorityManager = authority
}
