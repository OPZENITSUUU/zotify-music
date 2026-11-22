package com.music.bitchord.data.discord

import android.content.Context
import android.content.Intent
import android.net.Uri
import android.widget.Toast
import com.music.bitchord.data.settings.AppSettings
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext
import okhttp3.MediaType.Companion.toMediaType
import okhttp3.Request
import okhttp3.RequestBody.Companion.toRequestBody
import org.json.JSONObject

/**
 * Official Discord OAuth authorization-code flow.
 *
 * Discord credentials are handled by Discord in the browser. The app receives
 * only a short-lived one-time ticket, exchanges it with the Zotify backend,
 * then stores the backend session in encrypted app storage.
 */
object DiscordOAuth {
    private const val REDIRECT_URI = "zotify://discord/oauth/callback"
    private const val START_PATH = "/auth/discord?redirect_uri="

    fun start(context: Context) {
        val uri = Uri.parse(
            DiscordRPC.BASE_URL + START_PATH + Uri.encode(REDIRECT_URI)
        )
        context.startActivity(Intent(Intent.ACTION_VIEW, uri))
    }

    fun consumeCallback(context: Context, intent: Intent?, scope: CoroutineScope) {
        if (intent?.action != Intent.ACTION_VIEW) return
        val callback = intent.data ?: return
        if (callback.scheme != "zotify" ||
            callback.host != "discord" ||
            callback.path != "/oauth/callback"
        ) return

        val error = callback.getQueryParameter("error")
        if (!error.isNullOrBlank()) {
            Toast.makeText(context, "Discord authorization was cancelled", Toast.LENGTH_LONG).show()
            return
        }

        val ticket = callback.getQueryParameter("ticket")
        if (ticket.isNullOrBlank()) {
            Toast.makeText(context, "Discord authorization returned no ticket", Toast.LENGTH_LONG).show()
            return
        }

        scope.launch(Dispatchers.IO) {
            try {
                val payload = JSONObject().put("ticket", ticket)
                val request = Request.Builder()
                    .url(DiscordRPC.BASE_URL + "/auth/discord/exchange")
                    .post(
                        payload.toString().toRequestBody(
                            "application/json; charset=utf-8".toMediaType()
                        )
                    )
                    .build()

                DiscordRPC.httpClient.newCall(request).execute().use { response ->
                    if (!response.isSuccessful) {
                        error("Discord authorization failed (HTTP ${response.code})")
                    }
                    val result = JSONObject(response.body?.string().orEmpty())
                    val user = result.getJSONObject("user")
                    val session = result.getString("access_token")
                    if (session.isBlank()) error("The Discord session was empty")

                    withContext(Dispatchers.Main) {
                        AppSettings.setDiscordSessionToken(session)
                        AppSettings.setDiscordAccount(
                            user.optString("username"),
                            user.optString("global_name").ifBlank {
                                user.optString("username")
                            },
                            user.optString("avatar").takeIf { it.isNotBlank() },
                        )
                        Toast.makeText(context, "Discord connected", Toast.LENGTH_SHORT).show()
                    }
                }
            } 
            catch (failure: Exception) {
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
}
