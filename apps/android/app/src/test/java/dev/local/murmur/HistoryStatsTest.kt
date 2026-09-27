package dev.local.murmur

import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Test
import java.time.LocalDate
import java.time.ZoneOffset
import java.util.TimeZone

class HistoryStatsTest {
    private val utc = TimeZone.getTimeZone("UTC")

    private fun day(date: String): Long = LocalDate.parse(date).atStartOfDay().toInstant(ZoneOffset.UTC).toEpochMilli()

    @Test fun weeklyWordsAndPaceMatchRetainedHistory() {
        val stats = calculateStats(sequenceOf(
            DictationRecord(1, day("2026-09-24"), "one two three four", "one two three four five", 24_000),
            DictationRecord(2, day("2026-09-25"), "one two three four five six", "one two three four five six seven eight nine ten eleven twelve thirteen fourteen fifteen", 36_000),
            DictationRecord(3, day("2026-09-17"), "one two three four five", "one two three four five six seven eight nine ten", 30_000),
            DictationRecord(4, day("2026-08-03"), "old", "old words remain", 0),
        ), day("2026-09-27"), utc)

        assertEquals(33, stats.totalWords)
        assertEquals(20, stats.thisWeekWords)
        assertEquals(10, stats.previousWeekWords)
        assertEquals(100.0, stats.weekOverWeekPercent!!, 0.001)
        assertEquals(4, stats.dictations)
        assertEquals(3, stats.recordingsWithDuration)
        assertEquals(1.5, stats.recordedMinutes, 0.001)
        assertEquals(10, stats.recordedWpm)
        assertEquals(8, stats.weeks.size)
        assertEquals(20, stats.weeks.last().words)
        assertEquals(10, stats.weeks[6].words)
        assertEquals(10, stats.weeks.last().recordedWpm)
    }

    @Test fun emptyHistoryHasNoPaceOrTrend() {
        val stats = calculateStats(emptySequence(), day("2026-09-27"), utc)
        assertEquals(0, stats.totalWords)
        assertEquals(0, stats.thisWeekWords)
        assertEquals(0, stats.previousWeekWords)
        assertNull(stats.recordedWpm)
        assertNull(stats.weekOverWeekPercent)
        assertEquals(8, stats.weeks.size)
    }

    @Test fun punctuationAndEmptyTranscriptsDoNotInflateStats() {
        val stats = calculateStats(sequenceOf(
            DictationRecord(1, day("2026-09-27"), "one two", "hello, -- world!", 30_000),
            DictationRecord(2, day("2026-09-27"), "one two", "... --", 30_000),
            DictationRecord(3, day("2026-09-27"), "...", "one", 30_000),
        ), day("2026-09-27"), utc)

        assertEquals(3, stats.totalWords)
        assertEquals(2, stats.dictations)
        assertEquals(1, stats.recordingsWithDuration)
        assertEquals(0.5, stats.recordedMinutes, 0.001)
        assertEquals(4, stats.recordedWpm)
    }
}
