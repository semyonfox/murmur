package dev.local.murmur

import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertThrows
import org.junit.Assert.assertTrue
import org.junit.Test
import java.io.File
import java.nio.ByteBuffer
import java.nio.ByteOrder
import java.nio.file.Files

class LectureAudioTest {
    @Test fun splitsRecordingAtAQuietMomentAndDeletesTemporaryParts() {
        val directory = Files.createTempDirectory("lecture-audio-test").toFile()
        try {
            val source = File(directory, "lecture-source.wav")
            val seconds = 125
            // a steady tone with one quiet stretch from 115.0 s to 115.4 s
            val pcm = ByteBuffer.allocate(16_000 * 2 * seconds).order(ByteOrder.LITTLE_ENDIAN)
            for (index in 0 until 16_000 * seconds) {
                val second = index / 16_000.0
                pcm.putShort(if (second in 115.0..115.4) 0 else (4_000 * kotlin.math.sin(index * 0.2)).toInt().toShort())
            }
            source.writeBytes(header(pcm.capacity()) + pcm.array())
            val sizes = mutableListOf<Long>()
            val offsets = mutableListOf<Long>()
            LectureAudio.forEachChunk(source, directory) { part, index, total, offsetMs ->
                assertEquals(2, total)
                assertEquals(sizes.size, index)
                sizes.add(part.length() - 44)
                offsets.add(offsetMs)
                assertEquals("RIFF", part.inputStream().use { String(it.readNBytes(4), Charsets.US_ASCII) })
            }
            assertEquals(2, sizes.size)
            assertEquals(pcm.capacity().toLong(), sizes.sum())
            assertEquals(0L, offsets[0])
            assertEquals(sizes[0] / 32, offsets[1])
            assertTrue("cut at ${offsets[1]} ms", offsets[1] in 115_000L..115_400L)
            assertEquals(125_000L, LectureAudio.durationMs(source))
            assertEquals(listOf(source.name), directory.listFiles()!!.map(File::getName))
        } finally {
            directory.listFiles()?.forEach(File::delete)
            directory.delete()
        }
    }

    @Test fun rejectsIncompleteRecording() {
        val source = File.createTempFile("lecture-broken-", ".wav")
        try {
            source.writeBytes(header(0))
            assertThrows(IllegalArgumentException::class.java) {
                LectureAudio.forEachChunk(source, source.parentFile!!) { _, _, _, _ -> }
            }
            assertFalse(source.length() > 44)
        } finally {
            source.delete()
        }
    }

    private fun header(pcmBytes: Int) = ByteBuffer.allocate(44).order(ByteOrder.LITTLE_ENDIAN).apply {
        put("RIFF".toByteArray())
        putInt(36 + pcmBytes)
        put("WAVEfmt ".toByteArray())
        putInt(16)
        putShort(1)
        putShort(1)
        putInt(16_000)
        putInt(32_000)
        putShort(2)
        putShort(16)
        put("data".toByteArray())
        putInt(pcmBytes)
    }.array()
}
