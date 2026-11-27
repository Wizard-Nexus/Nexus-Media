package com.nuvio.tv.ui.screens.livetv

import android.os.SystemClock
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.produceState
import androidx.compose.runtime.rememberUpdatedState
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.res.stringResource
import androidx.tv.material3.MaterialTheme
import androidx.tv.material3.Text
import com.nuvio.tv.R
import kotlinx.coroutines.delay
import kotlin.math.roundToInt

internal data class LiveTvVideoSample(
    val source: Any,
    val renderedFrames: Int?,
    val streamFps: Double?
)

internal data class LiveTvFps(val value: Int, val measured: Boolean)

internal fun sampledLiveTvFps(previous: LiveTvVideoSample?, current: LiveTvVideoSample?, elapsedMs: Long): LiveTvFps? {
    if (current == null) return null
    val previousFrames = previous?.renderedFrames
    val currentFrames = current.renderedFrames
    if (elapsedMs > 0 && previous?.source === current.source && previousFrames != null &&
        currentFrames != null && currentFrames >= previousFrames
    ) {
        return LiveTvFps(((currentFrames - previousFrames) * 1_000.0 / elapsedMs).roundToInt(), measured = true)
    }
    return current.streamFps?.takeIf { it.isFinite() && it > 0.0 }?.let {
        LiveTvFps(it.roundToInt(), measured = false)
    }
}

@Composable
internal fun LiveTvFpsReadout(readSample: () -> LiveTvVideoSample?) {
    val latest by rememberUpdatedState(readSample)
    val fps by produceState<LiveTvFps?>(null) {
        var previous: LiveTvVideoSample? = null
        var sampledAt = SystemClock.elapsedRealtime()
        while (true) {
            val current = latest()
            val now = SystemClock.elapsedRealtime()
            value = sampledLiveTvFps(previous, current, now - sampledAt)
            previous = current
            sampledAt = now
            delay(1_200L)
        }
    }
    val rate = fps
    Text(
        when {
            rate == null -> stringResource(R.string.live_tv_fps_waiting)
            rate.measured -> stringResource(R.string.live_tv_fps, rate.value)
            else -> stringResource(R.string.live_tv_stream_fps, rate.value)
        },
        color = Color.White.copy(alpha = .83f),
        style = MaterialTheme.typography.labelMedium,
        maxLines = 1
    )
}
