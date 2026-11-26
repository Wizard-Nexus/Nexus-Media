package com.nuvio.tv.ui.screens.livetv

import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.PaddingValues
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.heightIn
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.LazyRow
import androidx.compose.foundation.lazy.items
import androidx.compose.foundation.lazy.rememberLazyListState
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.produceState
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Modifier
import androidx.compose.ui.focus.FocusRequester
import androidx.compose.ui.focus.focusRequester
import androidx.compose.ui.input.key.Key
import androidx.compose.ui.input.key.KeyEventType
import androidx.compose.ui.input.key.key
import androidx.compose.ui.input.key.onPreviewKeyEvent
import androidx.compose.ui.input.key.type
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.text.input.ImeAction
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import androidx.tv.material3.Button
import androidx.tv.material3.ButtonDefaults
import androidx.tv.material3.MaterialTheme
import androidx.tv.material3.Text
import com.nuvio.tv.R
import com.nuvio.tv.data.livetv.LiveChannel
import com.nuvio.tv.data.livetv.LiveProgramme
import com.nuvio.tv.data.livetv.LiveSportsEvent
import com.nuvio.tv.data.livetv.liveSportsEvents
import com.nuvio.tv.data.livetv.nowAndNext
import com.nuvio.tv.ui.components.NuvioDialog
import com.nuvio.tv.ui.screens.account.InputField
import com.nuvio.tv.ui.theme.NuvioTheme
import kotlinx.coroutines.delay
import java.time.Instant
import java.time.ZoneId
import java.time.format.DateTimeFormatter
import java.time.format.FormatStyle

internal fun LiveChannel.matchesLiveTvSearch(query: String): Boolean {
    val text = query.trim()
    return text.isEmpty() || name.contains(text, ignoreCase = true) || group?.contains(text, ignoreCase = true) == true
}

@Composable
internal fun liveGuideClock(): Long = produceState(System.currentTimeMillis()) {
    while (true) {
        delay(30_000L)
        value = System.currentTimeMillis()
    }
}.value

internal fun formatProgrammeTime(time: Long): String =
    Instant.ofEpochMilli(time).atZone(ZoneId.systemDefault()).format(DateTimeFormatter.ofLocalizedTime(FormatStyle.SHORT))

internal fun featuredSportsEvents(
    channels: List<LiveChannel>,
    guide: Map<Long, List<LiveProgramme>>,
    now: Long,
    perSection: Int
): List<LiveSportsEvent> {
    val (onAir, upcoming) = liveSportsEvents(channels, guide, now).partition { it.programme.isOnAir(now) }
    return onAir.take(perSection) + upcoming.take(perSection)
}

@Composable
internal fun LiveTvSportsStrip(
    events: List<LiveSportsEvent>,
    now: Long,
    emptyMessage: Int? = null,
    onSelect: (LiveSportsEvent) -> Unit
) {
    if (events.isEmpty()) {
        emptyMessage?.let {
            Text(stringResource(it), style = MaterialTheme.typography.bodySmall, color = NuvioTheme.colors.TextSecondary)
        }
        return
    }
    LazyRow(
        horizontalArrangement = Arrangement.spacedBy(10.dp),
        contentPadding = PaddingValues(vertical = 4.dp, horizontal = 3.dp)
    ) {
        items(events, key = { "${it.channel.id}-${it.programme.startsAt}-${it.programme.endsAt}-${it.programme.title}" }) { event ->
            val label = if (event.programme.isOnAir(now)) {
                stringResource(R.string.live_tv_event_on_air, event.channel.name)
            } else {
                stringResource(R.string.live_tv_event_starts, formatProgrammeTime(event.programme.startsAt), event.channel.name)
            }
            LiveTvShortcut(event.programme.title, label, Modifier.width(224.dp)) { onSelect(event) }
        }
    }
}

@Composable
internal fun LiveTvEventDialog(event: LiveSportsEvent, onWatch: () -> Unit, onDismiss: () -> Unit) {
    NuvioDialog(
        onDismiss = onDismiss,
        title = event.programme.title,
        subtitle = stringResource(
            R.string.live_tv_event_starts, formatProgrammeTime(event.programme.startsAt), event.channel.name
        )
    ) {
        Row(horizontalArrangement = Arrangement.spacedBy(10.dp)) {
            Button(onClick = onDismiss) { Text(stringResource(R.string.action_cancel)) }
            Button(onClick = onWatch) { Text(stringResource(R.string.live_tv_watch_channel)) }
        }
    }
}

@Composable
internal fun rememberLiveTvHomeShortcutsVisible(viewModel: LiveTvViewModel): Boolean {
    val state by viewModel.state.collectAsStateWithLifecycle()
    val channels = state.catalog?.channels.orEmpty()
    if (channels.isEmpty()) return false
    val now = liveGuideClock()
    val favouriteIds = state.history.favourites
    val sports = remember(channels) { channels.filter { it.isSports } }
    val guideSeeds = remember(favouriteIds, sports) { (favouriteIds.take(8) + sports.take(8).map { it.id }).distinct() }
    LaunchedEffect(state.profileId, state.sourceKey, guideSeeds, now / 60_000) { viewModel.requestGuide(guideSeeds) }
    return channels.any { it.id in favouriteIds } ||
        sports.any { channel -> state.guide[channel.id].orEmpty().any { it.endsAt > now } }
}

