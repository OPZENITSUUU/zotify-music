package com.music.bitchord.data.webdav

import java.net.URI
import java.util.Locale

/**
 * In-memory credentials for [com.music.bitchord.data.Http]'s interceptor.
 *
 * Held outside SharedPreferences so the network layer never touches prefs:
 * [com.music.bitchord.data.settings.AppSettings] publishes here on init and
 * on every WebDAV edit. Authorization is scoped to scheme, host and port so
 * a same-host URL on another service cannot inherit the credential.
 */
object WebDavAuth {
    @Volatile var origin: String? = null
    @Volatile var authHeader: String? = null

    /** [origin] and [header] as WebDavConfig derives them from the saved server. */
    fun update(origin: String?, header: String?) {
        this.origin = origin?.lowercase(Locale.ROOT)
        authHeader = header
    }

    fun shouldAuthorize(requestUrl: String): Boolean {
        val expected = origin ?: return false
        val header = authHeader ?: return false
        if (header.isBlank()) return false
        val actual = canonicalOrigin(requestUrl) ?: return false
        return actual == expected
    }

    fun canonicalOrigin(rawUrl: String): String? {
        val url = rawUrl.trim().let {
            if (it.startsWith("http://", true) || it.startsWith("https://", true)) it else "https://$it"
        }
        val uri = runCatching { URI(url) }.getOrNull() ?: return null
        val scheme = uri.scheme?.lowercase(Locale.ROOT) ?: return null
        if (scheme != "http" && scheme != "https") return null
        val host = uri.host?.lowercase(Locale.ROOT)?.takeIf { it.isNotBlank() } ?: return null
        val port = if (uri.port >= 0) uri.port else if (scheme == "https") 443 else 80
        return "$scheme://$host:$port"
    }
}
