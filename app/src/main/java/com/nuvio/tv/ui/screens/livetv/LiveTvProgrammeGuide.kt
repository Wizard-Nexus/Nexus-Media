package com.nuvio.tv.ui.screens.livetv

import androidx.compose.animation.animateColorAsState
import androidx.compose.foundation.BorderStroke
import androidx.compose.foundation.Canvas
import androidx.compose.foundation.background
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.PaddingValues
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxHeight
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.offset
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.draw.clipToBounds
import androidx.compose.ui.focus.FocusRequester
import androidx.compose.ui.focus.focusProperties
import androidx.compose.ui.focus.focusRequester
import androidx.compose.ui.focus.onFocusChanged
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.graphics.Brush
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.input.key.Key
import androidx.compose.ui.input.key.KeyEventType
import androidx.compose.ui.input.key.key
import androidx.compose.ui.input.key.onPreviewKeyEvent
import androidx.compose.ui.input.key.type
import androidx.compose.ui.layout.ContentScale
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.semantics.semantics
import androidx.compose.ui.semantics.contentDescription
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.Dp
import androidx.compose.ui.unit.DpSize
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import androidx.tv.material3.Border
import androidx.tv.material3.Button
import androidx.tv.material3.ButtonDefaults
import androidx.tv.material3.ClickableSurfaceDefaults
import androidx.tv.material3.MaterialTheme
import androidx.tv.material3.Surface
import androidx.tv.material3.Text
import coil3.compose.AsyncImage
import com.nuvio.tv.R
import com.nuvio.tv.data.livetv.LiveChannel
import com.nuvio.tv.data.livetv.LiveProgramme
import com.nuvio.tv.data.livetv.canReplay
import java.time.Instant
import java.time.ZoneId
import java.time.format.DateTimeFormatter

internal const val GUIDE_STEP_MS = 30 * 60_000L

private val GuideBlack = Color(0xFF090B11)
internal val GuideRail = Color(0xFF0D1018)
private val GuideCard = Color(0xFF131B29)
private val GuideOnAir = Color(0xFF192E4F)
private val GuideSelected = Color(0xFF3659AD)
private val GuideFocus = Color(0xFF567FE7)
internal val GuideAccent = Color(0xFF79ABFF)
private val GuideLine = Color(0xFF2B80EA)
internal val GuideMuted = Color(0xFFACB7CB)
private val GuideText = Color(0xFFF1F5FF)
private val GuideShape = RoundedCornerShape(6.dp)

internal fun guideTimeFloor(time: Long): Long {
    val local = Instant.ofEpochMilli(time).atZone(ZoneId.systemDefault())
    return local.withMinute(local.minute / 30 * 30).withSecond(0).withNano(0).toInstant().toEpochMilli()
}

internal data class GuideWindow(val start: Long, val duration: Long, val width: Dp) {
    val end: Long get() = start + duration

    fun x(time: Long): Dp = width * ((time - start).toDouble() / duration).toFloat()
}

internal data class GuideBlock(val start: Long, val end: Long, val programme: LiveProgramme?)

internal fun guideBlocks(programmes: List<LiveProgramme>, start: Long, end: Long): List<GuideBlock> = buildList {
    var cursor = start
    for (programme in programmes) {
        if (programme.startsAt >= end) break
        val first = maxOf(cursor, programme.startsAt)
        val last = minOf(end, programme.endsAt)
        if (last <= first) continue
        if (first > cursor) add(GuideBlock(cursor, first, null))
        add(GuideBlock(first, last, programme))
        cursor = last
    }
    if (cursor < end) add(GuideBlock(cursor, end, null))
}

internal fun stepGuideTime(programmes: List<LiveProgramme>, time: Long, forward: Boolean): Long? {
    if (forward) return programmes.firstOrNull { it.startsAt > time }?.startsAt
    val start = programmes.firstOrNull { it.isOnAir(time) }?.startsAt ?: time
    return programmes.lastOrNull { it.startsAt < start }?.startsAt
}

