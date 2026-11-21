package com.music.bitchord

import com.music.bitchord.data.AppUpdateChecker
import kotlinx.serialization.json.Json
import kotlinx.serialization.json.JsonObject
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test

class AppUpdateCheckerTest {
    private val digest = "a".repeat(64)

    @Test
    fun `direct APK selection requires SHA-256 digest`() {
        val release = release(asset(digestValue = null))

        assertNull(AppUpdateChecker.verifiedApkAsset(release))
    }

    @Test
    fun `direct APK selection rejects non-official or insecure asset URLs`() {
        val wrongHost = asset(url = "https://example.invalid/BitChord.apk")
        val cleartext = asset(url = "http://github.com/OPZENITSUUU/zotify-music/releases/download/v2/Zotify.Music.apk")

        assertNull(AppUpdateChecker.verifiedApkAsset(release(wrongHost)))
        assertNull(AppUpdateChecker.verifiedApkAsset(release(cleartext)))
    }

    @Test
    fun `matching arm64 APK is preferred over universal release APK`() {
        val arm64 = asset(name = "Zotify.Music_arm64-v8a.apk")
        val universal = asset(name = "Zotify.Music.apk")

        val selected = AppUpdateChecker.verifiedApkAsset(
            release(universal, arm64),
            supportedAbis = listOf("arm64-v8a", "armeabi-v7a"),
        )

        assertEquals("Zotify.Music_arm64-v8a.apk", selected?.name)
        assertEquals("https://github.com/OPZENITSUUU/zotify-music/releases/download/v2/Zotify.Music_arm64-v8a.apk", selected?.url)
        assertEquals(digest, selected?.sha256)
    }

    @Test
    fun `universal APK is fallback when no matching ABI split exists`() {
        val armv7 = asset(name = "Zotify.Music_armeabi-v7a.apk")
        val universal = asset(name = "Zotify.Music.apk")

        val selected = AppUpdateChecker.verifiedApkAsset(
            release(armv7, universal),
            supportedAbis = listOf("x86_64"),
        )

        assertEquals("Zotify.Music.apk", selected?.name)
        assertEquals("https://github.com/OPZENITSUUU/zotify-music/releases/download/v2/Zotify.Music.apk", selected?.url)
    }

    @Test
    fun `APK for another app or unsupported ABI is never selected`() {
        val otherApp = asset(name = "ZotifyMusicTV-v1.8_arm64-v8a.apk")
        val unsupportedSplit = asset(name = "Zotify.Music_x86_64.apk")

        assertNull(
            AppUpdateChecker.verifiedApkAsset(
                release(otherApp, unsupportedSplit),
                supportedAbis = listOf("arm64-v8a"),
            ),
        )
    }

    @Test
    fun `digest parser rejects malformed SHA-256 values`() {
        assertNull(AppUpdateChecker.normalizeSha256Digest("sha256:1234"))
        assertEquals(digest, AppUpdateChecker.normalizeSha256Digest("SHA256:$digest"))
    }

    @Test
    fun `release version code parser requires a positive integer metadata line`() {
        assertEquals(43L, AppUpdateChecker.releaseVersionCode("Release notes\nAndroid version code: 43\n- Fixes"))
        assertNull(AppUpdateChecker.releaseVersionCode("Release notes without version metadata"))
        assertNull(AppUpdateChecker.releaseVersionCode("Android version code: 0"))
        assertNull(AppUpdateChecker.releaseVersionCode("Android version code: 43abc"))
    }

    @Test
    fun `version code comparison detects only strictly higher codes`() {
        assertTrue(AppUpdateChecker.isNewerVersionCode(candidate = 44L, installed = 43L))
        assertFalse(AppUpdateChecker.isNewerVersionCode(candidate = 43L, installed = 43L))
        assertFalse(AppUpdateChecker.isNewerVersionCode(candidate = 42L, installed = 43L))
    }

    @Test
    fun `release eligibility blocks downgrades and preserves same-code beta to stable updates`() {
        assertTrue(AppUpdateChecker.shouldNotifyUpdate("1.8.2", 44L, "1.8.1", 43L, null))
        assertFalse(AppUpdateChecker.shouldNotifyUpdate("1.9.0", 42L, "1.8.1", 43L, null))
        assertTrue(AppUpdateChecker.shouldNotifyUpdate("1.8.1", 43L, "1.8.1-beta4", 43L, null))
        assertTrue(AppUpdateChecker.shouldNotifyUpdate("1.8.1-beta4", 43L, "1.8.1-beta1", 43L, null))
        assertFalse(AppUpdateChecker.shouldNotifyUpdate("1.8.1-beta2", 43L, "1.8.1-beta4", 43L, null))
        assertFalse(AppUpdateChecker.shouldNotifyUpdate("1.8.2", 44L, "1.8.1", 43L, "1.8.2"))
    }

    @Test
    fun `release checks are throttled to once per day`() {
        assertTrue(AppUpdateChecker.shouldCheckNow(0L, 100L))
        assertFalse(AppUpdateChecker.shouldCheckNow(10_000L, 10_000L + AppUpdateChecker.CHECK_INTERVAL_MS - 1))
        assertTrue(AppUpdateChecker.shouldCheckNow(10_000L, 10_000L + AppUpdateChecker.CHECK_INTERVAL_MS))
    }

    @Test
    fun `single signer update requires an installed certificate in candidate lineage`() {
        assertTrue(
            AppUpdateChecker.signingIdentityCompatible(
                installedHasMultipleSigners = false,
                installedCurrentSigners = setOf("old"),
                candidateHasMultipleSigners = false,
                candidateCurrentSigners = setOf("new"),
                candidateSigningHistory = setOf("old", "new"),
            ),
        )
        assertFalse(
            AppUpdateChecker.signingIdentityCompatible(
                installedHasMultipleSigners = false,
                installedCurrentSigners = setOf("old"),
                candidateHasMultipleSigners = false,
                candidateCurrentSigners = setOf("new"),
                candidateSigningHistory = setOf("new"),
            ),
        )
    }

    @Test
    fun `multi signer update requires the exact current signer set`() {
        assertTrue(
            AppUpdateChecker.signingIdentityCompatible(
                installedHasMultipleSigners = true,
                installedCurrentSigners = setOf("a", "b"),
                candidateHasMultipleSigners = true,
                candidateCurrentSigners = setOf("a", "b"),
                candidateSigningHistory = emptySet(),
            ),
        )
        assertFalse(
            AppUpdateChecker.signingIdentityCompatible(
                installedHasMultipleSigners = true,
                installedCurrentSigners = setOf("a", "b"),
                candidateHasMultipleSigners = true,
                candidateCurrentSigners = setOf("a"),
                candidateSigningHistory = setOf("a", "b"),
            ),
        )
    }

    private fun asset(
        name: String = "Zotify.Music.apk",
        url: String = "https://github.com/OPZENITSUUU/zotify-music/releases/download/v2/$name",
        digestValue: String? = "sha256:$digest",
    ): String {
        val digestPart = digestValue?.let { "\"digest\":\"$it\"," }.orEmpty()
        return """{"name":"$name","browser_download_url":"$url",${digestPart}"state":"uploaded"}"""
    }

    private fun release(vararg assets: String): JsonObject =
        Json.parseToJsonElement("""{"assets":[${assets.joinToString(",")}] }""") as JsonObject
}
