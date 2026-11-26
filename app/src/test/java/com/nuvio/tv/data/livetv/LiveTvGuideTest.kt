package com.nuvio.tv.data.livetv

import okhttp3.mockwebserver.MockResponse
import okhttp3.mockwebserver.MockWebServer
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test
import java.time.Instant

class LiveTvGuideTest {
    @Test
    fun readsShortGuideWithEncodedAndPlainTitlesAndProviderTimezone() {
        MockWebServer().use { server ->
            server.enqueue(MockResponse().setBody(
                """{"epg_listings":[
                    {"title":"Kickoff","start":"2026-11-26 19:00:00","end":"2026-11-26 21:00:00"},
                    {"title":"TGl2ZSBmb290YmFsbA==","start_timestamp":"1795687200","stop_timestamp":1795690800},
                    {"title":"Ignore bad time","start_timestamp":"missing"}
                ]}"""
            ))
            val source = checkNotNull(XtreamSource.from(server.url("/").toString(), "viewer", "fixture"))
            val guide = XtreamClient().guide(source, 42, "Australia/Sydney")
            val request = checkNotNull(server.takeRequest().requestUrl)

            assertEquals("get_short_epg", request.queryParameter("action"))
            assertEquals("42", request.queryParameter("stream_id"))
            assertEquals(listOf("Kickoff", "Live football"), guide.map { it.title })
            assertEquals(Instant.parse("2026-11-26T08:00:00Z").toEpochMilli(), guide.first().startsAt)
            assertEquals(Instant.parse("2026-11-26T10:00:00Z").toEpochMilli(), guide.first().endsAt)
            assertEquals("Kickoff", guide.nowAndNext(guide.first().startsAt).first?.title)
            assertEquals("Live football", guide.nowAndNext(guide.first().startsAt).second?.title)
        }
    }

    @Test
    fun sportsQueueIncludesCurrentAndUpcomingProviderProgrammes() {
        val sports = LiveChannel(1, "Arena", "Sports")
        val local = LiveChannel(2, "Local News", "Local")
        val schedule = listOf(
            LiveProgramme("Expired", 0, 10),
            LiveProgramme("Live match", 15, 20),
            LiveProgramme("Next match", 20, 30)
        )
        val events = liveSportsEvents(listOf(sports, local), mapOf(1L to schedule, 2L to schedule), 16)
        assertEquals(listOf("Live match", "Next match"), events.map { it.programme.title })
        assertTrue(events.first().programme.isOnAir(16))
        assertFalse(events.last().programme.isOnAir(16))
        assertNull(schedule.nowAndNext(30).first)
    }
}
