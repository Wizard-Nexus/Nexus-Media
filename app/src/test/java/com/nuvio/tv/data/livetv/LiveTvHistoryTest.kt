package com.nuvio.tv.data.livetv

import org.junit.Assert.assertEquals
import org.junit.Assert.assertNotEquals
import org.junit.Test

class LiveTvHistoryTest {
    @Test
    fun recentChannelsDeduplicateAndKeepThePreviousChannel() {
        val history = LiveTvHistory().toggleFavourite(40).watched(3).watched(12).watched(3)
        assertEquals(listOf(3L, 12L), history.recent)
        assertEquals(12L, history.previousChannel(3))
        assertEquals(emptyList<Long>(), history.toggleFavourite(40).favourites)
        val longHistory = (1L..42L).fold(history) { state, id -> state.watched(id) }
        assertEquals((42L downTo 13L).toList(), longHistory.recent)
    }

    @Test
    fun providerAndAccountScopeTheCacheAcrossPasswordChanges() {
        fun key(host: String, user: String, password: String) =
            checkNotNull(XtreamSource.from(host, user, password)).cacheKey

        val original = key("https://guide.example.test", "viewer", "fixture")
        assertEquals(original, key("https://guide.example.test/", "viewer", "rotated"))
        assertNotEquals(original, key("https://guide.example.test", "viewer2", "fixture"))
        assertNotEquals(original, key("https://other.example.test", "viewer", "fixture"))
    }
}
