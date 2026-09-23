package dev.lynx.nativehost

import dev.lynx.model.SocketTuple

/** Platform ownership adapter. Null means this platform has no safe resolver yet. */
expect fun nativeConnectionOwnerResolver(): ConnectionOwnerResolver?

/** Listener-local tuple for ownership lookup; null fails closed to pass-through. */
expect fun nativeAcceptedSocketTuple(fileDescriptor: Int): SocketTuple?
