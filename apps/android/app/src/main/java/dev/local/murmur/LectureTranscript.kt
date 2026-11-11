package dev.local.murmur

import java.util.Locale

internal data class TranscriptSegment(val startMs: Long?, val text: String)

// lecture text is stored as "[mm:ss] text" lines, one per transcribed part
internal object LectureTranscript {
    private val STAMP = Regex("""^\[(?:(\d+):)?(\d{1,3}):(\d{2})]\s*""")

    fun stamp(ms: Long): String = "[${clock(ms)}]"

    fun clock(ms: Long): String {
        val seconds = (ms / 1000).coerceAtLeast(0)
        val hours = seconds / 3600
        return if (hours > 0) String.format(Locale.ROOT, "%d:%02d:%02d", hours, seconds / 60 % 60, seconds % 60)
        else String.format(Locale.ROOT, "%02d:%02d", seconds / 60, seconds % 60)
    }

    fun parse(text: String): List<TranscriptSegment> {
        val segments = mutableListOf<TranscriptSegment>()
        text.lineSequence().forEach { line ->
            val match = STAMP.find(line)
            if (match != null) {
                val (hours, minutes, seconds) = match.destructured
                val startMs = ((hours.toLongOrNull() ?: 0L) * 3600 + minutes.toLong() * 60 + seconds.toLong()) * 1000
                segments += TranscriptSegment(startMs, line.substring(match.range.last + 1).trim())
            } else if (line.isNotBlank()) {
                val last = segments.lastOrNull()
                // a cleanup model can split one part over several lines, keep them with their stamp
                if (last != null) segments[segments.lastIndex] = last.copy(text = last.text + "\n" + line.trim())
                else segments += TranscriptSegment(null, line.trim())
            }
        }
        return segments
    }

    // copied or shared text reads better without the stamps
    fun plain(text: String): String = parse(text).joinToString("\n\n") { it.text }
}
