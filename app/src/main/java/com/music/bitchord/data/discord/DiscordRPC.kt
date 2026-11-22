package com.music.bitchord.data.discord

import com.music.bitchord.data.model.Song
import org.json.JSONObject
import java.net.HttpURLConnection
import java.net.URL
import java.util.Locale

/**
 * Client for Zotify's official Discord bot bridge. This deliberately never
 * authenticates as a Discord user or handles a Discord user access token.
 */
class DiscordRPC(private val sessionToken: String) {
    suspend fun updateSong(
        song: Song,
        currentPlaybackTimeMillis: Long,
        durationMillis: Long,
        playbackSpeed: Float = 1.0f,
        useDetails: Boolean = false,
        status: String = "online",
        activityType: String = "listening",
        activityName: String = "",
        audioQuality: String? = null,
        paused: Boolean = false,
    ) = runCatching {
        val title = if (playbackSpeed != 1.0f) {
            "${song.title} [${"%.2f".format(Locale.ROOT, playbackSpeed)}x]"
        } else {
            song.title
        }
        request("/presence", JSONObject().apply {
            put("title", title)
            put("artist", song.artist)
            put("paused", paused)
            put("activityType", activityType)
            put("activityName", activityName)
            put("status", status)
        })
    }

    suspend fun pause(
        song: Song,
        positionMs: Long,
        durationMs: Long,
        activityName: String,
        activityType: String,
        status: String,
    ) = runCatching {
        request("/presence", JSONObject().apply {
            put("title", song.title)
            put("artist", song.artist)
            put("paused", true)
            put("activityType", activityType)
            put("activityName", activityName)
            put("status", status)
        })
    }

    suspend fun close() {
        request("/clear", JSONObject())
    }

    suspend fun closeRPC() = Unit

    suspend fun wakeUp() {
        request("/health", null, authenticated = false)
    }

    private fun request(path: String, body: JSONObject?, authenticated: Boolean = true) {
        val connection = (URL(BASE_URL + path).openConnection() as HttpURLConnection).apply {
            requestMethod = if (body == null) "GET" else "POST"
            connectTimeout = 8_000
            readTimeout = 8_000
            if (authenticated) setRequestProperty("Authorization", "Bearer $sessionToken")
            if (body != null) {
                doOutput = true
                setRequestProperty("Content-Type", "application/json")
            }
        }
        try {
            if (body != null) {
                connection.outputStream.use {
                    it.write(body.toString().toByteArray(Charsets.UTF_8))
                }
            }
            val code = connection.responseCode
            if (code !in 200..299) {
                throw IllegalStateException("Zotify bot returned HTTP $code")
            }
            connection.inputStream.close()
        } finally {
            connection.disconnect()
        }
    }

    companion object {
        internal val httpClient: okhttp3.OkHttpClient by lazy {
            okhttp3.OkHttpClient.Builder().build()
        }
        const val BASE_URL = "https://zotify-discord-bot.onrender.com"
    }
}
