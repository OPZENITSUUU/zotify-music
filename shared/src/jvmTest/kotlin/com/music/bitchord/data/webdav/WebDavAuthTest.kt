package com.music.bitchord.data.webdav

import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFalse
import kotlin.test.assertNull
import kotlin.test.assertTrue

class WebDavAuthTest {
    @Test
    fun credentialsAreLimitedToTheConfiguredSchemeHostAndPort() {
        val header = "Basic dXNlcjpwYXNz"
        WebDavAuth.update("https://cloud.example.com:443", header)

        assertTrue(WebDavAuth.shouldAuthorize("https://cloud.example.com/Music/track.mp3"))
        assertFalse(WebDavAuth.shouldAuthorize("http://cloud.example.com/Music/track.mp3"))
        assertFalse(WebDavAuth.shouldAuthorize("https://cloud.example.com:8443/Music/track.mp3"))
        assertFalse(WebDavAuth.shouldAuthorize("https://attacker.example/Music/track.mp3"))
        assertEquals(header, WebDavAuth.authHeader)
    }

    @Test
    fun clearingTheConfigurationStopsAuthorization() {
        WebDavAuth.update("https://cloud.example.com:443", "Basic dXNlcjpwYXNz")
        WebDavAuth.update(null, null)

        assertFalse(WebDavAuth.shouldAuthorize("https://cloud.example.com/Music/track.mp3"))
        assertNull(WebDavAuth.authHeader)
    }
}
