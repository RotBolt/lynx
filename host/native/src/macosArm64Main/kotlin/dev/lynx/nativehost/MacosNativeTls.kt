package dev.lynx.nativehost

import dev.lynx.nativehost.openssl.*
import kotlinx.cinterop.*

private const val PEM = 1

@OptIn(ExperimentalForeignApi::class)
private class OpenSslConnection(
    override val fileDescriptor: Int,
    private val ssl: CPointer<LYNSsl>?,
    private val context: CPointer<LYNSslCtx>?,
    override val applicationProtocol: String?,
) : NativeTlsConnection {
    init { require(ssl != null) { "unable to create native TLS session" } }
    override fun read(maxBytes: Int): ByteArray? = memScoped {
        val buffer = allocArray<ByteVar>(maxBytes)
        val count = SSL_read(ssl, buffer, maxBytes)
        if (count <= 0) null else ByteArray(count) { buffer[it] }
    }
    override fun write(bytes: ByteArray) { bytes.usePinned { require(SSL_write(ssl, it.addressOf(0), bytes.size) == bytes.size) { "native TLS write failed" } } }
    override fun close() { SSL_shutdown(ssl); SSL_free(ssl); SSL_CTX_free(context) }
}

@OptIn(ExperimentalForeignApi::class)
actual fun nativeTlsProvider(): NativeTlsProvider = object : NativeTlsProvider {
    init { OPENSSL_init_ssl(0u, null) }
    override fun server(clientFd: Int, certificatePath: String, privateKeyPath: String): NativeTlsConnection = memScoped {
        val context = SSL_CTX_new(TLS_server_method()) ?: error("unable to create TLS server context")
        lynx_ssl_enable_h2_server(context)
        require(SSL_CTX_use_certificate_file(context, certificatePath, PEM) == 1)
        require(SSL_CTX_use_PrivateKey_file(context, privateKeyPath, PEM) == 1)
        val ssl = SSL_new(context) ?: error("unable to create TLS server session")
        require(SSL_set_fd(ssl, clientFd) == 1)
        require(SSL_accept(ssl) == 1) { "TLS client handshake failed: ${sslError()}" }
        OpenSslConnection(clientFd, ssl, context, if (lynx_ssl_is_h2(ssl) == 1) "h2" else null)
    }
    override fun client(upstreamFd: Int): NativeTlsConnection = memScoped {
        val context = SSL_CTX_new(TLS_client_method()) ?: error("unable to create TLS client context")
        val ssl = SSL_new(context) ?: error("unable to create TLS client session")
        require(SSL_set_fd(ssl, upstreamFd) == 1)
        require(lynx_ssl_enable_h2_client(ssl) == 0)
        require(SSL_connect(ssl) == 1) { "TLS upstream handshake failed: ${sslError()}" }
        OpenSslConnection(upstreamFd, ssl, context, if (lynx_ssl_is_h2(ssl) == 1) "h2" else null)
    }
}

@OptIn(ExperimentalForeignApi::class)
private fun sslError(): String = memScoped { val buffer = allocArray<ByteVar>(256); ERR_error_string_n(ERR_get_error(), buffer, 256u); buffer.toKString() }
