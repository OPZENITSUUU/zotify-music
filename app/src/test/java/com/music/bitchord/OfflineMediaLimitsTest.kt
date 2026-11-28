package com.music.bitchord

import com.music.bitchord.download.OfflineMediaLimits
import java.io.ByteArrayInputStream
import java.io.ByteArrayOutputStream
import java.io.IOException
import org.junit.Assert.assertArrayEquals
import org.junit.Assert.assertEquals
import org.junit.Assert.assertThrows
import org.junit.Test

class OfflineMediaLimitsTest {
    @Test
    fun `copyBounded copies exactly up to its limit`() {
        val bytes = byteArrayOf(1, 2, 3, 4)
        val output = ByteArrayOutputStream()
        assertEquals(4L, OfflineMediaLimits.copyBounded(ByteArrayInputStream(bytes), output, 4))
        assertArrayEquals(bytes, output.toByteArray())
    }

    @Test
    fun `copyBounded fails rather than truncating an oversized response`() {
        val output = ByteArrayOutputStream()
        assertThrows(IOException::class.java) {
            OfflineMediaLimits.copyBounded(ByteArrayInputStream(byteArrayOf(1, 2, 3)), output, 2)
        }
        assertArrayEquals(byteArrayOf(1, 2), output.toByteArray())
    }
}
