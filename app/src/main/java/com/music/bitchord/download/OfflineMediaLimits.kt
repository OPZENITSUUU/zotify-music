package com.music.bitchord.download

import java.io.ByteArrayOutputStream
import java.io.IOException
import java.io.InputStream
import java.io.OutputStream

/** Resource ceilings for remote manifest-driven offline downloads. */
internal object OfflineMediaLimits {
    const val MAX_MANIFEST_BYTES = 2L * 1024 * 1024
    const val MAX_SEGMENTS = 10_000
    const val MAX_SEGMENT_BYTES = 128L * 1024 * 1024
    const val MAX_TRACK_BYTES = 1024L * 1024 * 1024

    fun readBounded(input: InputStream, maxBytes: Long): ByteArray {
        val output = ByteArrayOutputStream(minOf(maxBytes, 64 * 1024L).toInt())
        copyBounded(input, output, maxBytes)
        return output.toByteArray()
    }

    /** Copies no more than [maxBytes], failing rather than silently truncating. */
    fun copyBounded(input: InputStream, output: OutputStream, maxBytes: Long): Long {
        require(maxBytes >= 0) { "Negative byte limit" }
        val buffer = ByteArray(DEFAULT_BUFFER_SIZE)
        var copied = 0L
        while (true) {
            val remaining = maxBytes - copied
            if (remaining == 0L) {
                if (input.read() < 0) return copied
                throw IOException("Remote media exceeded the configured byte limit")
            }
            val readLimit = minOf(buffer.size.toLong(), remaining).toInt()
            val read = input.read(buffer, 0, readLimit)
            if (read < 0) return copied
            if (read == 0) continue
            output.write(buffer, 0, read)
            copied += read
        }
    }
}
