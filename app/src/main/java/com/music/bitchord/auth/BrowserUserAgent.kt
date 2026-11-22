package com.music.bitchord.auth

private val WEBVIEW_VERSION_TOKEN = Regex("""Version/\d+(\.\d+)*\s*""")

private const val FALLBACK_USER_AGENT =
    "Mozilla/5.0 (Linux; Android 14; SM-S921U; Build/UP1A.231005.007) " +
        "AppleWebKit/537.36 (KHTML, like Gecko) Chrome/129.0.0.0 Mobile Safari/537.36"

/**
 * Strips the two tokens that mark a user agent as coming from a WebView.
 * Discord's older user-agent parsing can misclassify Android WebView as the
 * unsupported Android Browser 4.0. Keep the real device and Chrome version.
 */
internal fun browserUserAgent(platformUserAgent: String?): String {
    val stripped = platformUserAgent
        ?.replace("; wv", "")
        ?.replace(WEBVIEW_VERSION_TOKEN, "")
        ?.replace("  ", " ")
        ?.trim()
    return stripped?.takeIf { it.contains("Chrome/") } ?: FALLBACK_USER_AGENT
}
