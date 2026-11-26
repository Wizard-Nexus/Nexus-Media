package com.nuvio.tv.data.livetv

import kotlinx.serialization.json.JsonArray
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.JsonPrimitive
import kotlinx.serialization.json.contentOrNull
import java.nio.ByteBuffer
import java.nio.charset.StandardCharsets
import java.time.LocalDateTime
import java.time.ZoneId
import java.time.format.DateTimeFormatter
import java.util.Base64

internal data class LiveProgramme(
    val title: String,
    val startsAt: Long,
    val endsAt: Long
) {
    fun isOnAir(now: Long): Boolean = now in startsAt until endsAt
}

internal fun List<LiveProgramme>.nowAndNext(now: Long): Pair<LiveProgramme?, LiveProgramme?> =
    firstOrNull { it.isOnAir(now) } to firstOrNull { it.startsAt > now }

internal data class LiveSportsEvent(val channel: LiveChannel, val programme: LiveProgramme)

internal fun liveSportsEvents(
    channels: List<LiveChannel>,
    schedules: Map<Long, List<LiveProgramme>>,
    now: Long
): List<LiveSportsEvent> = channels.asSequence()
    .filter { it.isSports }
    .flatMap { channel ->
        schedules[channel.id].orEmpty().asSequence()
            .filter { it.endsAt > now }
            .map { LiveSportsEvent(channel, it) }
    }
    .sortedWith(compareBy({ it.programme.startsAt > now }, { it.programme.startsAt }))
    .toList()

internal fun parseXtreamGuide(response: JsonObject, providerZone: ZoneId?): List<LiveProgramme> {
    val rows = response["epg_listings"] as? JsonArray ?: return emptyList()
    return rows.filterIsInstance<JsonObject>().mapNotNull { entry ->
        val title = entry.value("title")?.let(::guideText) ?: return@mapNotNull null
        val start = entry.time("start_timestamp", "start", providerZone) ?: return@mapNotNull null
        val end = entry.time("stop_timestamp", "end", providerZone) ?: return@mapNotNull null
        if (end <= start) return@mapNotNull null
        LiveProgramme(title, start, end)
    }.distinct().sortedBy { it.startsAt }
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