@Composable
internal fun LiveTvGuideHeader(
    channel: LiveChannel?,
    programme: LiveProgramme?,
    date: Long,
    now: Long,
    status: String?,
    canGoEarlier: Boolean,
    canGoLater: Boolean,
    categoriesVisible: Boolean,
    onSearch: () -> Unit,
    onNow: () -> Unit,
    onEarlier: () -> Unit,
    onLater: () -> Unit,
    onCategories: () -> Unit,
    onWatch: (() -> Unit)?,
    previewSize: DpSize?,
    playingChannel: LiveChannel?,
    onDismiss: (() -> Unit)?,
    modifier: Modifier = Modifier,
    extra: @Composable () -> Unit = {}
) {
    Row(
        modifier.fillMaxWidth().background(Brush.horizontalGradient(listOf(GuideBlack, Color(0xFF10182B))))
            .padding(start = GuideHeaderStart, top = GuideHeaderTop, end = GuideHeaderEnd, bottom = 14.dp),
        horizontalArrangement = Arrangement.spacedBy(GuideHeaderGap),
        verticalAlignment = Alignment.CenterVertically
    ) {
        Column(
            Modifier.weight(GUIDE_PREVIEW_WEIGHT).then(if (previewSize != null) Modifier.align(Alignment.Top) else Modifier),
            verticalArrangement = Arrangement.spacedBy(6.dp)
        ) {
            if (previewSize != null) {
                Spacer(Modifier.size(previewSize).background(Color.Black))
                playingChannel?.let {
                    Text(
                        stringResource(R.string.live_tv_current_channel, it.name),
                        color = GuideAccent,
                        style = MaterialTheme.typography.labelMedium,
                        maxLines = 1,
                        overflow = TextOverflow.Ellipsis
                    )
                }
            } else {
                Text(
                    stringResource(R.string.nav_live_tv),
                    color = GuideAccent,
                    fontSize = 13.sp,
                    fontWeight = FontWeight.Bold
                )
                if (channel != null) {
                    ChannelLogo(channel, Modifier.size(90.dp, 50.dp))
                    if (onWatch != null) GuideButton(stringResource(R.string.live_tv_watch_channel), onWatch)
                } else {
                    Text(stringResource(R.string.live_tv_guide), color = GuideText, style = MaterialTheme.typography.headlineMedium)
                }
            }
        }
        Column(
            Modifier.weight(1f - GUIDE_PREVIEW_WEIGHT).fillMaxHeight(),
            verticalArrangement = Arrangement.spacedBy(6.dp)
        ) {
            Row(
                Modifier.fillMaxWidth(),
                horizontalArrangement = Arrangement.spacedBy(5.dp, Alignment.End),
                verticalAlignment = Alignment.CenterVertically
            ) {
                if (!categoriesVisible) GuideButton(stringResource(R.string.live_tv_guide_categories), onCategories)
                GuideButton("‹", onEarlier, enabled = canGoEarlier, description = stringResource(R.string.live_tv_guide_earlier))
                GuideButton(stringResource(R.string.live_tv_now), onNow)
                GuideButton("›", onLater, enabled = canGoLater, description = stringResource(R.string.live_tv_guide_later))
                GuideButton(stringResource(R.string.live_tv_search), onSearch)
                if (onDismiss != null) GuideButton(stringResource(R.string.live_tv_preview_fullscreen), onDismiss)
            }
            Spacer(Modifier.height(4.dp))
            val dateLabel = remember(date) {
                Instant.ofEpochMilli(date).atZone(ZoneId.systemDefault()).format(DateTimeFormatter.ofPattern("EEE, d MMM"))
            }
            Text(
                if (channel != null) "${channel.name} · $dateLabel" else dateLabel,
                color = GuideAccent,
                style = MaterialTheme.typography.labelMedium,
                maxLines = 1,
                overflow = TextOverflow.Ellipsis
            )
            Text(
                programme?.title ?: stringResource(
                    if (channel == null) R.string.live_tv_guide_choose else R.string.live_tv_guide_unavailable
                ),
                color = GuideText,
                style = MaterialTheme.typography.headlineSmall,
                fontWeight = FontWeight.Bold,
                maxLines = 2,
                overflow = TextOverflow.Ellipsis
            )
            if (programme != null) {
                val range = stringResource(
                    R.string.live_tv_guide_time_range,
                    formatProgrammeTime(programme.startsAt),
                    formatProgrammeTime(programme.endsAt)
                )
                Text(
                    if (programme.isOnAir(now)) stringResource(R.string.live_tv_guide_on_now, range) else range,
                    color = GuideMuted,
                    style = MaterialTheme.typography.labelMedium
                )
                programme.description?.let { description ->
                    Text(description, color = GuideText, style = MaterialTheme.typography.bodySmall,
                        maxLines = 3, overflow = TextOverflow.Ellipsis)
                }
            }
            status?.let { Text(it, color = GuideMuted, style = MaterialTheme.typography.labelSmall, maxLines = 1) }
            extra()
        }
    }
}

