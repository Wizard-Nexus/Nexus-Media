package com.nuvio.tv.ui.screens.livetv

import androidx.annotation.StringRes
import androidx.compose.animation.AnimatedVisibility
import androidx.compose.animation.core.FastOutSlowInEasing
import androidx.compose.animation.core.RepeatMode
import androidx.compose.animation.core.animateFloat
import androidx.compose.animation.core.infiniteRepeatable
import androidx.compose.animation.core.rememberInfiniteTransition
import androidx.compose.animation.core.tween
import androidx.compose.animation.fadeIn
import androidx.compose.animation.fadeOut
import androidx.compose.foundation.background
import androidx.compose.foundation.layout.BoxScope
import androidx.compose.foundation.layout.BoxWithConstraints
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.padding
import androidx.compose.runtime.Composable
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.Brush
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.graphicsLayer
import androidx.compose.ui.layout.layout
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.semantics.contentDescription
import androidx.compose.ui.semantics.semantics
import androidx.compose.ui.text.style.TextAlign
import androidx.compose.ui.unit.Constraints
import androidx.compose.ui.unit.Dp
import androidx.compose.ui.unit.DpSize
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import androidx.compose.ui.zIndex
import androidx.tv.material3.MaterialTheme
import androidx.tv.material3.Text
import com.nuvio.tv.R
import com.nuvio.tv.data.livetv.LiveChannel
import com.nuvio.tv.ui.components.LoadingIndicator

internal const val GUIDE_HEADER_SHARE = .405f
internal val GuideHeaderStart = 24.dp
internal val GuideHeaderEnd = 22.dp
internal val GuideHeaderTop = 12.dp
internal val GuideHeaderGap = 23.dp
internal val GuidePreviewBottom = 8.dp
private const val GUIDE_PREVIEW_MAX_WIDTH_SHARE = .41f

internal fun guidePreviewSize(width: Dp, height: Dp): DpSize {
    val availableWidth = (width - GuideHeaderStart - GuideHeaderEnd - GuideHeaderGap) * GUIDE_PREVIEW_MAX_WIDTH_SHARE
    val availableHeight = (height * GUIDE_HEADER_SHARE - GuideHeaderTop - GuidePreviewBottom).coerceAtLeast(1.dp)
    val videoWidth = minOf(availableWidth, availableHeight * (16f / 9f)).coerceAtLeast(1.dp)
    return DpSize(videoWidth, videoWidth * (9f / 16f))
}

// Move the existing video view into the header without detaching it or preparing a second stream.
internal fun Modifier.liveTvGuidePreview(): Modifier = this.zIndex(5f)
    .layout { measurable, constraints ->
        val size = guidePreviewSize(constraints.maxWidth.toDp(), constraints.maxHeight.toDp())
        val preview = measurable.measure(Constraints.fixed(size.width.roundToPx(), size.height.roundToPx()))
        layout(constraints.maxWidth, constraints.maxHeight) {
            preview.placeRelative(GuideHeaderStart.roundToPx(), GuideHeaderTop.roundToPx())
        }
    }
    .background(Color.Black)

@Composable
internal fun BoxScope.LiveTvPreviewStatus(@StringRes message: Int) {
    Text(
        stringResource(message),
        modifier = Modifier.align(Alignment.Center).background(Color.Black.copy(alpha = .72f)).padding(8.dp),
        color = Color.White,
        style = MaterialTheme.typography.labelMedium,
        textAlign = TextAlign.Center
    )
}


private val ChannelLoadingBackdrop = Brush.radialGradient(listOf(Color(0xFF1E293C), Color(0xFF080B10)))

@Composable
internal fun LiveTvChannelLoading(visible: Boolean, channel: LiveChannel?) {
    AnimatedVisibility(
        visible = visible,
        modifier = Modifier.fillMaxSize(),
        enter = fadeIn(tween(120)),
        exit = fadeOut(tween(180))
    ) {
        val status = if (channel == null) {
            stringResource(R.string.live_tv_vlc_loading)
        } else {
            stringResource(R.string.live_tv_opening_channel, channel.name)
        }
        BoxWithConstraints(
            modifier = Modifier.fillMaxSize().background(ChannelLoadingBackdrop)
                .semantics { contentDescription = status },
            contentAlignment = Alignment.Center
        ) {
            if (channel == null) {
                LoadingIndicator()
            } else {
                val zoom = rememberInfiniteTransition(label = "channel opening").animateFloat(
                    initialValue = .93f,
                    targetValue = 1.08f,
                    animationSpec = infiniteRepeatable(
                        animation = tween(1_200, easing = FastOutSlowInEasing),
                        repeatMode = RepeatMode.Reverse
                    ),
                    label = "logo scale"
                )
                val width = (maxWidth * .64f).coerceAtMost(340.dp)
                val height = (maxHeight * .59f).coerceAtMost(188.dp)
                ChannelLogo(
                    channel,
                    Modifier.size(width, height).graphicsLayer {
                        scaleX = zoom.value
                        scaleY = zoom.value
                    },
                    fallbackTextSize = (height.value * .43f).sp
                )
            }
        }
    }
}
