package dev.local.murmur

import android.content.Context
import android.media.AudioFormat
import android.media.MediaCodec
import android.media.MediaExtractor
import android.media.MediaFormat
import android.net.Uri
import java.io.File
import java.io.RandomAccessFile
import java.nio.ByteBuffer
import java.nio.ByteOrder
import kotlin.math.roundToInt

internal object LectureAudio {
    private const val SAMPLE_RATE = 16_000
    private const val BYTES_PER_SECOND = SAMPLE_RATE * 2
    private const val HEADER_BYTES = 44
    private const val CHUNK_BYTES = BYTES_PER_SECOND * 120
    private const val MAX_SAMPLES = SAMPLE_RATE * 28_800L

    fun durationMs(file: File): Long = ((file.length() - HEADER_BYTES).coerceAtLeast(0) * 1000L) / BYTES_PER_SECOND

    fun forEachChunk(file: File, cacheDir: File, consume: (File, Int, Int) -> Unit) {
        RandomAccessFile(file, "r").use { source ->
            val header = ByteArray(HEADER_BYTES)
            source.readFully(header)
            require(String(header, 0, 4, Charsets.US_ASCII) == "RIFF" &&
                String(header, 8, 4, Charsets.US_ASCII) == "WAVE" &&
                String(header, 36, 4, Charsets.US_ASCII) == "data") { "Recording is not a supported WAV file." }
            val spec = ByteBuffer.wrap(header).order(ByteOrder.LITTLE_ENDIAN)
            require(spec.getShort(22).toInt() == 1 && spec.getInt(24) == SAMPLE_RATE &&
                spec.getShort(34).toInt() == 16) { "Recording is not 16 kHz mono audio." }
            val available = source.length() - HEADER_BYTES
            require(available > 0 && available % 2L == 0L) { "Recording is empty or incomplete." }
            val total = ((available + CHUNK_BYTES - 1) / CHUNK_BYTES).toInt()
            var remaining = available
            for (index in 0 until total) {
                check(!Thread.currentThread().isInterrupted) { "Transcription stopped." }
                val chunk = File.createTempFile("murmur-lecture-part-", ".wav", cacheDir)
                try {
                    WavSink(chunk).use { sink ->
                        val buffer = ByteArray(16 * 1024)
                        var left = minOf(remaining, CHUNK_BYTES.toLong())
                        while (left > 0) {
                            val count = minOf(buffer.size.toLong(), left).toInt()
                            source.readFully(buffer, 0, count)
                            sink.writePcm(buffer, count)
                            left -= count
                        }
                    }
                    consume(chunk, index, total)
                } finally {
                    chunk.delete()
                }
                remaining -= minOf(remaining, CHUNK_BYTES.toLong())
            }
        }
    }

