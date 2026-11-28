package com.music.bitchord.playback

/**
 * An in-memory cache whose value is visible only while the active account key
 * matches the key that populated it. The synchronization also makes snapshots
 * safe to read from MediaLibraryService callbacks that may race with refreshes.
 */
internal class AccountScopedCache<T> {
    private var initialized = false
    private var accountKey: String? = null
    private var entry: Pair<Long, T>? = null

    @Synchronized
    fun get(activeAccountKey: String?): Pair<Long, T>? {
        moveTo(activeAccountKey)
        return entry
    }

    @Synchronized
    fun put(activeAccountKey: String?, timestamp: Long, value: T) {
        moveTo(activeAccountKey)
        entry = timestamp to value
    }

    private fun moveTo(activeAccountKey: String?) {
        if (!initialized || accountKey != activeAccountKey) {
            initialized = true
            accountKey = activeAccountKey
            entry = null
        }
    }
}

/** A non-secret identity key for one signed-in account and its selected profile. */
internal fun accountProfileCacheKey(accountId: String?, profileId: String?): String? {
    val account = accountId?.takeIf { it.isNotBlank() } ?: return null
    val profile = profileId?.takeIf { it.isNotBlank() } ?: "default"
    return "${account.length}:$account:${profile.length}:$profile"
}
