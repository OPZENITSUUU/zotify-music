package com.music.bitchord.data.discord

import android.app.Activity
import android.os.Handler
import android.os.Looper

/**
 * Personal-account Rich Presence through Discord's official Android RPC transport.
 *
 * This is separate from the Zotify bot bridge: it talks to the Discord app already
 * signed in on this device and never asks for a Discord user token.
 */
object DiscordSocialPresence {
    private val mainHandler = Handler(Looper.getMainLooper())

    @Volatile
    private var ready = false

    private val callbackPump = object : Runnable {
        override fun run() {
            if (!ready) return
            runCatching { nativeRunCallbacks() }
            mainHandler.postDelayed(this, CALLBACK_INTERVAL_MS)
        }
    }

    @Synchronized
    fun initialize(activity: Activity) {
        try {
            // The SDK keeps an Activity reference; refresh it after Android recreates
            // MainActivity (rotation, process restore, or returning from Settings).
            com.discord.socialsdk.DiscordSocialSdkInit.setEngineActivity(activity)
            if (ready) return
            System.loadLibrary("bitchord_discord")
            ready = nativeInit()
            if (ready) {
                mainHandler.removeCallbacks(callbackPump)
                mainHandler.post(callbackPump)
            }
        } catch (_: Throwable) {
            // Missing/ineligible SDK or Discord installation must never affect audio playback.
            ready = false
        }
    }

    fun update(
        title: String,
        artist: String,
        artworkUrl: String?,
        positionMs: Long,
        durationMs: Long,
        spotifyStyle: Boolean = true,
        playing: Boolean = true,
    ) {
        if (!ready || title.isBlank()) return
        runCatching {
            nativeUpdate(
                title,
                artist,
                artworkUrl.orEmpty(),
                positionMs.coerceAtLeast(0L),
                durationMs.coerceAtLeast(0L),
                spotifyStyle,
                playing,
            )
        }
    }

    fun clear() {
        if (!ready) return
        runCatching { nativeClear() }
    }

    val isAvailable: Boolean
        get() = ready

    private external fun nativeInit(): Boolean
    private external fun nativeUpdate(
        title: String,
        artist: String,
        artworkUrl: String,
        positionMs: Long,
        durationMs: Long,
        spotifyStyle: Boolean,
        playing: Boolean,
    )
    private external fun nativeClear()
    private external fun nativeRunCallbacks()

    private const val CALLBACK_INTERVAL_MS = 100L
}
