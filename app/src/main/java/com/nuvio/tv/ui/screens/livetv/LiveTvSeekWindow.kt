package com.nuvio.tv.ui.screens.livetv

/** Milliseconds on the engine's seekable timeline, up to its playable live edge. */
internal data class LiveTvSeekWindow(
    val startMs: Long,
    val edgeMs: Long,
    val positionMs: Long
) {
    val behindMs: Long get() = (edgeMs - positionMs).coerceIn(0L, edgeMs - startMs)
    val progress: Float get() = 1f - behindMs.toFloat() / (edgeMs - startMs)
    val isAtLiveEdge: Boolean get() = behindMs <= 2_500L

    fun seekBy(deltaMs: Long): Long =
        (positionMs.coerceIn(startMs, edgeMs) + deltaMs).coerceIn(startMs, edgeMs)
}
