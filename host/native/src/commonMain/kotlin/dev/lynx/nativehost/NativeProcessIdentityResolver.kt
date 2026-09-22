package dev.lynx.nativehost

data class NativeProcessIdentity(val pid: Int, val startIdentity: String)

fun interface NativeProcessIdentityResolver {
    fun resolve(pid: Int): NativeProcessIdentity?
}
