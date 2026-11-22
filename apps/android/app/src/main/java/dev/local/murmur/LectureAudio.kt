package dev.local.murmur

import android.content.Context
import android.media.AudioFormat
import android.media.MediaCodec
import android.media.MediaExtractor
import android.media.MediaFormat
import android.net.Uri
import android.os.CancellationSignal
import android.os.StatFs
import java.io.File
import java.io.RandomAccessFile
import java.nio.ByteBuffer
import java.nio.ByteOrder
import java.util.concurrent.ExecutionException
import java.util.concurrent.FutureTask
import java.util.concurrent.Semaphore
import java.util.concurrent.TimeUnit
import java.util.concurrent.TimeoutException
import java.util.concurrent.atomic.AtomicLong
import kotlin.concurrent.thread
import kotlin.math.roundToInt

internal object LectureAudio {
    private const val SAMPLE_RATE = 16_000
    private const val BYTES_PER_SECOND = SAMPLE_RATE * 2
    private const val HEADER_BYTES = 44
    private const val CHUNK_BYTES = BYTES_PER_SECOND * 120
    private const val FRAME_BYTES = 640
    private const val SEARCH_BYTES = BYTES_PER_SECOND * 10
    private const val MAX_SAMPLES = SAMPLE_RATE * 28_800L
    private const val IMPORT_RESERVE_BYTES = 256L * 1024 * 1024
    private val importOpenTimeoutNs = TimeUnit.MINUTES.toNanos(10)
    private const val IMPORT_STALL_NS = 30_000_000_000L
    // a provider can block both the copy and the cancellation call
    private val stagingSlots = Semaphore(2)

    private val recordingName = Regex("lecture-[A-Za-z0-9-]+\\.wav")

    fun isRecordingName(name: String): Boolean = recordingName.matches(name)

    fun unlistedRecordings(directory: File, listedNames: Set<String>): List<File> =
        directory.listFiles()?.filter { it.isFile && isRecordingName(it.name) && it.name !in listedNames }.orEmpty()

    fun removeTemporaryRecordings(lectureDirectory: File, cacheDirectory: File) {
        val liveChunk = Regex("murmur-chunk-[A-Za-z0-9-]+\\.wav")
        val cachedAudio = Regex("murmur-((capture|chunk|lecture-part|lecture-import)-[A-Za-z0-9-]+\\.wav|lecture-source-[A-Za-z0-9-]+\\.audio)")
        lectureDirectory.listFiles()?.filter { it.isFile && it.name.matches(liveChunk) }?.forEach { it.delete() }
        cacheDirectory.listFiles()?.filter { it.isFile && it.name.matches(cachedAudio) }?.forEach { it.delete() }
    }

    fun durationMs(file: File): Long = ((file.length() - HEADER_BYTES).coerceAtLeast(0) * 1000L) / BYTES_PER_SECOND

    // interrupted captures can have a zero or stale header, but their PCM is usable
    fun recoverRecording(file: File): Unit = RandomAccessFile(file, "rw").use { source ->
        val available = source.length() - HEADER_BYTES
        val pcmBytes = available - available % 2
        require(pcmBytes in 2..MAX_SAMPLES * 2) { "Recording is empty or incomplete." }
        val header = ByteArray(HEADER_BYTES)
        source.readFully(header)
        val expected = PcmRecorder.wavHeader(pcmBytes.toInt())
        require(header.all { it == 0.toByte() } || header.indices.all { index ->
            index in 4..7 || index in 40..43 || header[index] == expected[index]
        }) { "Recording is not a supported WAV file." }
        if (!header.contentEquals(expected)) {
            source.seek(0)
            source.write(expected)
        }
        if (available != pcmBytes) source.setLength(HEADER_BYTES + pcmBytes)
    }

    // parts of about two minutes. each one ends at the quietest moment of its last
    // ten seconds, so a word is not split between two requests
    fun forEachChunk(file: File, cacheDir: File, consume: (chunk: File, index: Int, total: Int, offsetMs: Long) -> Unit) {
        recoverRecording(file)
        RandomAccessFile(file, "r").use { source ->
            val available = source.length() - HEADER_BYTES
            val window = ByteArray(CHUNK_BYTES)
            var position = 0L
            var index = 0
            while (position < available) {
                check(!Thread.currentThread().isInterrupted) { "Transcription stopped." }
                val left = available - position
                val read = minOf(left, CHUNK_BYTES.toLong()).toInt()
                source.seek(HEADER_BYTES + position)
                source.readFully(window, 0, read)
                val length = if (left > CHUNK_BYTES) quietestCut(window) else read
                val total = index + 1 + ((left - length + CHUNK_BYTES - 1) / CHUNK_BYTES).toInt()
                val chunk = File.createTempFile("murmur-lecture-part-", ".wav", cacheDir)
                try {
                    WavSink(chunk).use { it.writePcm(window, length) }
                    consume(chunk, index, total, position * 1000L / BYTES_PER_SECOND)
                } finally {
                    chunk.delete()
                }
                position += length
                index++
            }
        }
    }

