package com.music.bitchord.data.discord

import android.content.Context
import android.content.Intent
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext
import org.json.JSONObject
import java.net.HttpURLConnection
import java.net.URL
import java.net.URLEncoder
import java.util.concurrent.ConcurrentHashMap

/**
 * Bridges Zotify playback into Android's Spotify-compatible media broadcasts.
 * Discord's Android client can consume these broadcasts and render its native
 * Spotify listening card; this is separate from the app's user-authorized RPC.
 */
object SpotifyMediaSessionBridge {
    private const val TOKEN_URL = "https://open.spotify.com/get_access_token"
    private const val SEARCH_URL = "https://api.spotify.com/v1/search"
    private val trackUris = ConcurrentHashMap<String, String>()
    @Volatile private var token: String? = null
    @Volatile private var tokenExpiresAt = 0L

    suspend fun publishTrack(
        context: Context,
        title: String,
        artist: String,
        positionMs: Long,
        playing: Boolean,
    ) = withContext(Dispatchers.IO) {
        runCatching {
            val cleanTitle = title.trim()
            val cleanArtist = artist.trim()
            if (cleanTitle.isBlank() || cleanArtist.isBlank()) return@runCatching
            val key = "$cleanTitle|$cleanArtist"
            val uri = trackUris[key] ?: searchTrack(accessToken(), cleanTitle, cleanArtist)
                ?.also { if (trackUris.size > 500) trackUris.clear(); trackUris[key] = it }
                ?: return@runCatching

            context.sendBroadcast(Intent(ACTION_METADATA).apply {
                putExtra("id", uri)
                putExtra("track", cleanTitle)
                putExtra("artist", cleanArtist)
                putExtra("timeSent", System.currentTimeMillis())
            })
            publishPlaybackState(context, playing, positionMs)
        }
    }

    fun publishPlaybackState(context: Context, playing: Boolean, positionMs: Long) {
        context.sendBroadcast(Intent(ACTION_PLAYBACK).apply {
            putExtra("playing", playing)
            putExtra("playbackPosition", positionMs.coerceAtLeast(0L).coerceAtMost(Int.MAX_VALUE.toLong()).toInt())
            putExtra("timeSent", System.currentTimeMillis())
        })
    }

    private fun accessToken(): String {
        if (!token.isNullOrBlank() && System.currentTimeMillis() < tokenExpiresAt - 30_000L) {
            return token!!
        }
        val connection = (URL(TOKEN_URL).openConnection() as HttpURLConnection).apply {
            connectTimeout = 8_000
            readTimeout = 8_000
            setRequestProperty("User-Agent", "Mozilla/5.0")
        }
        try {
            val response = connection.inputStream.bufferedReader().use { it.readText() }
            val json = JSONObject(response)
            return json.getString("accessToken").also {
                token = it
                tokenExpiresAt = json.optLong("accessTokenExpirationTimestampMs", System.currentTimeMillis() + 300_000L)
            }
        } finally {
            connection.disconnect()
        }
    }

    private fun searchTrack(accessToken: String, title: String, artist: String): String? {
        val query = URLEncoder.encode("$title artist:$artist", Charsets.UTF_8.name())
        val url = URL("$SEARCH_URL?q=$query&type=track&limit=1")
        val connection = (url.openConnection() as HttpURLConnection).apply {
            connectTimeout = 8_000
            readTimeout = 8_000
            setRequestProperty("Authorization", "Bearer $accessToken")
        }
        try {
            if (connection.responseCode !in 200..299) return null
            val json = JSONObject(connection.inputStream.bufferedReader().use { it.readText() })
            return json.optJSONObject("tracks")?.optJSONArray("items")
                ?.optJSONObject(0)?.optString("uri")?.takeIf { it.startsWith("spotify:track:") }
        } finally {
            connection.disconnect()
        }
    }

    private const val ACTION_METADATA = "com.spotify.music.metadatachanged"
    private const val ACTION_PLAYBACK = "com.spotify.music.playbackstatechanged"
}
