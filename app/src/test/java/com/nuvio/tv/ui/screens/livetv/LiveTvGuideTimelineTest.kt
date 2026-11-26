package com.nuvio.tv.ui.screens.livetv

import com.nuvio.tv.data.livetv.LiveProgramme
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Test

class LiveTvGuideTimelineTest {
    private val previous = LiveProgramme("Previous", 0, 30)
    private val current = LiveProgramme("Current", 30, 75)
    private val later = LiveProgramme("Later", 100, 150)
    private val programmes = listOf(previous, current, later)

    @Test
    fun clipsProgrammesAtTheViewportAndLeavesAVisibleGap() {
        assertEquals(
            listOf(
                GuideBlock(10, 30, previous),
                GuideBlock(30, 75, current),
                GuideBlock(75, 100, null),
                GuideBlock(100, 110, later)
            ),
            guideBlocks(programmes, 10, 110)
        )
    }

    @Test
    fun browsesBothSidesOfCurrentTimeAndStopsAtAvailableData() {
        assertEquals(0L, stepGuideTime(programmes, 56L, forward = false))
        assertEquals(100L, stepGuideTime(programmes, 56L, forward = true))
        assertEquals(30L, stepGuideTime(programmes, 90L, forward = false))
        assertNull(stepGuideTime(programmes, 100L, forward = true))
        assertNull(stepGuideTime(programmes, 10L, forward = false))
    }
}
