package com.nuvio.tv.ui.screens.livetv

import androidx.annotation.StringRes
import androidx.compose.foundation.background
import androidx.compose.foundation.layout.BoxScope
import androidx.compose.foundation.layout.padding
import androidx.compose.runtime.Composable
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.layout.layout
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.text.style.TextAlign
import androidx.compose.ui.unit.Constraints
import androidx.compose.ui.unit.Dp
import androidx.compose.ui.unit.DpSize
import androidx.compose.ui.unit.dp
import androidx.compose.ui.zIndex
import androidx.tv.material3.MaterialTheme
import androidx.tv.material3.Text

internal const val GUIDE_HEADER_SHARE = .405f
internal val GuideHeaderStart = 24.dp
internal val GuideHeaderEnd = 22.dp
internal val GuideHeaderTop = 12.dp
internal val GuideHeaderGap = 23.dp
internal const val GUIDE_PREVIEW_WEIGHT = .33f

internal fun guidePreviewSize(width: Dp, height: Dp): DpSize {
    val availableWidth = (width - GuideHeaderStart - GuideHeaderEnd - GuideHeaderGap) * GUIDE_PREVIEW_WEIGHT
    val availableHeight = (height * GUIDE_HEADER_SHARE - 48.dp).coerceAtLeast(1.dp)
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
