package com.nuvio.tv.data.livetv

import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Test

class LiveTvRecordingScheduleTest {
    @Test
    fun `a third recording in the same slot is blocked while later slots remain open`() {
        val first = recording(1, 12, 13)
        assertNull(recordingScheduleLimit(listOf(first), 12, 13))
        val two = listOf(first, recording(2, 12, 14))
        assertEquals(RecordingRequest.OVERLAP, recordingScheduleLimit(two, 12, 13))
        assertNull(recordingScheduleLimit(two, 18, 19))
        assertNull(recordingScheduleLimit(two + recording(3, 18, 19), 22, 23))
    }

    @Test
    fun `a recording can span two back to back recordings`() {
        val sequential = listOf(recording(1, 12, 13), recording(2, 13, 14))
        assertNull(recordingScheduleLimit(sequential, 12, 14))
    }

    @Test
    fun `the queue holds ten upcoming recordings and excludes active and completed ones`() {
        val queued = (1L..10L).map { id -> recording(id, id * 2L + 12, id * 2L + 13) }
        val active = recording(11, 12, 13).copy(status = RecordingStatus.RECORDING)
        val completed = recording(12, 10, 11).copy(status = RecordingStatus.COMPLETE)
        assertNull(recordingScheduleLimit(queued.take(9) + active + completed, 36, 37))
        assertEquals(RecordingRequest.QUEUE_FULL, recordingScheduleLimit(queued + active + completed, 36, 37))
    }

    private fun recording(id: Long, start: Long, end: Long) = LiveRecording(
        id = id.toString(), profileId = 1, sourceKey = "fixture", channelId = id,
        channelName = "Channel $id", title = "Programme $id", startsAt = start,
        endsAt = end, runAt = start, format = "m3u8"
    )
}
