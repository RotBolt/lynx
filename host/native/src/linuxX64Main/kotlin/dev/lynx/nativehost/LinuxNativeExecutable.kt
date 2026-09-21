package dev.lynx.nativehost

import kotlinx.cinterop.ByteVar
import kotlinx.cinterop.ExperimentalForeignApi
import kotlinx.cinterop.allocArray
import kotlinx.cinterop.memScoped
import kotlinx.cinterop.readBytes
import platform.posix.readlink

@OptIn(ExperimentalForeignApi::class)
actual fun nativeExecutablePath(): String? = memScoped {
    val buffer = allocArray<ByteVar>(4096)
    val length = readlink("/proc/self/exe", buffer, 4095u)
    if (length <= 0) null else {
        buffer.readBytes(length.toInt()).decodeToString()
    }
}
