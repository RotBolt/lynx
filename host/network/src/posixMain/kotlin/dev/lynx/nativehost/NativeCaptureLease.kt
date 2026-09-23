package dev.lynx.nativehost

/** Immutable identity a worker must echo after binding before routing may change. */
data class NativeCaptureLease(
    val captureId: String,
    val captureToken: String,
    val endpoint: String,
    val worker: NativeWorkerIdentity,
)