    // the middle of the quietest 20 ms in the last ten seconds of a full window
    private fun quietestCut(window: ByteArray): Int {
        var cut = window.size
        var quietest = Long.MAX_VALUE
        var frame = window.size - FRAME_BYTES
        while (frame >= window.size - SEARCH_BYTES) {
            var energy = 0L
            var index = frame
            while (index < frame + FRAME_BYTES) {
                val sample = (window[index].toInt() and 0xff) or (window[index + 1].toInt() shl 8)
                energy += sample.toLong() * sample
                index += 2
            }
            if (energy < quietest) {
                quietest = energy
                cut = frame + FRAME_BYTES / 2
            }
            frame -= FRAME_BYTES
        }
        return cut
    }

    fun import(context: Context, uri: Uri, destination: File, signal: CancellationSignal) {
        val source = File.createTempFile("murmur-lecture-source-", ".audio", context.cacheDir)
        try {
            stageImport(context, uri, source, signal)
            signal.throwIfCanceled()
            decodeImport(source, destination, signal)
        } finally {
            source.delete()
        }
    }

    private fun stageImport(context: Context, uri: Uri, source: File, importSignal: CancellationSignal) {
        val signal = CancellationSignal()
        val deadline = AtomicLong(System.nanoTime() + importOpenTimeoutNs)
        val cacheDir = context.cacheDir
        source.outputStream().use { output ->
            val copy = FutureTask {
                val asset = context.contentResolver.openAssetFileDescriptor(uri, "r", signal)
                    ?: error("Could not open the selected audio file.")
                asset.use {
                    deadline.set(System.nanoTime() + IMPORT_STALL_NS)
                    signal.setOnCancelListener { runCatching { asset.close() } }
                    signal.throwIfCanceled()
                    val length = asset.length
                    if (length >= 0) requireImportSpace(cacheDir, length)
                    asset.createInputStream().use { input ->
                        val buffer = ByteArray(256 * 1024)
                        while (true) {
                            signal.throwIfCanceled()
                            val count = input.read(buffer)
                            if (count < 0) break
                            if (count == 0) continue
                            requireImportSpace(cacheDir, count.toLong())
                            output.write(buffer, 0, count)
                            deadline.set(System.nanoTime() + IMPORT_STALL_NS)
                        }
                    }
                }
            }
            val worker = thread(start = false, isDaemon = true, name = "murmur-import-source") {
                try {
                    copy.run()
                } finally {
                    stagingSlots.release()
                }
            }
            val canceller = thread(start = false, isDaemon = true, name = "murmur-import-cancel") {
                try {
                    signal.cancel()
                } finally {
                    stagingSlots.release()
                }
            }
            importSignal.throwIfCanceled()
            check(stagingSlots.tryAcquire(2)) { "The previous audio provider is still busy. Try again later." }
            try {
                worker.start()
                while (true) {
                    importSignal.throwIfCanceled()
                    val remaining = deadline.get() - System.nanoTime()
                    check(remaining > 0 || copy.isDone) { "The audio provider stopped responding." }
                    try {
                        copy.get(remaining.coerceIn(0, TimeUnit.SECONDS.toNanos(1)), TimeUnit.NANOSECONDS)
                        break
                    } catch (_: TimeoutException) {
                        // recheck cancellation and the deadline after each wait
                    } catch (error: ExecutionException) {
                        throw error.cause ?: error
                    }
                }
            } finally {
                try {
                    if (worker.state == Thread.State.NEW) stagingSlots.release()
                    else if (copy.cancel(true)) canceller.start()
                    else worker.join()
                } finally {
                    if (canceller.state == Thread.State.NEW) stagingSlots.release()
                }
            }
        }
    }

    private fun requireImportSpace(cacheDir: File, bytes: Long) {
        require(bytes <= StatFs(cacheDir.path).availableBytes - IMPORT_RESERVE_BYTES) {
            "Not enough free storage to import this audio file."
        }
    }

    private fun decodeImport(source: File, destination: File, signal: CancellationSignal) {
        val extractor = MediaExtractor()
        var codec: MediaCodec? = null
        try {
            source.inputStream().use { extractor.setDataSource(it.fd) }
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
                var lastOutput = System.nanoTime()
                while (!outputEnded) {
                    signal.throwIfCanceled()
                    check(System.nanoTime() - lastOutput < IMPORT_STALL_NS) { "Audio decoder stopped producing output." }
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
                            if (info.size > 0) lastOutput = System.nanoTime()
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
