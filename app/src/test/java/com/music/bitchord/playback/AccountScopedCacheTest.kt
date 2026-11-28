package com.music.bitchord.playback

import org.junit.Assert.assertEquals
import org.junit.Assert.assertNotEquals
import org.junit.Assert.assertNull
import org.junit.Test

class AccountScopedCacheTest {
    @Test
    fun switchingAccountsEvictsThePreviousAccountValue() {
        val cache = AccountScopedCache<List<String>>()
        cache.put("account-a-cookie", 10L, listOf("private A history"))

        assertEquals(listOf("private A history"), cache.get("account-a-cookie")?.second)
        assertNull(cache.get("account-b-cookie"))
        assertNull(cache.get("account-a-cookie"))
    }

    @Test
    fun loggingOutEvictsTheAuthenticatedValue() {
        val cache = AccountScopedCache<String>()
        cache.put("account-cookie", 10L, "account data")

        assertNull(cache.get(null))
        assertNull(cache.get("account-cookie"))
    }

    @Test
    fun anonymousValuesRemainScopedToTheAnonymousState() {
        val cache = AccountScopedCache<String>()
        cache.put(null, 10L, "anonymous data")

        assertEquals("anonymous data", cache.get(null)?.second)
        assertNull(cache.get("account-cookie"))
        assertNull(cache.get(null))
    }

    @Test
    fun selectedAccountAndProfileProduceDifferentCacheKeys() {
        val accountAProfileOne = accountProfileCacheKey("account-a", "profile-one")
        val accountAProfileTwo = accountProfileCacheKey("account-a", "profile-two")
        val accountADefaultProfile = accountProfileCacheKey("account-a", null)
        val accountBProfileOne = accountProfileCacheKey("account-b", "profile-one")

        assertNotEquals(accountAProfileOne, accountAProfileTwo)
        assertNotEquals(accountAProfileOne, accountADefaultProfile)
        assertNotEquals(accountAProfileOne, accountBProfileOne)
        assertNull(accountProfileCacheKey(null, "profile-one"))
    }
}
