package dev.local.murmur

import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test
import kotlin.math.sin

class AudioChunksTest {
    private val rate = 16_000

    // speech stands in as a loud tone, pauses as near silence with a little hiss
    private fun pcm(vararg parts: Pair<Boolean, Double>): ByteArray {
        val bytes = ArrayList<Byte>()
        var n = 0
        for ((speech, seconds) in parts) {
            repeat((seconds * rate).toInt()) {
                val sample = if (speech) (8_000 * sin(n * 0.2)).toInt() else (n % 7) - 3
                bytes += (sample and 0xff).toByte()
                bytes += ((sample shr 8) and 0xff).toByte()
                n++
            }
        }
        return bytes.toByteArray()
    }

    private fun feed(chunker: PauseChunker, audio: ByteArray): List<ByteArray> {
        val pieces = mutableListOf<ByteArray>()
        var offset = 0
        while (offset < audio.size) {
            val count = minOf(3_200, audio.size - offset)
            pieces += chunker.add(audio.copyOfRange(offset, offset + count), count)
            offset += count
        }
        return pieces
    }

    @Test
    fun cutsInsideTheFirstPauseAfterTheMinimum() {
        val audio = pcm(true to 8.0, false to 0.5, true to 6.0, false to 0.6, true to 4.0)
        val chunker = PauseChunker()
        val pieces = feed(chunker, audio)

        assertEquals(1, pieces.size)
        val seconds = pieces[0].size / 32_000.0
        // the pause at 8 s is too early, the one from 14.5 s to 15.1 s is taken
        assertTrue("cut at $seconds s", seconds in 14.5..15.1)
        val tail = chunker.finish()!!
        assertEquals(audio.size, pieces[0].size + tail.size)
    }

    @Test
    fun forcesACutAtTheQuietestPointWhenTheSpeakerNeverPauses() {
        val audio = pcm(true to 22.0, false to 0.1, true to 8.0)
        val pieces = feed(PauseChunker(), audio)

        assertEquals(1, pieces.size)
        val seconds = pieces[0].size / 32_000.0
        assertTrue("cut at $seconds s", seconds in 22.0..22.1)
    }

    @Test
    fun dropsASilentTailSoWhisperDoesNotInventWords() {
        val chunker = PauseChunker()
        feed(chunker, pcm(true to 13.0, false to 1.0, false to 2.0))
        assertNull(chunker.finish())
    }

    @Test
    fun splitsLongAudioAtTheQuietestFrameBeforeEachLimit() {
        val samples = FloatArray(50 * rate) { index ->
            val second = index / rate.toDouble()
            if (second in 17.0..17.3 || second in 33.0..33.3) 0f else 0.5f * sin(index * 0.2).toFloat()
        }
        val pieces = splitAtPauses(samples, 20 * rate, 6 * rate)

        assertEquals(3, pieces.size)
        assertEquals(0, pieces.first().first)
        assertEquals(samples.size - 1, pieces.last().last)
        pieces.zipWithNext().forEach { (left, right) -> assertEquals(left.last + 1, right.first) }
        assertTrue(pieces[0].last / rate.toDouble() in 17.0..17.3)
        assertTrue(pieces[1].last / rate.toDouble() in 33.0..33.3)
    }
}
