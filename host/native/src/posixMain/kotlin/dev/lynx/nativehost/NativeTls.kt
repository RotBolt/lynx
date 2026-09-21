package dev.lynx.nativehost

/** Thin boundary around the host's native TLS implementation. */
interface NativeTlsConnection {
    fun read(maxBytes: Int = 16 * 1024): ByteArray?
    fun write(bytes: ByteArray)
    fun close()
}

interface NativeTlsProvider {
    fun server(clientFd: Int, certificatePath: String, privateKeyPath: String): NativeTlsConnection
    fun client(upstreamFd: Int): NativeTlsConnection
}

expect fun nativeTlsProvider(): NativeTlsProvider
