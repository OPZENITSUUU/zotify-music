package com.music.bitchord.data.discord

import com.music.bitchord.data.model.Song
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext
import org.json.JSONArray
import org.json.JSONObject
import java.net.HttpURLConnection
import java.net.URL
import java.net.URLEncoder
import java.util.Locale

/**
 * Publishes music to the connected user's Discord profile through Discord's
 * Social Layer gateway. The OAuth token is user-authorized with PKCE; no bot
 * token or shared bot-profile presence is used for playback.
 */
class DiscordRPC(
    private var accessToken: String,
    private var refreshToken: String = "",
    expiresInSeconds: Long = 0L,
    private val onTokensRefreshed: (String, String, Long) -> Unit = { _, _, _ -> },
) {
    private var expiresAtMs = if (expiresInSeconds > 0L) {
        System.currentTimeMillis() + expiresInSeconds * 1_000L
    } else 0L
    private var gateway: DiscordGateway? = null
    private val externalAssets = LinkedHashMap<String, String>(64, 0.75f, true)
    private var lastActivity: JSONObject? = null
    private var lastStatus: String = "online"

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
        spotifySpoof: Boolean = false,
        artworkUrl: String? = null,
        button1Text: String = "",
        button1Visible: Boolean = true,
        button2Text: String = "",
        button2Visible: Boolean = true,
    ) = runCatching {
        val title = if (playbackSpeed != 1.0f) {
            "${song.title} [${"%.2f".format(Locale.ROOT, playbackSpeed)}x]"
        } else song.title
        val details = if (useDetails) title else song.artist
        val state = buildString {
            append(if (useDetails) song.artist else title)
            audioQuality?.takeIf { it.isNotBlank() }?.let { append(" · "); append(it) }
        }
        val kind = if (spotifySpoof) "listening" else activityType
        val type = when (kind.lowercase(Locale.ROOT)) {
            "playing" -> 0
            "watching" -> 3
            "competing" -> 5
            else -> 2
        }
        val name = if (spotifySpoof) "Spotify" else activityName.trim().ifBlank { "Zotify Music" }
        val now = System.currentTimeMillis()
        val speed = playbackSpeed.coerceAtLeast(0.1f)
        val start = now - (currentPlaybackTimeMillis.coerceAtLeast(0L) / speed).toLong()
        val end = start + (durationMillis.coerceAtLeast(0L) / speed).toLong()
        val activity = JSONObject()
            .put("name", name)
            .put("type", if (spotifySpoof) 2 else type)
            .put("application_id", APPLICATION_ID)
            .put("details", details.take(128))
            .put("state", state.take(128))
        val registeredArtwork = artworkUrl?.takeIf { it.startsWith("https://") }?.let { registerExternalAsset(it) }
        if (!registeredArtwork.isNullOrBlank()) {
            activity.put("assets", JSONObject()
                .put("large_image", registeredArtwork)
                .put("large_text", title.take(128)))
        }
        val buttonLabels = JSONArray()
        val buttonUrls = JSONArray()
        if (button1Visible) {
            buttonLabels.put(button1Text.trim().ifBlank { "YouTube Music" }.take(32))
            val video = song.videoId.trim()
            val query = URLEncoder.encode("${song.title} ${song.artist}", Charsets.UTF_8.name())
            buttonUrls.put(if (video.isNotBlank()) "https://music.youtube.com/watch?v=$video"
                else "https://music.youtube.com/search?q=$query")
        }
        if (button2Visible) {
            buttonLabels.put(button2Text.trim().ifBlank { "Spotify" }.take(32))
            val query = URLEncoder.encode("${song.title} ${song.artist}", Charsets.UTF_8.name())
            buttonUrls.put("https://open.spotify.com/search/$query")
        }
        if (buttonLabels.length() > 0) {
            activity.put("buttons", buttonLabels)
            activity.put("metadata", JSONObject().put("button_urls", buttonUrls))
        }
        if (!paused && durationMillis > 0L) {
            val timestamps = JSONObject().put("start", start)
            if (type == 2 || spotifySpoof) timestamps.put("end", end)
            activity.put("timestamps", timestamps)
        }
        lastActivity = activity
        lastStatus = status
        publish(activity, status)
    }

    suspend fun pause(
        song: Song,
        positionMs: Long,
        durationMs: Long,
        activityName: String,
        activityType: String,
        status: String,
    ) = updateSong(
        song = song,
        currentPlaybackTimeMillis = positionMs,
        durationMillis = durationMs,
        activityName = activityName,
        activityType = activityType,
        status = status,
        paused = true,
    )

    suspend fun close() {
        lastActivity = null
        gateway?.setActivity(null, lastStatus)
    }

    suspend fun closeRPC() {
        gateway?.close()
        gateway = null
    }

    suspend fun wakeUp() {
        val activity = lastActivity
        if (activity != null) publish(activity, lastStatus)
        else ensureGateway()
    }

    private suspend fun registerExternalAsset(url: String): String? = withContext(Dispatchers.IO) {
        synchronized(externalAssets) { externalAssets[url] }?.let { return@withContext it }
        fun register(): String? {
            val connection = (URL("https://discord.com/api/v10/applications/$APPLICATION_ID/external-assets")
                .openConnection() as HttpURLConnection).apply {
                requestMethod = "POST"
                connectTimeout = 10_000
                readTimeout = 10_000
                doOutput = true
                setRequestProperty("Authorization", "Bearer $accessToken")
                setRequestProperty("Content-Type", "application/json; charset=utf-8")
            }
            val response: String
            try {
                connection.outputStream.use {
                    it.write(JSONObject().put("urls", JSONArray().put(url)).toString().toByteArray(Charsets.UTF_8))
                }
                val code = connection.responseCode
                val stream = if (code in 200..299) connection.inputStream else connection.errorStream
                response = stream?.bufferedReader(Charsets.UTF_8)?.use { it.readText() }.orEmpty()
                if (code !in 200..299) return null
            } finally {
                connection.disconnect()
            }
            val path = runCatching { JSONArray(response).optJSONObject(0)?.optString("external_asset_path") }.getOrNull()
                ?.takeIf { it.isNotBlank() } ?: return null
            return "mp:$path"
        }
        var registered = register()
        if (registered == null && refreshToken.isNotBlank()) {
            runCatching { refreshTokens() }
            registered = register()
        }
        if (registered != null) synchronized(externalAssets) {
            externalAssets[url] = registered
            if (externalAssets.size > 128) externalAssets.remove(externalAssets.keys.first())
        }
        registered
    }

    private suspend fun publish(activity: JSONObject, status: String) {
        refreshIfNeeded()
        try {
            ensureGateway().setActivity(activity, status)
        } catch (failure: Exception) {
            if (refreshToken.isBlank()) throw failure
            // A revoked/expired access token can outlive its local expiry clock.
            refreshTokens()
            gateway?.close()
            gateway = null
            ensureGateway().setActivity(activity, status)
        }
    }

    private suspend fun ensureGateway(): DiscordGateway {
        gateway?.let { return it }
        val created = DiscordGateway(accessToken)
        created.connect()
        gateway = created
        return created
    }

    private suspend fun refreshIfNeeded() {
        if (expiresAtMs == 0L || System.currentTimeMillis() < expiresAtMs - TOKEN_REFRESH_EARLY_MS) return
        refreshTokens()
    }

    private suspend fun refreshTokens() = withContext(Dispatchers.IO) {
        check(refreshToken.isNotBlank()) { "Discord session expired. Reconnect Discord." }
        val connection = (URL(BASE_URL + "/auth/discord/refresh").openConnection() as HttpURLConnection).apply {
            requestMethod = "POST"
            connectTimeout = 10_000
            readTimeout = 10_000
            doOutput = true
            setRequestProperty("Content-Type", "application/json; charset=utf-8")
        }
        val response: String
        try {
            connection.outputStream.use {
                it.write(JSONObject().put("refresh_token", refreshToken).toString().toByteArray(Charsets.UTF_8))
            }
            val code = connection.responseCode
            val stream = if (code in 200..299) connection.inputStream else connection.errorStream
            response = stream?.bufferedReader(Charsets.UTF_8)?.use { it.readText() }.orEmpty()
            check(code in 200..299) { "Discord token refresh failed (HTTP $code)" }
        } finally {
            connection.disconnect()
        }
        val result = JSONObject(response)
        accessToken = result.getString("access_token")
        refreshToken = result.optString("refresh_token", refreshToken)
        val expiresIn = result.optLong("expires_in", 0L)
        expiresAtMs = if (expiresIn > 0L) System.currentTimeMillis() + expiresIn * 1_000L else 0L
        onTokensRefreshed(accessToken, refreshToken, expiresIn)
    }

    companion object {
        const val BASE_URL = "https://zotify-discord-bot.onrender.com"
        private const val APPLICATION_ID = "1573592442916307075"
        private const val TOKEN_REFRESH_EARLY_MS = 60_000L
    }
}
