package com.nuvio.tv.ui.screens.livetv

import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test

class LiveTvSeekWindowTest {
    @Test
    fun seekUsesTheCurrentlyAvailableWindow() {
        val window = LiveTvSeekWindow(startMs = 10_000, edgeMs = 35_000, positionMs = 14_000)
        assertEquals(10_000L, window.seekBy(-10_000))
        assertEquals(24_000L, window.seekBy(10_000))
        assertEquals(35_000L, window.seekBy(30_000))
        assertEquals(21_000L, window.behindMs)
    }

    @Test
    fun programmeStartUsesTheTimestampOnTheLiveWindowWhenAvailable() {
        val window = LiveTvSeekWindow(0L, 120_000L, 98_000L, startWallTimeMs = 1_700_000_000_000L)
        assertEquals(60_000L, window.programmeStart(1_700_000_060_000L, 1_700_000_130_000L))
        assertNull(window.programmeStart(1_699_999_999_999L, 1_700_000_130_000L))
    }

    @Test
    fun programmeStartFallsBackToDistanceBehindTheLiveEdge() {
        val now = 1_700_000_000_000L
        val window = LiveTvSeekWindow(10_000L, 120_000L, 95_000L)
        assertEquals(60_000L, window.programmeStart(now - 60_000L, now))
        assertNull(window.programmeStart(now - 121_000L, now))
        assertNull(window.programmeStart(now + 1L, now))
    }

    @Test
    fun estimatesRenderedFpsFromTwoSamplesOnTheSameDecoder() {
        val decoder = Any()
        val previous = LiveTvVideoSample(decoder, 240, 24.0)
        val current = LiveTvVideoSample(decoder, 276, 24.0)
        assertEquals(LiveTvFps(30, measured = true), sampledLiveTvFps(previous, current, 1_200L))
    }

    @Test
    fun showsTheReportedStreamRateWhenTheMeasuredCounterChangesOrResets() {
        val decoder = Any()
        val previous = LiveTvVideoSample(decoder, 240, 24.0)
        val current = LiveTvVideoSample(decoder, 3, 23.976)
        assertEquals(LiveTvFps(24, measured = false), sampledLiveTvFps(previous, current, 1_200L))
        assertEquals(LiveTvFps(24, measured = false), sampledLiveTvFps(previous, current.copy(source = Any()), 1_200L))
        assertNull(sampledLiveTvFps(null, null, 0L))
    }

    @Test
    fun positionsOutsideTheWindowAreClampedForDisplay() {
        assertEquals(0f, LiveTvSeekWindow(10_000, 35_000, 8_000).progress, .001f)
        assertEquals(1f, LiveTvSeekWindow(10_000, 35_000, 40_000).progress, .001f)
        assertTrue(LiveTvSeekWindow(10_000, 35_000, 34_000).isAtLiveEdge)
    }
}
