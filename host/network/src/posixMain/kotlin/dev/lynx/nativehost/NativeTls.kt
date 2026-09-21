package dev.lynx.nativehost

/** Thin boundary around the host's native TLS implementation. */
interface NativeTlsConnection {
    /** Underlying connected socket used by protocol-aware relay loops. */
    val fileDescriptor: Int
    /** Negotiated application protocol, for example `h2` or null. */
    val applicationProtocol: String?
    fun read(maxBytes: Int = 16 * 1024): ByteArray?
    fun write(bytes: ByteArray)
    fun close()
}

interface NativeTlsProvider {
    fun server(clientFd: Int, certificatePath: String, privateKeyPath: String, enableHttp2: Boolean = true): NativeTlsConnection
    fun client(upstreamFd: Int): NativeTlsConnection
}

expect fun nativeTlsProvider(): NativeTlsProvider
