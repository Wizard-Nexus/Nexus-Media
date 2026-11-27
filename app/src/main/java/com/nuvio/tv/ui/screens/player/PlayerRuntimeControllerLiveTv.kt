package com.nuvio.tv.ui.screens.player

import androidx.media3.common.Player
import androidx.media3.common.Timeline
import androidx.media3.exoplayer.SeekParameters
import com.nuvio.tv.ui.screens.livetv.LiveTvSeekWindow
import com.nuvio.tv.ui.screens.livetv.LiveTvVideoSample

internal val PlayerRuntimeController.isLiveTvStartOver: Boolean
    get() = navigationArgs.startFromBeginning &&
        LivePlaybackUiPolicy.isLiveContentType(contentType) &&
        savedStateHandle.get<String>("liveChannelId")?.toLongOrNull() != null

internal fun PlayerRuntimeController.liveTvVideoSample(): LiveTvVideoSample? {
    if (isUsingMpvEngine()) {
        val view = mpvView ?: return null
        return LiveTvVideoSample(view, null, view.streamFrameRate())
    }
    val player = exoPlayer ?: return null
    val counters = player.videoDecoderCounters
    counters?.ensureUpdated()
    return LiveTvVideoSample(
        counters ?: player,
        counters?.renderedOutputBufferCount?.takeIf { it > 0 },
        player.videoFormat?.frameRate?.toDouble()
    )
}

internal fun PlayerRuntimeController.liveTvSeekWindow(): LiveTvSeekWindow? {
    if (!LivePlaybackUiPolicy.isLiveContentType(contentType) || _uiState.value.error != null) return null
    val startOver = isLiveTvStartOver
    if (isUsingMpvEngine()) {
        val view = mpvView ?: return null
        if (!startOver || view.isLiveStreamNow()) return view.cachedLiveTvWindow()
        if (!view.isPositionFromRequestedMedia()) return null
        return view.durationMs().takeIf { it > 0L }?.let { LiveTvSeekWindow(0L, it, view.currentPositionMs()) }
    }
    val player = exoPlayer ?: return null
    if ((!player.isCurrentMediaItemLive && !startOver) || !player.isCurrentMediaItemSeekable ||
        !player.isCommandAvailable(Player.COMMAND_SEEK_IN_CURRENT_MEDIA_ITEM)) return null
    val window = player.currentTimeline.getWindow(player.currentMediaItemIndex, Timeline.Window())
    val edge = if (startOver && !player.isCurrentMediaItemLive) window.durationMs else minOf(window.defaultPositionMs, window.durationMs)
    val origin = window.windowStartTimeMs.takeIf { it > 0L }
    return if (edge > 0L) LiveTvSeekWindow(0L, edge, player.currentPosition, origin) else null
}

internal fun PlayerRuntimeController.seekLiveTvBy(deltaMs: Long) {
    val window = liveTvSeekWindow()
    if (window != null) {
        val replay = isLiveTvStartOver
        val target = window.seekBy(deltaMs, keepReplayTail = replay)
        when {
            target == window.positionMs -> Unit
            replay -> seekPlaybackTo(target, SeekParameters.EXACT, exact = true)
            target == window.edgeMs && !isUsingMpvEngine() && deltaMs > 0 -> exoPlayer?.seekTo(target)
            else -> seekPlaybackTo(target)
        }
    }
    if (_uiState.value.showControls) showControlsTemporarily() else showSeekOverlayTemporarily()
}

internal fun PlayerRuntimeController.seekLiveProgrammeStart(startsAt: Long?): Boolean {
    val window = liveTvSeekWindow() ?: return false
    val position = if (startsAt == null) window.startMs else {
        window.programmeStart(startsAt, System.currentTimeMillis()) ?: return false
    }
    userPausedManually = false
    cancelPauseOverlay()
    seekPlaybackTo(position, SeekParameters.EXACT, exact = true)
    if (isUsingMpvEngine()) mpvView?.setPaused(false) else exoPlayer?.play()
    showControlsTemporarily()
    return true
}

internal fun PlayerRuntimeController.goLiveTv() {
    if (!LivePlaybackUiPolicy.isLiveContentType(contentType)) return
    userPausedManually = false
    cancelPauseOverlay()
    val player = exoPlayer
    if (!isUsingMpvEngine() && player != null && player.isCurrentMediaItemLive &&
        player.isCommandAvailable(Player.COMMAND_SEEK_TO_DEFAULT_POSITION)) {
        player.seekToDefaultPosition()
        player.play()
    } else {
        // For a linear feed or an engine without a live-edge API, rejoin the channel.
        onEvent(PlayerEvent.OnRetry, showPlaybackControls = false)
    }
    showControlsTemporarily()
}
