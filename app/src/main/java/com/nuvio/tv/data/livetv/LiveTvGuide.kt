package com.nuvio.tv.data.livetv

import kotlinx.serialization.Serializable
import kotlinx.serialization.json.JsonArray
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.JsonPrimitive
import kotlinx.serialization.json.contentOrNull
import java.nio.ByteBuffer
import java.nio.charset.StandardCharsets
import java.time.Instant
import java.time.LocalDateTime
import java.time.ZoneId
import java.time.format.DateTimeFormatter
import java.util.Base64
import java.util.Locale
import java.time.temporal.ChronoUnit

internal val LIVE_TV_GUIDE_HISTORY_DAYS = listOf(1, 3, 5, 7)

internal fun guideHistoryStart(now: Long, days: Int, zone: ZoneId): Long =
    Instant.ofEpochMilli(now).atZone(zone).toLocalDate().minusDays(days.toLong())
        .atStartOfDay(zone).toInstant().toEpochMilli()

internal fun earlierGuideDateTargets(
    schedules: Collection<List<LiveProgramme>>,
    now: Long,
    days: Int,
    zone: ZoneId
): List<Long> = (1..days).mapNotNull { day ->
    val start = guideHistoryStart(now, day, zone)
    val end = guideHistoryStart(now, day - 1, zone)
    schedules.mapNotNull { schedule ->
        schedule.firstOrNull { it.endsAt > start && it.startsAt < end }?.startsAt
    }.minOrNull()?.coerceAtLeast(start)
}

internal fun mergeGuideHistory(
    cached: List<LiveProgramme>,
    refreshed: List<LiveProgramme>,
    cutoff: Long
): List<LiveProgramme> {
    val fresh = refreshed.filter { it.endsAt > cutoff }
    var freshIndex = 0
    val retained = cached.filter { listing ->
        if (listing.endsAt <= cutoff) return@filter false
        while (freshIndex < fresh.size && fresh[freshIndex].endsAt <= listing.startsAt) {
            freshIndex++
        }
        freshIndex == fresh.size || listing.endsAt <= fresh[freshIndex].startsAt
    }
    return (retained + fresh).sortedBy { it.startsAt }
}

@Serializable
internal data class LiveProgramme(
    val title: String,
    val startsAt: Long,
    val endsAt: Long,
    val description: String? = null,
    val hasArchive: Boolean? = null,
    val archiveStart: String? = null
) {
    fun isOnAir(now: Long): Boolean = now in startsAt until endsAt
}

internal fun LiveProgramme.canReplay(channel: LiveChannel, now: Long): Boolean {
    if (endsAt > now || hasArchive == false) return false
    if (channel.archiveDays <= 0) return hasArchive == true
    return now - startsAt <= channel.archiveDays.toLong() * 86_400_000L
}

internal fun List<LiveProgramme>.nowAndNext(now: Long): Pair<LiveProgramme?, LiveProgramme?> =
    firstOrNull { it.isOnAir(now) } to firstOrNull { it.startsAt > now }

internal data class LiveSportsEvent(val channel: LiveChannel, val programme: LiveProgramme)

internal fun LiveChannel.isSportsGuideCandidate(): Boolean =
    isSports || MAIN_SPORTS.containsMatchIn("$name ${group.orEmpty()}") || SPORTS_NETWORK.containsMatchIn(name)

internal fun liveMainSportsListings(
    channels: List<LiveChannel>,
    schedules: Map<Long, List<LiveProgramme>>,
    now: Long
): Map<Long, List<LiveProgramme>> = buildMap {
    for (channel in channels) {
        val programme = schedules[channel.id].orEmpty()
            .filter { it.isOnAir(now) }
            .maxByOrNull { it.startsAt } ?: continue
        val description = programme.description.orEmpty()
        val listing = "${programme.title} $description"
        if (!MAIN_SPORTS.containsMatchIn("$listing ${channel.name} ${channel.group.orEmpty()}")) continue
        if (SPORTS_SHOW.containsMatchIn(programme.title) || REPLAY_DESCRIPTION.containsMatchIn(description)) continue
        if (COMPETITION.containsMatchIn(listing)) put(channel.id, listOf(programme))
    }
}