    fun import(context: Context, uri: Uri, destination: File) {
        val extractor = MediaExtractor()
        var codec: MediaCodec? = null
        try {
            extractor.setDataSource(context, uri, null)
            val track = (0 until extractor.trackCount).firstOrNull { index ->
                extractor.getTrackFormat(index).getString(MediaFormat.KEY_MIME)?.startsWith("audio/") == true
            } ?: error("Choose a supported audio file.")
            extractor.selectTrack(track)
            val format = extractor.getTrackFormat(track)
            val mime = format.getString(MediaFormat.KEY_MIME) ?: error("Audio format is missing.")
            codec = MediaCodec.createDecoderByType(mime)
            codec.configure(format, null, null, 0)
            codec.start()
            WavSink(destination).use { sink ->
                val info = MediaCodec.BufferInfo()
                var inputEnded = false
                var outputEnded = false
                var resampler: MonoResampler? = null
                var channels = 0
                var encoding = AudioFormat.ENCODING_PCM_16BIT
                while (!outputEnded) {
                    check(!Thread.currentThread().isInterrupted) { "Audio import stopped." }
                    if (!inputEnded) {
                        val inputIndex = codec.dequeueInputBuffer(10_000)
                        if (inputIndex >= 0) {
                            val input = codec.getInputBuffer(inputIndex) ?: error("Audio decoder has no input buffer.")
                            input.clear()
                            val size = extractor.readSampleData(input, 0)
                            if (size < 0) {
                                codec.queueInputBuffer(inputIndex, 0, 0, 0, MediaCodec.BUFFER_FLAG_END_OF_STREAM)
                                inputEnded = true
                            } else {
                                codec.queueInputBuffer(inputIndex, 0, size, extractor.sampleTime, 0)
                                extractor.advance()
                            }
                        }
                    }
                    when (val outputIndex = codec.dequeueOutputBuffer(info, 10_000)) {
                        MediaCodec.INFO_OUTPUT_FORMAT_CHANGED -> {
                            val outputFormat = codec.outputFormat
                            channels = outputFormat.getInteger(MediaFormat.KEY_CHANNEL_COUNT)
                            val rate = outputFormat.getInteger(MediaFormat.KEY_SAMPLE_RATE)
                            require(channels in 1..8 && rate in 8_000..192_000) { "Unsupported audio channels or sample rate." }
                            encoding = if (outputFormat.containsKey(MediaFormat.KEY_PCM_ENCODING))
                                outputFormat.getInteger(MediaFormat.KEY_PCM_ENCODING) else AudioFormat.ENCODING_PCM_16BIT
                            require(encoding == AudioFormat.ENCODING_PCM_16BIT || encoding == AudioFormat.ENCODING_PCM_FLOAT) {
                                "Unsupported decoded audio format."
                            }
                            resampler = MonoResampler(rate) { sink.writeSample(it) }
                        }
                        in 0..Int.MAX_VALUE -> {
                            val output = codec.getOutputBuffer(outputIndex) ?: error("Audio decoder has no output buffer.")
                            output.order(ByteOrder.LITTLE_ENDIAN)
                            output.position(info.offset)
                            output.limit(info.offset + info.size)
                            if (channels == 0) {
                                val outputFormat = codec.outputFormat
                                channels = outputFormat.getInteger(MediaFormat.KEY_CHANNEL_COUNT)
                                val rate = outputFormat.getInteger(MediaFormat.KEY_SAMPLE_RATE)
                                require(channels in 1..8 && rate in 8_000..192_000) { "Unsupported audio channels or sample rate." }
                                encoding = if (outputFormat.containsKey(MediaFormat.KEY_PCM_ENCODING))
                                    outputFormat.getInteger(MediaFormat.KEY_PCM_ENCODING) else AudioFormat.ENCODING_PCM_16BIT
                                require(encoding == AudioFormat.ENCODING_PCM_16BIT || encoding == AudioFormat.ENCODING_PCM_FLOAT) {
                                    "Unsupported decoded audio format."
                                }
                                resampler = MonoResampler(rate) { sink.writeSample(it) }
                            }
                            val bytesPerSample = if (encoding == AudioFormat.ENCODING_PCM_FLOAT) 4 else 2
                            require(output.remaining() % (channels * bytesPerSample) == 0) { "Audio decoder returned partial samples." }
                            while (output.hasRemaining()) {
                                var mono = 0f
                                repeat(channels) {
                                    mono += if (encoding == AudioFormat.ENCODING_PCM_FLOAT) output.float
                                        else output.short / 32768f
                                }
                                resampler!!.push(mono / channels)
                            }
                            outputEnded = info.flags and MediaCodec.BUFFER_FLAG_END_OF_STREAM != 0
                            codec.releaseOutputBuffer(outputIndex, false)
                        }
                    }
                }
                require(sink.samples > 0) { "Audio file contains no samples." }
            }
        } catch (error: Exception) {
            destination.delete()
            throw error
        } finally {
            runCatching { codec?.stop() }
            codec?.release()
            extractor.release()
        }
    }

    private class MonoResampler(private val inputRate: Int, private val emit: (Float) -> Unit) {
        private var previous = 0f
        private var inputIndex = 0L
        private var nextOutput = 0.0

        fun push(sample: Float) {
            if (inputIndex == 0L) {
                emit(sample)
                nextOutput = inputRate.toDouble() / SAMPLE_RATE
            } else {
                while (nextOutput <= inputIndex) {
                    val fraction = (nextOutput - (inputIndex - 1)).toFloat().coerceIn(0f, 1f)
                    emit(previous + (sample - previous) * fraction)
                    nextOutput += inputRate.toDouble() / SAMPLE_RATE
                }
            }
            previous = sample
            inputIndex++
        }
    }

    private class WavSink(file: File) : AutoCloseable {
        private val output = RandomAccessFile(file, "rw")
        private val buffer = ByteArray(16 * 1024)
        private var used = 0
        var samples = 0L
            private set

        init {
            output.setLength(0)
            output.write(ByteArray(HEADER_BYTES))
        }

        fun writeSample(sample: Float) {
            require(samples < MAX_SAMPLES) { "Audio file is longer than eight hours." }
            val value = (sample.coerceIn(-1f, 1f) * 32767f).roundToInt().toShort().toInt()
            buffer[used++] = value.toByte()
            buffer[used++] = (value ushr 8).toByte()
            samples++
            if (used == buffer.size) flush()
        }

        fun writePcm(bytes: ByteArray, count: Int) {
            require(count % 2 == 0)
            flush()
            output.write(bytes, 0, count)
            samples += count / 2
        }

        private fun flush() {
            if (used > 0) {
                output.write(buffer, 0, used)
                used = 0
            }
        }

        override fun close() {
            try {
                flush()
                val pcmBytes = samples * 2
                require(pcmBytes <= Int.MAX_VALUE - 36) { "Audio file is too long." }
                output.seek(0)
                output.write(wavHeader(pcmBytes.toInt()))
            } finally {
                output.close()
            }
        }
    }

    private fun wavHeader(pcmBytes: Int): ByteArray = ByteBuffer.allocate(HEADER_BYTES).order(ByteOrder.LITTLE_ENDIAN).apply {
        put("RIFF".toByteArray(Charsets.US_ASCII))
        putInt(36 + pcmBytes)
        put("WAVEfmt ".toByteArray(Charsets.US_ASCII))
        putInt(16)
        putShort(1)
        putShort(1)
        putInt(SAMPLE_RATE)
        putInt(BYTES_PER_SECOND)
        putShort(2)
        putShort(16)
        put("data".toByteArray(Charsets.US_ASCII))
        putInt(pcmBytes)
    }.array()
}
