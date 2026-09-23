package dev.lynx.nativehost

import dev.lynx.model.SocketTuple
import dev.lynx.nativehost.proc.lynx_socket_local_endpoint
import dev.lynx.nativehost.proc.lynx_socket_local_port
import dev.lynx.nativehost.proc.lynx_socket_peer_endpoint
import dev.lynx.nativehost.proc.lynx_socket_peer_port
import kotlinx.cinterop.ByteVar
import kotlinx.cinterop.ExperimentalForeignApi
import kotlinx.cinterop.allocArray
import kotlinx.cinterop.memScoped
import kotlinx.cinterop.toKString

actual fun nativeConnectionOwnerResolver(): ConnectionOwnerResolver? = MacosConnectionOwnerResolver()

@OptIn(ExperimentalForeignApi::class)
actual fun nativeAcceptedSocketTuple(fileDescriptor: Int): SocketTuple? = memScoped {
    val localAddress = allocArray<ByteVar>(46)
    val peerAddress = allocArray<ByteVar>(46)
    val localPort = lynx_socket_local_port(fileDescriptor)
    val peerPort = lynx_socket_peer_port(fileDescriptor)
    if (localPort <= 0 || lynx_socket_local_endpoint(fileDescriptor, localAddress, 46u, null) != 0 ||
        peerPort <= 0 || lynx_socket_peer_endpoint(fileDescriptor, peerAddress, 46u) != 0) null
    else SocketTuple(localAddress.toKString(), localPort, peerAddress.toKString(), peerPort)
}
