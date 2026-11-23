package com.music.bitchord.data.discord

import org.junit.Assert.assertEquals
import org.junit.Test

class DiscordPresenceTextTest {

    @Test
    fun `lead with song shows title and artist exactly once`() {
        assertEquals(
            "Say Something" to "Haven",
            discordPresenceTextLines(
                title = "Say Something",
                artist = "Haven",
                leadWithSong = true,
            ),
        )
    }

    @Test
    fun `artist first keeps title on the second line`() {
        assertEquals(
            "Haven" to "Say Something",
            discordPresenceTextLines(
                title = "Say Something",
                artist = "Haven",
                leadWithSong = false,
            ),
        )
    }

    @Test
    fun `custom details and state override track defaults`() {
        assertEquals(
            "Now playing" to "From my library",
            discordPresenceTextLines(
                title = "Say Something",
                artist = "Haven",
                leadWithSong = true,
                customDetails = "Now playing",
                customState = "From my library",
            ),
        )
    }

    @Test
    fun `blank custom values preserve the two line track layout`() {
        val lines = discordPresenceTextLines(
            title = "Say Something",
            artist = "Haven",
            leadWithSong = true,
            customDetails = " ",
            customState = "",
        )
        assertEquals("Say Something", lines.first)
        assertEquals("Haven", lines.second)
        assertNotEquals(lines.first, lines.second)
    }
}
