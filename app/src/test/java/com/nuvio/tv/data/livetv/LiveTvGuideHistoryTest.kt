package com.nuvio.tv.data.livetv

import java.time.LocalDate
import java.time.ZoneId
import org.junit.Assert.assertEquals
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
    fun jumpsUseOnlyPastDatesWithListingsWithinTheSelectedWindow() {
        val schedules = listOf(listOf(on(8), on(7), on(3), on(0)), listOf(on(5), on(1)))
        assertEquals(listOf(at(1, 12)), earlierGuideDateTargets(schedules, now, 1, zone))
        assertEquals(listOf(at(1, 12), at(3, 12)), earlierGuideDateTargets(schedules, now, 3, zone))
        assertEquals(listOf(at(1, 12), at(3, 12), at(5, 12)), earlierGuideDateTargets(schedules, now, 5, zone))
        assertEquals(listOf(at(1, 12), at(3, 12), at(5, 12), at(7, 12)), earlierGuideDateTargets(schedules, now, 7, zone))
        assertEquals(emptyList<Long>(), earlierGuideDateTargets(listOf(listOf(on(0))), now, 7, zone))
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
    }

    @Test
    fun overnightListingStartsTheJumpAtTheBeginningOfTheAllowedDay() {
        val overnight = LiveProgramme("Overnight", at(2, 23), at(1, 2))
        val endsAtMidnight = LiveProgramme("Earlier", at(2, 20), at(1))
        val schedules = listOf(listOf(endsAtMidnight, overnight))
        assertEquals(listOf(at(1)), earlierGuideDateTargets(schedules, now, 1, zone))
    }
}
