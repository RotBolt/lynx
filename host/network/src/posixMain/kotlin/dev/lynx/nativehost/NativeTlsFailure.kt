package dev.lynx.nativehost

internal class NativeTlsFailure(val kind: String, cause: Throwable) : RuntimeException(cause.message, cause)

internal fun nativeTlsFailureKind(failure: Throwable): String =
    (failure as? NativeTlsFailure)?.kind ?: "HTTPS_MITM_ERROR"
