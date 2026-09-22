package dev.lynx.nativehost

import dev.lynx.nativehost.zlib.lynx_gunzip
import dev.lynx.nativehost.zlib.lynx_gunzip_free
import kotlinx.cinterop.*

@OptIn(ExperimentalForeignApi::class)
internal object NativeGzipDecoder {
    fun decode(compressed: ByteArray): ByteArray? {
        if (compressed.isEmpty()) return null
        return compressed.usePinned { pinned ->
            memScoped {
                val output = allocPointerTo<UByteVar>()
                val outputSize = alloc<ULongVar>()
                if (lynx_gunzip(
                        pinned.addressOf(0).reinterpret(),
                        compressed.size.toULong(),
                        output.ptr,
                        outputSize.ptr,
                    ) != 0
                ) return@memScoped null

                val bytes = output.value ?: return@memScoped null
                try {
                    val size = outputSize.value
                    if (size > Int.MAX_VALUE.toULong()) null
                    else ByteArray(size.toInt()) { index -> bytes[index].toByte() }
                } finally {
                    lynx_gunzip_free(bytes)
                }
            }
        }
    }
}
