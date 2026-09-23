package dev.lynx.nativehost

import dev.lynx.model.SocketTuple
import dev.lynx.nativehost.proc.lynx_socket_local_endpoint
import dev.lynx.nativehost.proc.lynx_socket_local_port
import kotlinx.cinterop.ByteVar
import kotlinx.cinterop.ExperimentalForeignApi
import kotlinx.cinterop.allocArray
import kotlinx.cinterop.memScoped
import kotlinx.cinterop.toKString

actual fun nativeConnectionOwnerResolver(): ConnectionOwnerResolver? = MacosConnectionOwnerResolver()

@OptIn(ExperimentalForeignApi::class)
actual fun nativeAcceptedSocketTuple(fileDescriptor: Int): SocketTuple? = memScoped {
    val address = allocArray<ByteVar>(46)
    if (lynx_socket_local_endpoint(fileDescriptor, address, 46u, null) != 0) null
    else lynx_socket_local_port(fileDescriptor).takeIf { it > 0 }
        ?.let { SocketTuple(address.toKString(), it, "", 0) }
}
