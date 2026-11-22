package com.music.bitchord.data.discord

import android.content.Context
import android.content.Intent
import android.net.Uri
import android.util.Base64
import android.widget.Toast
import com.music.bitchord.data.settings.AppSettings
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext
import org.json.JSONObject
import java.net.HttpURLConnection
import java.net.URL
import java.security.MessageDigest
import java.security.SecureRandom

/**
 * Official Discord OAuth authorization-code flow using PKCE.
 *
 * Discord credentials are handled by Discord in the browser. The redirect may
 * expose the one-time ticket to another app that claims the custom URI scheme,
 * but that app cannot exchange it without the verifier kept in encrypted app
 * storage.
 */
object DiscordOAuth {
    private const val REDIRECT_URI = "zotify://discord/oauth/callback"
    private const val START_URL = "/auth/discord"

    fun start(context: Context) {
        val random = ByteArray(32).also(SecureRandom()::nextBytes)
        val verifier = base64Url(random)
        val challenge = base64Url(
            MessageDigest.getInstance("SHA-256").digest(verifier.toByteArray(Charsets.US_ASCII))
        )
        AppSettings.setPendingDiscordOAuthVerifier(verifier)

        val uri = Uri.parse(DiscordRPC.BASE_URL + START_URL).buildUpon()
            .appendQueryParameter("redirect_uri", REDIRECT_URI)
            .appendQueryParameter("code_challenge", challenge)
            .appendQueryParameter("code_challenge_method", "S256")
            .build()
        try {
            context.startActivity(Intent(Intent.ACTION_VIEW, uri))
        } catch (failure: Exception) {
            AppSettings.setPendingDiscordOAuthVerifier(null)
            Toast.makeText(
                context,
                "Couldn't open a browser for Discord authorization",
                Toast.LENGTH_LONG,
            ).show()
        }
    }

    fun consumeCallback(context: Context, intent: Intent?, scope: CoroutineScope) {
        if (intent?.action != Intent.ACTION_VIEW) return
        val callback = intent.data ?: return
        if (callback.scheme != "zotify" || callback.host != "discord") return

        // A bare public install link bounces here first. Generate PKCE locally,
        // store the verifier encrypted, then begin the ordinary OAuth request.
        if (callback.path == "/oauth/start") {
            start(context)
            return
        }
        if (callback.path != "/oauth/callback") return

        val error = callback.getQueryParameter("error")
        if (!error.isNullOrBlank()) {
            AppSettings.setPendingDiscordOAuthVerifier(null)
            Toast.makeText(context, "Discord authorization was cancelled", Toast.LENGTH_LONG).show()
            return
        }

        val ticket = callback.getQueryParameter("ticket")
        if (ticket.isNullOrBlank()) {
            AppSettings.setPendingDiscordOAuthVerifier(null)
            Toast.makeText(context, "Discord authorization returned no ticket", Toast.LENGTH_LONG).show()
            return
        }
        val verifier = AppSettings.takePendingDiscordOAuthVerifier()
        if (verifier.isNullOrBlank()) {
            Toast.makeText(context, "Discord authorization expired. Please connect again.", Toast.LENGTH_LONG).show()
            return
        }

        scope.launch(Dispatchers.IO) {
            try {
                val payload = JSONObject()
                    .put("ticket", ticket)
                    .put("code_verifier", verifier)
                val connection = (URL(DiscordRPC.BASE_URL + "/auth/discord/exchange")
                    .openConnection() as HttpURLConnection).apply {
                    requestMethod = "POST"
                    connectTimeout = 10_000
                    readTimeout = 10_000
                    doOutput = true
                    setRequestProperty("Content-Type", "application/json; charset=utf-8")
                }

                val responseBody: String
                try {
                    connection.outputStream.use {
                        it.write(payload.toString().toByteArray(Charsets.UTF_8))
                    }
                    val code = connection.responseCode
                    val stream = if (code in 200..299) connection.inputStream else connection.errorStream
                    responseBody = stream?.bufferedReader(Charsets.UTF_8)?.use { it.readText() }.orEmpty()
                    if (code !in 200..299) {
                        error("Discord authorization failed (HTTP $code)")
                    }
                } finally {
                    connection.disconnect()
                }

                val result = JSONObject(responseBody)
                val user = result.getJSONObject("user")
                val session = result.getString("access_token")
                val refreshToken = result.optString("refresh_token")
                val expiresIn = result.optLong("expires_in", 0L)
                if (session.isBlank()) error("The Discord access token was empty")
                if (refreshToken.isBlank()) error("Discord did not return a refresh token")
                val avatarHash = user.optString("avatar").takeIf { it.isNotBlank() }
                val avatarUrl = avatarHash?.let { hash ->
                    val extension = if (hash.startsWith("a_")) "gif" else "png"
                    "https://cdn.discordapp.com/avatars/${user.getString("id")}/$hash.$extension?size=128"
                }

                withContext(Dispatchers.Main) {
                    AppSettings.setDiscordOAuthTokens(session, refreshToken, expiresIn)
                    AppSettings.setDiscordAccount(
                        user.optString("username"),
                        user.optString("global_name").ifBlank {
                            user.optString("username")
                        },
                        avatarUrl,
                    )
                    Toast.makeText(context, "Discord connected", Toast.LENGTH_SHORT).show()
                }
            } catch (failure: Exception) {
                withContext(Dispatchers.Main) {
                    Toast.makeText(
                        context,
                        failure.message?.take(120) ?: "Discord authorization failed",
                        Toast.LENGTH_LONG,
                    ).show()
                }
            }
        }
    }

    private fun base64Url(bytes: ByteArray): String =
        Base64.encodeToString(bytes, Base64.URL_SAFE or Base64.NO_PADDING or Base64.NO_WRAP)
}
