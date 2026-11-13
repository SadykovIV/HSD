package com.hsd.universal.compat

import java.io.InputStream

internal fun InputStream.readUpTo(limit: Int): ByteArray {
    val bytes = ByteArray(limit)
    var size = 0
    while (size < bytes.size) {
        val n = read(bytes, size, bytes.size - size)
        if (n < 0) break
        size += n
    }
    return bytes.copyOf(size)
}