@Composable
internal fun GuideButton(
    label: String,
    onClick: () -> Unit,
    modifier: Modifier = Modifier,
    enabled: Boolean = true,
    description: String? = null,
    startAligned: Boolean = false
) {
    Button(
        onClick = onClick,
        enabled = enabled,
        modifier = modifier.height(31.dp).then(
            if (description == null) Modifier else Modifier.semantics { contentDescription = description }
        ),
        contentPadding = PaddingValues(horizontal = 9.dp),
        shape = ButtonDefaults.shape(GuideShape),
        scale = ButtonDefaults.scale(focusedScale = 1f),
        colors = ButtonDefaults.colors(
            containerColor = GuideCard,
            contentColor = GuideText,
            focusedContainerColor = GuideFocus,
            focusedContentColor = Color.White
        )
    ) {
        Text(
            label, modifier = if (startAligned) Modifier.fillMaxWidth() else Modifier,
            maxLines = 1, overflow = TextOverflow.Ellipsis, style = MaterialTheme.typography.labelMedium
        )
    }
}

@Composable
internal fun GuideRuler(window: GuideWindow, modifier: Modifier = Modifier) {
    Box(modifier.width(window.width).height(22.dp).clipToBounds()) {
        val ticks = remember(window.start, window.duration) {
            generateSequence(window.start) { it + GUIDE_STEP_MS }.takeWhile { it < window.end }.toList()
        }
        ticks.forEach { tick ->
            Row(
                Modifier.offset(x = window.x(tick)).fillMaxHeight(),
                verticalAlignment = Alignment.CenterVertically
            ) {
                Spacer(Modifier.width(1.dp).fillMaxHeight().background(GuideMuted.copy(alpha = .24f)))
                Text(
                    formatProgrammeTime(tick),
                    modifier = Modifier.padding(start = 5.dp),
                    color = GuideText,
                    fontWeight = FontWeight.SemiBold,
                    fontSize = 11.sp,
                    maxLines = 1
                )
            }
        }
    }
}

@Composable
internal fun GuideNowLine(window: GuideWindow, now: Long, modifier: Modifier = Modifier) {
    if (now !in window.start until window.end) return
    Canvas(modifier.width(window.width).fillMaxHeight()) {
        val x = window.x(now).toPx()
        drawLine(GuideLine, Offset(x, 0f), Offset(x, size.height), strokeWidth = 2.dp.toPx())
    }
}

