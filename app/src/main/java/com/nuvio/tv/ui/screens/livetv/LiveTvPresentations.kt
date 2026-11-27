package com.nuvio.tv.ui.screens.livetv

import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.PaddingValues
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.heightIn
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.lazy.LazyRow
import androidx.compose.foundation.lazy.items
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.produceState
import androidx.compose.runtime.remember
import androidx.compose.ui.Modifier
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import androidx.lifecycle.Lifecycle
import androidx.lifecycle.compose.LocalLifecycleOwner
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import androidx.lifecycle.repeatOnLifecycle
import androidx.tv.material3.Button
import androidx.tv.material3.ButtonDefaults
import androidx.tv.material3.MaterialTheme
import androidx.tv.material3.Text
import com.nuvio.tv.R
import com.nuvio.tv.data.livetv.LiveChannel
import com.nuvio.tv.data.livetv.nowAndNext
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

@Composable
internal fun KeepLiveTvGuideUpdated(viewModel: LiveTvViewModel, channelId: Long, sourceKey: String?) {
    val lifecycle = LocalLifecycleOwner.current.lifecycle
    LaunchedEffect(lifecycle, viewModel, channelId, sourceKey) {
        if (sourceKey == null) return@LaunchedEffect
        lifecycle.repeatOnLifecycle(Lifecycle.State.STARTED) {
            val channels = listOf(channelId)
            while (true) {
                viewModel.requestGuide(channels, full = true)
                delay(60_000L)
            }
        }
    }
}

internal fun formatProgrammeTime(time: Long): String =
    Instant.ofEpochMilli(time).atZone(ZoneId.systemDefault()).format(DateTimeFormatter.ofLocalizedTime(FormatStyle.SHORT))

@Composable
internal fun rememberLiveTvHomeShortcutsVisible(viewModel: LiveTvViewModel): Boolean {
    val state by viewModel.state.collectAsStateWithLifecycle()
    val favouriteIds = state.history.favourites
    val favouriteChannels = remember(state.catalog, state.categories.hidden, favouriteIds) {
        val visibleIds = state.visibleChannels().mapTo(hashSetOf()) { it.id }
        favouriteIds.asSequence().filter { it in visibleIds }.take(8).toList()
    }
    val now = liveGuideClock()
    LaunchedEffect(state.profileId, state.sourceKey, favouriteChannels, now / 60_000) {
        viewModel.requestGuide(favouriteChannels)
    }
    return favouriteChannels.isNotEmpty()
}

@Composable
internal fun LiveTvHomeShortcuts(
    viewModel: LiveTvViewModel,
    onPlay: (LiveChannel, List<LiveChannel>) -> Boolean
) {
    val state by viewModel.state.collectAsStateWithLifecycle()
    val channels = remember(state.catalog, state.categories.hidden) { state.visibleChannels() }
    if (channels.isEmpty()) return
    val now = liveGuideClock()
    val channelsById = remember(channels) { channels.associateBy { it.id } }
    val favourites = remember(channelsById, state.history.favourites) {
        state.history.favourites.mapNotNull(channelsById::get).take(8)
    }
    if (favourites.isNotEmpty()) {
        Column(modifier = Modifier.fillMaxWidth(), verticalArrangement = Arrangement.spacedBy(8.dp)) {
            Text(stringResource(R.string.live_tv_home_favourites), style = MaterialTheme.typography.titleMedium)
            LazyRow(horizontalArrangement = Arrangement.spacedBy(10.dp), contentPadding = PaddingValues(3.dp)) {
                items(favourites, key = { it.id }) { channel ->
                    val current = state.guide[channel.id].orEmpty().nowAndNext(now).first
                    LiveTvShortcut(channel.name, current?.title, Modifier.width(192.dp)) { onPlay(channel, favourites) }
                }
            }
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
