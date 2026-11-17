package dev.local.murmur

import android.media.MediaCodec
import android.media.MediaCodecInfo
import android.media.MediaFormat
import android.media.MediaMuxer
import java.io.File
import java.io.IOException
import java.io.RandomAccessFile
import java.util.Locale

internal object LectureExport {
    private const val SAMPLE_RATE = 16_000
    private const val HEADER_BYTES = 44
    // speech at 48 kbps aac is about 21 MB an hour, against 115 MB for the saved wav
    private const val BIT_RATE = 48_000
    private const val TIMEOUT_US = 10_000L

    // encodes a lecture's 16 kHz mono wav to a local m4a file, which the caller then
    // copies to wherever the user picked or shares
    fun writeM4a(wav: File, output: File) {
        val format = MediaFormat.createAudioFormat(MediaFormat.MIMETYPE_AUDIO_AAC, SAMPLE_RATE, 1).apply {
            setInteger(MediaFormat.KEY_AAC_PROFILE, MediaCodecInfo.CodecProfileLevel.AACObjectLC)
            setInteger(MediaFormat.KEY_BIT_RATE, BIT_RATE)
            setInteger(MediaFormat.KEY_MAX_INPUT_SIZE, 16 * 1024)
        }
        val codec = MediaCodec.createEncoderByType(MediaFormat.MIMETYPE_AUDIO_AAC)
        var muxer: MediaMuxer? = null
        var started = false
        try {
            codec.configure(format, null, null, MediaCodec.CONFIGURE_FLAG_ENCODE)
            codec.start()
            muxer = MediaMuxer(output.absolutePath, MediaMuxer.OutputFormat.MUXER_OUTPUT_MPEG_4)
            var track = -1
            val info = MediaCodec.BufferInfo()
            RandomAccessFile(wav, "r").use { source ->
                if (source.length() < HEADER_BYTES) throw IOException("Recording is empty.")
                source.seek(HEADER_BYTES.toLong())
                var samples = 0L
                var inputDone = false
                var outputDone = false
                val chunk = ByteArray(8 * 1024)
                while (!outputDone) {
                    if (Thread.currentThread().isInterrupted) throw IOException("Export cancelled.")
                    if (!inputDone) {
                        val index = codec.dequeueInputBuffer(TIMEOUT_US)
                        if (index >= 0) {
                            val buffer = codec.getInputBuffer(index) ?: throw IOException("Encoder gave no buffer.")
                            val read = source.read(chunk, 0, minOf(chunk.size, buffer.remaining()) and 1.inv())
                            val timeUs = samples * 1_000_000L / SAMPLE_RATE
                            if (read <= 0) {
                                codec.queueInputBuffer(index, 0, 0, timeUs, MediaCodec.BUFFER_FLAG_END_OF_STREAM)
                                inputDone = true
                            } else {
                                buffer.put(chunk, 0, read)
                                codec.queueInputBuffer(index, 0, read, timeUs, 0)
                                samples += read / 2
                            }
                        }
                    }
                    val index = codec.dequeueOutputBuffer(info, TIMEOUT_US)
                    when {
                        index == MediaCodec.INFO_OUTPUT_FORMAT_CHANGED -> {
                            track = muxer.addTrack(codec.outputFormat)
                            muxer.start()
                            started = true
                        }
                        index >= 0 -> {
                            val buffer = codec.getOutputBuffer(index) ?: throw IOException("Encoder gave no output.")
                            // the codec config travels in the track format, not as a sample
                            if (info.size > 0 && info.flags and MediaCodec.BUFFER_FLAG_CODEC_CONFIG == 0) {
                                check(started) { "Encoder produced audio before its format." }
                                buffer.position(info.offset)
                                buffer.limit(info.offset + info.size)
                                muxer.writeSampleData(track, buffer, info)
                            }
                            codec.releaseOutputBuffer(index, false)
                            if (info.flags and MediaCodec.BUFFER_FLAG_END_OF_STREAM != 0) outputDone = true
                        }
                    }
                }
            }
        } catch (error: Exception) {
            output.delete()
            throw if (error is IOException) error else IOException("Audio could not be converted.", error)
        } finally {
            runCatching { codec.stop() }
            codec.release()
            if (started) runCatching { muxer?.stop() }
            runCatching { muxer?.release() }
        }
    }

    // one subtitle per transcribed part, ending where the next starts or at the end
    // of the recording. text with no stamps becomes one subtitle for the whole lecture
    fun srt(text: String, durationMs: Long): String {
        val segments = LectureTranscript.parse(text).filter { it.text.isNotBlank() }
        return segments.mapIndexed { index, segment ->
            val start = segment.startMs ?: 0L
            val next = segments.getOrNull(index + 1)?.startMs
            val end = maxOf(start + 1_000L, next ?: durationMs.takeIf { it > start } ?: (start + 5_000L))
            "${index + 1}\n${timecode(start)} --> ${timecode(end)}\n${segment.text}\n"
        }.joinToString("\n")
    }

    private fun timecode(ms: Long): String = String.format(Locale.ROOT, "%02d:%02d:%02d,%03d",
        ms / 3_600_000, ms / 60_000 % 60, ms / 1_000 % 60, ms % 1_000)

    // a file name from the lecture title that every file manager accepts
    fun fileName(title: String, extension: String): String =
        title.replace(Regex("""[\\/:*?"<>|\n\r\t]+"""), " ").trim().take(80).ifBlank { "Lecture" } + ".$extension"
}