@Composable
internal fun LiveTvGuideRow(
    channel: LiveChannel,
    programmes: List<LiveProgramme>,
    window: GuideWindow,
    selectedTime: Long,
    now: Long,
    channelWidth: Dp,
    favourite: Boolean,
    categoryRailVisible: Boolean,
    onChannelFocus: () -> Unit,
    onProgrammeFocus: () -> Unit,
    onBrowse: (Long) -> Unit,
    onOpenCategories: () -> Unit,
    onSelect: (LiveProgramme?) -> Unit,
    onProgrammeOptions: (LiveProgramme) -> Unit,
    onFavourite: () -> Unit,
    modifier: Modifier = Modifier
) {
    val identity = remember { FocusRequester() }
    val track = remember { FocusRequester() }
    var trackFocused by remember { mutableStateOf(false) }
    val blocks = remember(programmes, window.start, window.end) { guideBlocks(programmes, window.start, window.end) }
    val focusedBlock = blocks.firstOrNull { selectedTime in it.start until it.end }
    Row(Modifier.fillMaxWidth().height(37.dp), verticalAlignment = Alignment.CenterVertically) {
        Surface(
            onClick = { onSelect(null) },
            onLongClick = onFavourite,
            modifier = modifier.width(channelWidth).fillMaxHeight().padding(horizontal = 4.dp, vertical = 2.dp)
                .focusRequester(identity)
                .onFocusChanged { if (it.isFocused) onChannelFocus() }
                .onPreviewKeyEvent { event ->
                    if (event.type != KeyEventType.KeyDown) return@onPreviewKeyEvent false
                    when {
                        event.key == Key.DirectionRight -> track.requestFocus()
                        event.key == Key.DirectionLeft && !categoryRailVisible -> {
                            onOpenCategories()
                            true
                        }
                        else -> false
                    }
                },
            colors = ClickableSurfaceDefaults.colors(
                containerColor = if (trackFocused) GuideOnAir else GuideRail,
                focusedContainerColor = GuideOnAir,
                contentColor = GuideText,
                focusedContentColor = Color.White
            ),
            border = ClickableSurfaceDefaults.border(
                focusedBorder = Border(border = BorderStroke(1.dp, GuideAccent), shape = GuideShape)
            ),
            shape = ClickableSurfaceDefaults.shape(GuideShape),
            scale = ClickableSurfaceDefaults.scale(focusedScale = 1f)
        ) {
            Row(
                Modifier.fillMaxSize().padding(horizontal = 8.dp),
                horizontalArrangement = Arrangement.spacedBy(11.dp),
                verticalAlignment = Alignment.CenterVertically
            ) {
                ChannelLogo(channel, Modifier.size(35.dp, 24.dp))
                Text(
                    channel.name,
                    modifier = Modifier.weight(1f),
                    fontWeight = FontWeight.Bold,
                    fontSize = 12.sp,
                    maxLines = 1,
                    overflow = TextOverflow.Ellipsis
                )
                if (favourite) Text("★", color = GuideAccent, fontSize = 11.sp)
            }
        }
        Surface(
            onClick = { onSelect(focusedBlock?.programme) },
            onLongClick = { focusedBlock?.programme?.let(onProgrammeOptions) ?: onFavourite() },
            modifier = Modifier.width(window.width).fillMaxHeight().padding(vertical = 2.dp)
                .focusRequester(track)
                .onFocusChanged {
                    trackFocused = it.isFocused
                    if (it.isFocused) onProgrammeFocus()
                }
                .onPreviewKeyEvent { event ->
                    if (event.type != KeyEventType.KeyDown) return@onPreviewKeyEvent false
                    val forward = when (event.key) {
                        Key.DirectionRight -> true
                        Key.DirectionLeft -> false
                        else -> return@onPreviewKeyEvent false
                    }
                    val next = stepGuideTime(programmes, selectedTime, forward)
                    if (next != null) onBrowse(next) else if (!forward) identity.requestFocus()
                    true
                },
            colors = ClickableSurfaceDefaults.colors(
                containerColor = GuideRail,
                focusedContainerColor = GuideRail,
                contentColor = GuideText,
                focusedContentColor = GuideText
            ),
            scale = ClickableSurfaceDefaults.scale(focusedScale = 1f),
            shape = ClickableSurfaceDefaults.shape(GuideShape)
        ) {
            Box(Modifier.fillMaxSize().clipToBounds()) {
                blocks.forEach { block ->
                    val width = (window.x(block.end) - window.x(block.start) - 2.dp).coerceAtLeast(1.dp)
                    val active = trackFocused && block == focusedBlock
                    val colour by animateColorAsState(
                        when {
                            active -> GuideSelected
                            block.programme?.isOnAir(now) == true -> GuideOnAir
                            else -> GuideCard
                        },
                        label = "programme"
                    )
                    Box(
                        Modifier.offset(x = window.x(block.start)).width(width).fillMaxHeight()
                            .clip(GuideShape).background(colour)
                            .focusProperties { canFocus = false }
                            .clickable { onSelect(block.programme) }
                            .padding(horizontal = if (width < 28.dp) 2.dp else 7.dp),
                        contentAlignment = Alignment.CenterStart
                    ) {
                        if (width >= 24.dp) {
                            val programme = block.programme
                            val title = programme?.title ?: stringResource(R.string.live_tv_guide_unavailable)
                            val replay = width >= 48.dp && programme?.canReplay(channel, now) == true
                            Text(
                                if (replay) stringResource(R.string.live_tv_replay_marker, title) else title,
                                color = if (active || block.programme != null) GuideText else GuideMuted,
                                fontSize = 12.sp,
                                maxLines = 1,
                                overflow = TextOverflow.Ellipsis
                            )
                        }
                    }
                }
            }
        }
    }
}

@Composable
private fun ChannelLogo(channel: LiveChannel, modifier: Modifier) {
    var ready by remember(channel.logoUrl) { mutableStateOf(false) }
    Box(modifier, contentAlignment = Alignment.Center) {
        if (!ready) {
            val initials = remember(channel.name) {
                channel.name.split(' ', ':', '|', '-').filter { it.any(Char::isLetterOrDigit) }
                    .take(2).mapNotNull { it.firstOrNull(Char::isLetterOrDigit) }.joinToString("").uppercase()
            }
            Box(
                Modifier.fillMaxSize().clip(GuideShape).background(GuideCard),
                contentAlignment = Alignment.Center
            ) {
                Text(initials.ifBlank { "TV" }, color = GuideAccent, fontWeight = FontWeight.Bold, fontSize = 11.sp)
            }
        }
        channel.logoUrl?.let { logo ->
            AsyncImage(
                model = logo,
                contentDescription = null,
                modifier = Modifier.fillMaxSize(),
                contentScale = ContentScale.Fit,
                onSuccess = { ready = true },
                onError = { ready = false }
            )
        }
    }
}
