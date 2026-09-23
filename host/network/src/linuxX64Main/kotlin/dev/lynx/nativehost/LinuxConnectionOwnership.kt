package dev.lynx.nativehost

import dev.lynx.model.SocketTuple

actual fun nativeConnectionOwnerResolver(): ConnectionOwnerResolver? = null
actual fun nativeAcceptedSocketTuple(fileDescriptor: Int): SocketTuple? = null
