package com.music.bitchord.data.discord

import android.content.Context
import android.content.Intent
import android.net.Uri
import android.widget.Toast
import com.music.bitchord.data.settings.AppSettings
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext
import okhttp3.MediaType.Companion.toMediaType
import okhttp3.Request
import okhttp3.RequestBody.Companion.toRequestBody
import org.json.JSONObject

/** Discord OAuth2 authorization-code flow; no Discord user token or shared bridge secret is used. */
object DiscordOAuth {
    private const val REDIRECT_URI = "zotify://discord/oauth/callback"
    private const val START_PATH = "/auth/discord?redirect_uri="

    fun start(context: Context) {
        val uri = Uri.parse(DiscordRPC.BASE_URL + START_PATH + Uri.encode(REDIRECT_URI))
        context.startActivity(Intent(Intent.ACTION_VIEW, uri))
    }

    fun consumeCallback(context: Context, intent: Intent, scope: CoroutineScope) {
        if (intent.action != Intent.ACTION_VIEW || intent.data?.scheme != "zotify") return
        val ticket = intent.data?.getQueryParameter("ticket") ?: return
        scope.launch(Dispatchers.IO) {
            try {
                val payload = JSONObject().put("ticket", ticket)
                val request = Request.Builder()
                    .url(DiscordRPC.BASE_URL + "/auth/discord/exchange")
                    .post(payload.toString().toRequestBody("application/json".toMediaType()))
                    .build()
                DiscordRPC.httpClient.newCall(request).execute().use { response ->
                    if (!response.isSuccessful) error("Discord authorization failed (${response.code})")
                    val result = JSONObject(response.body?.string().orEmpty())
                    val user = result.getJSONObject("user")
                    val session = result.getString("access_token")
                    withContext(Dispatchers.Main) {
                        AppSettings.setDiscordSessionToken(session)
                        AppSettings.setDiscordAccount(
                            user.optString("username"),
                            user.optString("global_name"),
                            user.optString("avatar"),
                        )
                        Toast.makeText(context, "Discord connected", Toast.LENGTH_SHORT).show()
                    }
                }
            } catch (_: Exception) {
                withContext(Dispatchers.Main) {
                    Toast.makeText(context, "Discord authorization failed", Toast.LENGTH_LONG).show()
                }
            }
        }
    }
}
