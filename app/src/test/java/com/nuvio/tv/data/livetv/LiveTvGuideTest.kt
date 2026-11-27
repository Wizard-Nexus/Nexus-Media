package com.nuvio.tv.data.livetv

import okhttp3.mockwebserver.MockResponse
import okhttp3.mockwebserver.MockWebServer
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
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
    fun sportsGroupShowsCurrentMajorCompetitions() {
        val games = LiveChannel(1, "ESPN", "Sports")
        val replays = LiveChannel(2, "League channel", "NBA")
        val studio = LiveChannel(3, "League extra", "NRL")
        val niche = LiveChannel(4, "Competition channel", "Sports")
        val empty = LiveChannel(5, "Match channel", "AFL")
        val late = LiveChannel(6, "Racing", "Formula 1")
        val encore = LiveChannel(7, "Court", "Tennis")
        val match = LiveProgramme("Live NBA: Lakers vs Celtics", 15, 20)
        val next = LiveProgramme("Live NBA: Heat vs Knicks", 20, 30)
        val race = LiveProgramme("Grand Prix qualifying", 14, 25)
        val channels = listOf(games, replays, studio, niche, empty, late, encore)
        val schedules = mapOf(
            1L to listOf(LiveProgramme("Live NBA: past match", 0, 15), match, next),
            2L to listOf(LiveProgramme("Live NBA Replay: Raptors vs Nets", 10, 20)),
            3L to listOf(LiveProgramme("Live NRL pre-game countdown", 14, 20)),
            4L to listOf(LiveProgramme("Live darts final", 1, 20)),
            6L to listOf(race),
            7L to listOf(LiveProgramme("Tennis: A vs B", 14, 19, "A replay of yesterday’s final."))
        )

        assertTrue(replays.isSportsGuideCandidate())
        assertTrue(late.isSportsGuideCandidate())
        assertFalse(LiveChannel(99, "Local News", "Local").isSportsGuideCandidate())
        assertEquals(mapOf(1L to listOf(match), 6L to listOf(race)), liveMainSportsListings(channels, schedules, 16))
        assertEquals(mapOf(1L to listOf(next), 6L to listOf(race)), liveMainSportsListings(channels, schedules, 20))
        assertTrue(liveMainSportsListings(channels, schedules, 30).isEmpty())
    }
}