private val MAIN_SPORTS = Regex(
    """\b(?:basketball|nba|wnba|nbl|euroleague|football|soccer|epl|premier league|champions league|fifa|"""+
        """nfl|afl|wafl|aflw|rugby|nrl|nrlw|cricket|bbl|wbbl|ipl|tennis|atp|wta|"""+
        """baseball|mlb|hockey|nhl|golf|pga|lpga|formula[ -]?1|f1|nascar|indycar|motogp|"""+
        """supercars|motorsports?|boxing|ufc|mma)\b""",
    RegexOption.IGNORE_CASE
)
private val SPORTS_NETWORK = Regex("""\b(?:espn\d*|tnt sports|skysports|supersport)\b""", RegexOption.IGNORE_CASE)
private val SPORTS_SHOW = Regex(
    """\b(?:replays?|reruns?|highlights?|recap|classics?|documentary|preview|pre[ -]?game|post[ -]?game|"""+
        """countdown|review|roundup|analysis|news|sportscent(?:er|re)|talk|daily|magazine|"""+
        """first take|inside the nba|nba today|best of)\b""",
    RegexOption.IGNORE_CASE
)
private val REPLAY_DESCRIPTION = Regex(
    """\b(?:replay of|encore of|a repeat of|highlights from|previously recorded|pre[ -]?recorded)\b""",
    RegexOption.IGNORE_CASE
)
private val COMPETITION = Regex(
    """\b(?:live|vs\.?|versus|match(?:es)?|game \d+|round \d+|day \d+|grand prix|race|qualifying|finals?)\b|\s(?:v\.?|at|@)\s""",
    RegexOption.IGNORE_CASE
)

internal fun parseXtreamGuide(response: JsonObject, providerZone: ZoneId?): List<LiveProgramme> {
    val rows = response["epg_listings"] as? JsonArray ?: return emptyList()
    return rows.filterIsInstance<JsonObject>().mapNotNull { entry ->
        val title = entry.value("title")?.let(::guideText) ?: return@mapNotNull null
        val start = entry.time("start_timestamp", "start", providerZone) ?: return@mapNotNull null
        val end = entry.time("stop_timestamp", "end", providerZone) ?: return@mapNotNull null
        if (end <= start) return@mapNotNull null
        val description = entry.value("description")?.let(::guideText)
        val archived = when (entry.value("has_archive")?.lowercase(Locale.ROOT)) {
            "1", "true" -> true
            "0", "false" -> false
            else -> null
        }
        val archiveStart = entry.value("start")?.let { local ->
            runCatching { REPLAY_TIME.format(LocalDateTime.parse(local, XTREAM_TIME).truncatedTo(ChronoUnit.MINUTES)) }.getOrNull()
        }
        LiveProgramme(title, start, end, description, archived, archiveStart)
    }.distinctBy { Triple(it.startsAt, it.endsAt, it.title) }.sortedBy { it.startsAt }
}

private fun JsonObject.value(key: String): String? =
    (get(key) as? JsonPrimitive)?.contentOrNull?.trim()?.takeIf { it.isNotEmpty() }

private fun JsonObject.time(timestamp: String, localTime: String, providerZone: ZoneId?): Long? {
    val epoch = value(timestamp)?.toLongOrNull()?.takeIf { it > 0 }
    if (epoch != null) return if (epoch >= 100_000_000_000L) epoch else epoch * 1000
    val local = value(localTime) ?: return null
    return runCatching {
        LocalDateTime.parse(local, XTREAM_TIME)
            .atZone(providerZone ?: ZoneId.systemDefault()).toInstant().toEpochMilli()
    }.getOrNull()
}

private fun guideText(title: String): String {
    val decoded = runCatching {
        val bytes = Base64.getDecoder().decode(title)
        StandardCharsets.UTF_8.newDecoder().decode(ByteBuffer.wrap(bytes)).toString().trim()
    }.getOrNull()
    return decoded?.takeIf { it.isNotBlank() && it.none(Char::isISOControl) } ?: title
}

private val XTREAM_TIME = DateTimeFormatter.ofPattern("yyyy-MM-dd HH:mm:ss")

private val REPLAY_TIME = DateTimeFormatter.ofPattern("uuuu-MM-dd:HH-mm", Locale.ROOT)
