package com.nuvio.tv.ui.screens.player

import androidx.media3.common.Player
import androidx.media3.common.Timeline
import com.nuvio.tv.ui.screens.livetv.LiveTvSeekWindow

internal fun PlayerRuntimeController.liveTvSeekWindow(): LiveTvSeekWindow? {
    if (!LivePlaybackUiPolicy.isLiveContentType(contentType) || _uiState.value.error != null) return null
    if (isUsingMpvEngine()) return mpvView?.cachedLiveTvWindow()
    val player = exoPlayer ?: return null
    if (!player.isCurrentMediaItemLive || !player.isCurrentMediaItemSeekable ||
        !player.isCommandAvailable(Player.COMMAND_SEEK_IN_CURRENT_MEDIA_ITEM)) return null
    val window = player.currentTimeline.getWindow(player.currentMediaItemIndex, Timeline.Window())
    val edge = minOf(window.defaultPositionMs, window.durationMs)
    return if (edge > 0L) LiveTvSeekWindow(0L, edge, player.currentPosition) else null
}

internal fun PlayerRuntimeController.seekLiveTvBy(deltaMs: Long) {
    val window = liveTvSeekWindow()
    if (window != null) {
        val target = window.seekBy(deltaMs)
        if (target == window.edgeMs && !isUsingMpvEngine() && deltaMs > 0) {
            exoPlayer?.seekTo(target)
        } else if (target != window.positionMs) {
            seekPlaybackTo(target)
        }
    }
    if (_uiState.value.showControls) showControlsTemporarily() else showSeekOverlayTemporarily()
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
