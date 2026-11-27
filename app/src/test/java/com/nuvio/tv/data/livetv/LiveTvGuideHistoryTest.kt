package com.nuvio.tv.data.livetv

import java.time.LocalDate
import java.time.ZoneId
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

class LiveTvGuideHistoryTest {
    private val zone = ZoneId.of("Australia/Sydney")
    private val today = LocalDate.of(2026, 10, 5)
    private val now = today.atTime(13, 0).atZone(zone).toInstant().toEpochMilli()

    private fun at(daysAgo: Long, hour: Int = 0) =
        today.minusDays(daysAgo).atTime(hour, 0).atZone(zone).toInstant().toEpochMilli()

    private fun on(daysAgo: Long) = LiveProgramme("Day $daysAgo", at(daysAgo, 12), at(daysAgo, 13))

    @Test
    fun historyUsesLocalCalendarDaysAcrossDaylightSaving() {
        assertEquals(at(1), guideHistoryStart(now, 1, zone))
        assertEquals(at(3), guideHistoryStart(now, 3, zone))
        assertEquals(at(5), guideHistoryStart(now, 5, zone))
        assertEquals(at(7), guideHistoryStart(now, 7, zone))
    }

    @Test
    fun jumpsUseOnlyPastAndFutureDatesWithListingsInsideTheSelectedWindows() {
        val schedules = sequenceOf(
            1L to listOf(on(8), on(7), on(3), on(0), on(-1), on(-3), on(-8)),
            2L to listOf(on(5), on(1), on(-5), on(-7))
        )
        for (days in LIVE_TV_GUIDE_DAY_OPTIONS) {
            val offsets = (1..days step 2).toList()
            val expected = (offsets + offsets.map { -it }).map { at(it.toLong(), 12) }
            val actual = guideDateTargets(schedules, now, days, days, zone, null)
            assertEquals(expected, actual.map { it.time })
            assertEquals(at(-days.toLong() - 1), guideAheadEnd(now, days, zone))
        }
        assertTrue(guideDateTargets(sequenceOf(1L to listOf(on(0))), now, 7, 7, zone, 1L).isEmpty())
    }

    @Test
    fun dateTargetsUseTheHighlightedChannelWhenItHasListingsAndAnotherChannelOtherwise() {
        val laterOnPreferredChannel = LiveProgramme("Preferred", at(1, 16), at(1, 17))
        val schedules = sequenceOf(
            2L to listOf(on(2), on(1), on(-1)),
            1L to listOf(laterOnPreferredChannel, on(0))
        )
        assertEquals(
            listOf(GuideDateTarget(1L, at(1, 16)), GuideDateTarget(2L, at(2, 12)), GuideDateTarget(2L, at(-1, 12))),
            guideDateTargets(schedules, now, 3, 1, zone, 1L)
        )
    }

    @Test
    fun keepsEveryCachedDayInTheWindowWhenTheProviderDropsEarlierListings() {
        val cached = (8L downTo 0L).map(::on)
        val updated = on(0).copy(title = "Corrected current listing")
        val recent = mergeGuideHistory(cached, listOf(updated), at(3))
        assertEquals(listOf(on(3), on(2), on(1), updated), recent)
        val sevenDays = mergeGuideHistory(cached, listOf(updated), at(7))
        assertEquals((7L downTo 1L).map(::on) + updated, sevenDays)
        assertEquals(recent, mergeGuideHistory(recent, emptyList(), at(3)))
    }

    @Test
    fun triesReplayingCachedProgrammesBeyondTheAdvertisedWindowForUpToThreeDays() {
        val channel = LiveChannel(31, "Sports", "Arena", archiveDays = 2)
        val start = now - 71 * 3_600_000L
        val programme = LiveProgramme("Earlier game", start, start + 3_600_000L)
        val refreshed = mergeGuideHistory(listOf(programme), listOf(on(0)), guideHistoryStart(now, 3, zone))

        assertEquals(programme, refreshed.first())
        assertTrue(refreshed.first().canReplay(channel, now))
        assertTrue(programme.canReplay(channel, start + 3 * 86_400_000L))
        assertFalse(programme.canReplay(channel, start + 3 * 86_400_000L + 1))
    }

