package com.music.bitchord

import com.music.bitchord.data.spotify.PlaylistArtworkStore
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNotEquals
import org.junit.Assert.assertTrue
import org.junit.Test

class PlaylistArtworkStoreTest {
    @Test
    fun stableKeyIsDeterministicAndFilesystemSafe() {
        val first = PlaylistArtworkStore.stableKey("VLplaylist-123")
        assertEquals(first, PlaylistArtworkStore.stableKey("VLplaylist-123"))
        assertNotEquals(first, PlaylistArtworkStore.stableKey("VLplaylist-456"))
        assertTrue(first.matches(Regex("[0-9a-f]{64}")))
    }
}
