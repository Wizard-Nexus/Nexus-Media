package com.nuvio.tv.ui.screens.livetv

import org.junit.Assert.assertEquals
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
    fun positionsOutsideTheWindowAreClampedForDisplay() {
        assertEquals(0f, LiveTvSeekWindow(10_000, 35_000, 8_000).progress, .001f)
        assertEquals(1f, LiveTvSeekWindow(10_000, 35_000, 40_000).progress, .001f)
        assertTrue(LiveTvSeekWindow(10_000, 35_000, 34_000).isAtLiveEdge)
    }
}
