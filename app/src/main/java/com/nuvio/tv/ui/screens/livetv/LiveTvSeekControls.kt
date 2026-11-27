package com.nuvio.tv.ui.screens.livetv

import androidx.compose.animation.core.animateFloatAsState
import androidx.compose.animation.core.tween
import androidx.compose.foundation.background
import androidx.compose.foundation.focusable
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.runtime.Composable
import androidx.compose.runtime.State
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.produceState
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberUpdatedState
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.focus.FocusRequester
import androidx.compose.ui.focus.focusProperties
import androidx.compose.ui.focus.focusRequester
import androidx.compose.ui.focus.onFocusChanged
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.input.key.onPreviewKeyEvent
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.semantics.ProgressBarRangeInfo
import androidx.compose.ui.semantics.contentDescription
import androidx.compose.ui.semantics.progressBarRangeInfo
import androidx.compose.ui.semantics.semantics
import androidx.compose.ui.unit.dp
import androidx.tv.material3.Button
import androidx.tv.material3.ButtonDefaults
import androidx.tv.material3.MaterialTheme
import androidx.tv.material3.Text
import com.nuvio.tv.R
import com.nuvio.tv.ui.screens.player.formatTime
import com.nuvio.tv.ui.screens.player.playerProgressFill
import com.nuvio.tv.ui.theme.NuvioTheme
import com.nuvio.tv.ui.theme.accentBrush
import kotlinx.coroutines.delay

@Composable
internal fun rememberLiveTvSeekWindow(
    active: Boolean,
    source: Any?,
    readWindow: () -> LiveTvSeekWindow?
): State<LiveTvSeekWindow?> {
    val latest by rememberUpdatedState(readWindow)
    return produceState<LiveTvSeekWindow?>(null, active, source) {
        if (!active) {
            value = null
            return@produceState
        }
        while (true) {
            value = latest()
            delay(400L)
        }
    }
}

@Composable
internal fun LiveTvSeekBar(
    window: LiveTvSeekWindow?,
    modifier: Modifier = Modifier,
    focusRequester: FocusRequester? = null,
    downFocusRequester: FocusRequester? = null,
    onFocused: (() -> Unit)? = null,
    onSeek: ((Long) -> Unit)? = null
) {
    if (window == null) {
        Text(
            stringResource(R.string.live_tv_seek_unavailable),
            modifier = modifier,
            color = Color.White.copy(alpha = .85f),
            style = MaterialTheme.typography.bodySmall
        )
        return
    }
    val label = stringResource(R.string.live_tv_seek_bar)
    val progress by animateFloatAsState(window.progress, tween(120), label = "live seek")
    var focused by remember { mutableStateOf(false) }
    val remote = if (onSeek == null) Modifier else Modifier
        .then(if (focusRequester != null) Modifier.focusRequester(focusRequester) else Modifier)
        .focusProperties { downFocusRequester?.let { down = it } }
        .onFocusChanged {
            focused = it.isFocused
            if (focused) onFocused?.invoke()
        }
        .onPreviewKeyEvent { handleLiveTvSeekKey(it.nativeKeyEvent, onSeek) }
        .focusable()
    Box(
        modifier.fillMaxWidth().height(22.dp)
            .then(remote)
            .semantics {
                contentDescription = label
                progressBarRangeInfo = ProgressBarRangeInfo(window.progress, 0f..1f)
            },
        contentAlignment = Alignment.Center
    ) {
        Box(
            Modifier.fillMaxWidth().height(if (focused) 8.dp else 5.dp)
                .clip(RoundedCornerShape(4.dp))
                .background(Color.White.copy(alpha = if (focused) .48f else .30f))
                .playerProgressFill(
                    progress = { progress },
                    bufferedProgress = { 0f },
                    bufferedColor = Color.Transparent,
                    playedBrush = NuvioTheme.palette.accentBrush()
                )
        )
    }
}

@Composable
internal fun LiveTvEdgeButton(
    window: LiveTvSeekWindow?,
    watchedMs: Long,
    onClick: () -> Unit,
    modifier: Modifier = Modifier,
    onFocused: (() -> Unit)? = null,
    showGoLiveLabel: Boolean = false
) {
    val action = stringResource(R.string.live_tv_go_live)
    val label = if (showGoLiveLabel) action else liveTvTimeLabel(window, watchedMs)
    LiveTvPlaybackButton(label, onClick, modifier.semantics { contentDescription = action }, onFocused)
}

@Composable
internal fun LiveTvStartOverButton(
    onClick: () -> Unit,
    modifier: Modifier = Modifier,
    onFocused: (() -> Unit)? = null
) {
    LiveTvPlaybackButton(stringResource(R.string.live_tv_start_over), onClick, modifier, onFocused)
}

@Composable
private fun LiveTvPlaybackButton(label: String, onClick: () -> Unit, modifier: Modifier, onFocused: (() -> Unit)?) {
    Button(
        onClick = onClick,
        modifier = modifier.height(38.dp).onFocusChanged { if (it.isFocused) onFocused?.invoke() },
        colors = ButtonDefaults.colors(
            containerColor = Color.White.copy(alpha = .17f),
            contentColor = Color.White,
            focusedContainerColor = Color.White,
            focusedContentColor = Color.Black
        )
    ) {
        Text(label, style = MaterialTheme.typography.labelLarge)
    }
}

@Composable
internal fun LiveTvSeekFeedback(window: LiveTvSeekWindow?, watchedMs: Long, modifier: Modifier = Modifier) {
    Column(
        modifier.fillMaxWidth().background(Color.Black.copy(alpha = .80f)).padding(horizontal = 28.dp, vertical = 14.dp),
        verticalArrangement = Arrangement.spacedBy(4.dp)
    ) {
        LiveTvSeekBar(window)
        Text(
            liveTvTimeLabel(window, watchedMs),
            modifier = Modifier.align(Alignment.End),
            color = Color.White,
            style = MaterialTheme.typography.labelLarge
        )
    }
}

@Composable
private fun liveTvTimeLabel(window: LiveTvSeekWindow?, watchedMs: Long): String =
    if (window != null && !window.isAtLiveEdge) {
        stringResource(R.string.live_tv_behind_live, formatTime(window.behindMs))
    } else {
        stringResource(R.string.player_live_watched, formatTime(watchedMs))
    }