@Composable
internal fun LiveTvHomeShortcuts(
    viewModel: LiveTvViewModel,
    onPlay: (LiveChannel, List<LiveChannel>) -> Boolean
) {
    val state by viewModel.state.collectAsStateWithLifecycle()
    val channels = state.catalog?.channels.orEmpty()
    if (channels.isEmpty()) return
    val now = liveGuideClock()
    val channelsById = remember(channels) { channels.associateBy { it.id } }
    val favourites = remember(channelsById, state.history.favourites) {
        state.history.favourites.mapNotNull(channelsById::get).take(8)
    }
    val sports = remember(channels) { channels.filter { it.isSports } }
    val events = remember(sports, state.guide, now) { featuredSportsEvents(sports, state.guide, now, 4) }
    var selectedEvent by remember(state.profileId) { mutableStateOf<LiveSportsEvent?>(null) }
    if (favourites.isNotEmpty() || events.isNotEmpty()) {
        Column(modifier = Modifier.fillMaxWidth(), verticalArrangement = Arrangement.spacedBy(8.dp)) {
            if (favourites.isNotEmpty()) {
                Text(stringResource(R.string.live_tv_home_favourites), style = MaterialTheme.typography.titleMedium)
                LazyRow(horizontalArrangement = Arrangement.spacedBy(10.dp), contentPadding = PaddingValues(3.dp)) {
                    items(favourites, key = { it.id }) { channel ->
                        val current = state.guide[channel.id].orEmpty().nowAndNext(now).first
                        LiveTvShortcut(channel.name, current?.title, Modifier.width(192.dp)) { onPlay(channel, favourites) }
                    }
                }
            }
            if (events.isNotEmpty()) {
                Text(stringResource(R.string.live_tv_home_sports), style = MaterialTheme.typography.titleMedium)
                LiveTvSportsStrip(events, now) { event ->
                    if (event.programme.isOnAir(now)) onPlay(event.channel, sports) else selectedEvent = event
                }
            }
        }
    }
    selectedEvent?.let { event ->
        LiveTvEventDialog(event, onWatch = {
            selectedEvent = null
            onPlay(event.channel, sports)
        }, onDismiss = { selectedEvent = null })
    }
}

@Composable
internal fun LiveTvChannelPicker(
    viewModel: LiveTvViewModel,
    currentId: Long,
    onPlay: (LiveChannel) -> Unit,
    onDismiss: () -> Unit
) {
    val state by viewModel.state.collectAsStateWithLifecycle()
    val channels = state.catalog?.channels.orEmpty()
    var query by remember { mutableStateOf("") }
    val ordered = remember(channels, state.history, currentId) {
        val channelIndex = channels.associateBy { it.id }
        val priority = (listOf(currentId) + state.history.favourites + state.history.recent).distinct().mapNotNull(channelIndex::get)
        val selectedIds = priority.map { it.id }.toHashSet()
        priority + channels.filter { it.id !in selectedIds }
    }
    val matches = remember(ordered, query) { ordered.filter { it.matchesLiveTvSearch(query) } }
    val listState = rememberLazyListState()
    val firstChannel = remember { FocusRequester() }
    val now = liveGuideClock()

    NuvioDialog(
        title = stringResource(R.string.live_tv_channel_picker),
        width = 620.dp,
        onDismiss = onDismiss,
        contentSpacing = 8.dp
    ) {
        InputField(
            value = query,
            onValueChange = { query = it },
            placeholder = stringResource(R.string.live_tv_search_hint),
            imeAction = ImeAction.Done,
            onImeAction = { if (matches.isNotEmpty()) runCatching { firstChannel.requestFocus() } },
            modifier = Modifier.fillMaxWidth().onPreviewKeyEvent { event ->
                event.type == KeyEventType.KeyDown && event.key == Key.DirectionDown && matches.isNotEmpty() &&
                    runCatching { firstChannel.requestFocus() }.getOrDefault(false)
            }
        )
        LazyColumn(
            state = listState,
            modifier = Modifier.fillMaxWidth().heightIn(max = 206.dp),
            contentPadding = PaddingValues(4.dp),
            verticalArrangement = Arrangement.spacedBy(5.dp)
        ) {
            if (matches.isEmpty()) {
                item { Text(stringResource(R.string.live_tv_search_empty), color = NuvioTheme.colors.TextSecondary) }
            }
            items(matches, key = { it.id }) { channel ->
                val current = state.guide[channel.id].orEmpty().nowAndNext(now).first
                val label = if (channel.id == currentId) stringResource(R.string.live_tv_current_channel, channel.name) else channel.name
                LiveTvShortcut(
                    label,
                    current?.title,
                    Modifier.fillMaxWidth().then(
                        if (channel.id == matches.first().id) Modifier.focusRequester(firstChannel) else Modifier
                    ),
                    onClick = { if (channel.id == currentId) onDismiss() else onPlay(channel) }
                )
            }
        }
        LaunchedEffect(query) { listState.scrollToItem(0) }
        LaunchedEffect(Unit) {
            if (matches.isNotEmpty()) runCatching { firstChannel.requestFocus() }
        }
    }
}

@Composable
private fun LiveTvShortcut(label: String, supportingText: String?, modifier: Modifier, onClick: () -> Unit) {
    Button(
        onClick = onClick,
        modifier = modifier.heightIn(min = 54.dp),
        scale = ButtonDefaults.scale(focusedScale = 1f)
    ) {
        Column {
            Text(label, maxLines = 1, overflow = TextOverflow.Ellipsis)
            supportingText?.let {
                Text(it, maxLines = 1, overflow = TextOverflow.Ellipsis, style = MaterialTheme.typography.labelSmall)
            }
        }
    }
}
