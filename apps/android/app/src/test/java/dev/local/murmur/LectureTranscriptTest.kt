package dev.local.murmur

import org.junit.Assert.assertEquals
import org.junit.Test

class LectureTranscriptTest {
    @Test
    fun parsesStampsIncludingOldMinuteOnlyFormat() {
        val segments = LectureTranscript.parse("[00:00] Intro.\n[02:00] Second part.\n[62:00] Past an hour.\n[1:04:00] New format.")
        assertEquals(listOf(0L, 120_000L, 3_720_000L, 3_840_000L), segments.map { it.startMs })
        assertEquals("Past an hour.", segments[2].text)
    }

    @Test
    fun keepsContinuationLinesWithTheirStamp() {
        val segments = LectureTranscript.parse("[02:00] First line.\nSecond line.\n\n[04:00] Next.")
        assertEquals(2, segments.size)
        assertEquals("First line.\nSecond line.", segments[0].text)
    }

    @Test
    fun keepsTextWithoutStamps() {
        assertEquals(listOf(TranscriptSegment(null, "Just text")), LectureTranscript.parse("Just text"))
    }

    @Test
    fun formatsClockAcrossTheHour() {
        assertEquals("[59:59]", LectureTranscript.stamp(3_599_000))
        assertEquals("[1:00:00]", LectureTranscript.stamp(3_600_000))
    }
}