    @Test
    fun replayFallbackRequiresArchiveSupportAndKeepsLongerAdvertisedWindows() {
        val channel = LiveChannel(31, "Sports", "Arena")
        val programme = on(2)
        assertFalse(programme.canReplay(channel, now))
        assertTrue(programme.canReplay(channel.copy(archiveEnabled = true), now))
        assertFalse(programme.copy(hasArchive = false).canReplay(channel.copy(archiveDays = 2), now))
        assertTrue(on(6).canReplay(channel.copy(archiveDays = 7), now))
        assertTrue(on(6).copy(hasArchive = true).canReplay(channel, now))
    }

    @Test
    fun searchesTeamNamesInRecentCachedProgrammes() {
        val sports = LiveChannel(31, "Sport One", "Sport")
        val hidden = LiveChannel(32, "Hidden Sport", "Hidden")
        val bruins = on(2).copy(title = "Boston Bruins at Anaheim Ducks")
        val stars = on(3).copy(title = "NHL Hockey", description = "The Anaheim Ducks play the Dallas Stars")
        val older = on(6).copy(title = "Anaheim Ducks at Los Angeles Kings")
        val tomorrow = on(-1).copy(title = "Seattle Kraken at Anaheim Ducks")
        val schedules = sequenceOf(
            sports.id to listOf(older, stars, bruins, tomorrow),
            hidden.id to listOf(bruins)
        )

        val actual = findGuideProgrammes("Anaheim Ducks", mapOf(sports.id to sports), schedules, at(3), now)
        assertEquals(listOf(bruins, stars, tomorrow), actual.map { it.programme })
        assertEquals(listOf(sports, sports, sports), actual.map { it.channel })
        val selectedDay = findGuideProgrammes(
            "Anaheim Ducks", mapOf(sports.id to sports), schedules, at(3), now,
            searchDay = at(2) until at(1)
        )
        assertEquals(listOf(bruins), selectedDay.map { it.programme })
    }

    @Test
    fun currentProgrammesCanUseAChannelArchiveEvenBeforeTheirListingIsArchived() {
        val channel = LiveChannel(42, "Sports", "Arena", archiveDays = 7)
        val programme = LiveProgramme("Live game", now - 600_000L, now + 600_000L, hasArchive = false)
        assertTrue(programme.canRequestStartOver(channel, now))
        assertTrue(programme.canRequestStartOver(channel.copy(archiveDays = 0, archiveEnabled = true), now))
        assertFalse(programme.canRequestStartOver(channel.copy(archiveDays = 0), now))
        assertTrue(programme.copy(hasArchive = true).canRequestStartOver(channel.copy(archiveDays = 0), now))
        assertFalse(programme.canRequestStartOver(channel, programme.startsAt))
        assertFalse(programme.canRequestStartOver(channel, programme.endsAt))
        assertFalse(programme.copy(startsAt = at(8)).canRequestStartOver(channel, now))
        assertFalse(programme.canReplay(channel, programme.endsAt))
    }

    @Test
    fun guideSearchAppliesTheForwardLimitEvenOnAllDays() {
        val channel = LiveChannel(31, "Sports", "Arena")
        val events = (1L..8L).map { day -> on(-day).copy(title = "Ducks day $day") }
        val schedules = sequenceOf(channel.id to events)
        for (days in LIVE_TV_GUIDE_DAY_OPTIONS) {
            val results = findGuideProgrammes(
                "Ducks", mapOf(channel.id to channel), schedules, at(1), now,
                aheadEnd = guideAheadEnd(now, days, zone)
            )
            assertEquals(events.take(days), results.map { it.programme })
        }
    }

    @Test
    fun overnightListingStartsTheJumpAtTheBeginningOfTheAllowedDay() {
        val overnight = LiveProgramme("Overnight", at(2, 23), at(1, 2))
        val endsAtMidnight = LiveProgramme("Earlier", at(2, 20), at(1))
        val schedules = sequenceOf(1L to listOf(endsAtMidnight, overnight))
        assertEquals(listOf(GuideDateTarget(1L, at(1))), guideDateTargets(schedules, now, 1, 1, zone, null))
    }
}
