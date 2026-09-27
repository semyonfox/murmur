package dev.local.murmur

import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertThrows
import org.junit.Test
import java.io.File
import java.io.RandomAccessFile
import java.nio.ByteBuffer
import java.nio.ByteOrder
import java.nio.file.Files

class LectureAudioTest {
    @Test fun splitsRecordingIntoBoundedWavRequestsAndDeletesTemporaryParts() {
        val directory = Files.createTempDirectory("lecture-audio-test").toFile()
        try {
            val source = File(directory, "lecture-source.wav")
            val pcmBytes = 16_000 * 2 * 121
            RandomAccessFile(source, "rw").use { output ->
                output.write(header(pcmBytes))
                output.setLength(44L + pcmBytes)
            }
            val sizes = mutableListOf<Long>()
            LectureAudio.forEachChunk(source, directory) { part, index, total ->
                assertEquals(2, total)
                assertEquals(sizes.size, index)
                sizes.add(part.length())
                assertEquals("RIFF", part.inputStream().use { String(it.readNBytes(4), Charsets.US_ASCII) })
            }
            assertEquals(listOf(44L + 16_000 * 2 * 120, 44L + 16_000 * 2), sizes)
            assertEquals(121_000L, LectureAudio.durationMs(source))
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
                LectureAudio.forEachChunk(source, source.parentFile!!) { _, _, _ -> }
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
