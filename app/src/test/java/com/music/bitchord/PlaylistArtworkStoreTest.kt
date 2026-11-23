package com.music.bitchord

import com.music.bitchord.data.spotify.PlaylistArtworkStore
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertNotEquals
import kotlin.test.assertTrue

class PlaylistArtworkStoreTest {
    @Test
    fun stableKeyIsDeterministicAndFilesystemSafe() {
        val first = PlaylistArtworkStore.stableKey("VLplaylist-123")
        assertEquals(first, PlaylistArtworkStore.stableKey("VLplaylist-123"))
        assertNotEquals(first, PlaylistArtworkStore.stableKey("VLplaylist-456"))
        assertTrue(first.matches(Regex("[0-9a-f]{64}")))
    }
}
