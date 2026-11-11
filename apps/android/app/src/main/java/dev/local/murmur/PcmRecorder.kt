package dev.local.murmur

import android.Manifest
import android.content.Context
import android.content.pm.PackageManager
import android.media.AudioFormat
import android.media.AudioRecord
import android.media.MediaRecorder
import android.media.audiofx.NoiseSuppressor
import android.os.Handler
import android.os.Looper
import java.io.File
import java.io.RandomAccessFile
import java.nio.ByteBuffer
import java.nio.ByteOrder
import java.util.concurrent.atomic.AtomicBoolean
import kotlin.math.max

internal class PcmRecorder private constructor(
    val file: File,
    private val recorder: AudioRecord,
    private val noiseSuppressor: NoiseSuppressor?,
    private val maxPcmBytes: Int,
    private val preserveOnFailure: Boolean,
    private val onChunk: ((File) -> Unit)?,
    private val chunkMinSeconds: Int,
    private val onLevel: ((level: Float, recordedMs: Long) -> Unit)?,
    private val onComplete: (Result<File>) -> Unit,
) {
    private val mainHandler = Handler(Looper.getMainLooper())
    private val running = AtomicBoolean(true)
    private val discard = AtomicBoolean(false)
    private val paused = AtomicBoolean(false)

    val isPaused: Boolean get() = paused.get()

    // the microphone keeps running while paused so resuming is instant, the audio is just dropped
    fun pause() = paused.set(true)

    fun resume() = paused.set(false)

    fun stop() {
        if (running.getAndSet(false)) {
            try {
                recorder.stop()
            } catch (_: IllegalStateException) {
                // the capture loop may already have stopped at its size limit
            }
        }
    }

    fun cancel() {
        discard.set(true)
        stop()
    }

    private fun runCapture(bufferSize: Int) {
        val result = runCatching {
            var pcmBytes = 0
            var peak = 0
            val chunker = onChunk?.let { PauseChunker(chunkMinSeconds) }
            var chunks = 0
            RandomAccessFile(file, "rw").use { output ->
                output.setLength(0)
                output.write(ByteArray(WAV_HEADER_BYTES))
                val buffer = ByteArray(bufferSize)
                // small reads give the level meter about 25 updates a second
                val readSize = if (onLevel != null) minOf(bufferSize, LEVEL_READ_BYTES) else bufferSize
                var checkpointAt = System.currentTimeMillis() + 5_000L
                try {
                    while (running.get() && pcmBytes < maxPcmBytes) {
                        val count = recorder.read(
                            buffer,
                            0,
                            minOf(readSize, maxPcmBytes - pcmBytes),
                            AudioRecord.READ_BLOCKING,
                        )
                        if (count < 0 && running.get()) error("Microphone capture failed.")
                        if (count <= 0) continue
                        if (paused.get()) {
                            onLevel?.invoke(0f, pcmBytes * 1000L / (SAMPLE_RATE * BYTES_PER_SAMPLE))
                            continue
                        }
                        output.write(buffer, 0, count)
                        if (peak < SILENT_PEAK) peak = maxOf(peak, peakOf(buffer, count))
                        chunker?.add(buffer, count)?.forEach { piece ->
                            emitChunk(piece)
                            chunks++
                        }
                        pcmBytes += count
                        onLevel?.invoke(levelOf(buffer, count), pcmBytes * 1000L / (SAMPLE_RATE * BYTES_PER_SAMPLE))
                        if (preserveOnFailure && System.currentTimeMillis() >= checkpointAt) {
                            val position = output.filePointer
                            output.seek(0)
                            output.write(wavHeader(pcmBytes))
                            output.seek(position)
                            checkpointAt = System.currentTimeMillis() + 5_000L
                        }
                    }
                } finally {
                    if (pcmBytes > 0) {
                        output.seek(0)
                        output.write(wavHeader(pcmBytes))
                    }
                }
                if (pcmBytes < MIN_PCM_BYTES) error("Record at least half a second of speech.")
                // android feeds silenced zeros instead of failing when it blocks a
                // background app's microphone, so catch that before uploading it
                if (!preserveOnFailure && peak < SILENT_PEAK) {
                    error("Microphone was blocked. Open Murmur and turn voice on again.")
                }
                // once pieces went out, the consumer joins them and needs the tail too
                if (chunks > 0) chunker?.finish()?.let(::emitChunk)
            }
            file
        }
        running.set(false)
        try {
            if (recorder.recordingState == AudioRecord.RECORDSTATE_RECORDING) recorder.stop()
        } catch (_: IllegalStateException) {
            // stop may have raced the user releasing the microphone button
        }
        recorder.release()
        noiseSuppressor?.release()
        mainHandler.post {
            if (discard.get()) file.delete()
            else {
                if (result.isFailure && !preserveOnFailure) file.delete()
                onComplete(result)
            }
        }
    }

    private fun emitChunk(pcm: ByteArray) {
        val chunk = pcmToWav(pcm, file.parentFile ?: return)
        mainHandler.post {
            if (discard.get()) chunk.delete() else onChunk?.invoke(chunk) ?: chunk.delete()
        }
    }

    companion object {
        private const val SAMPLE_RATE = 16_000
        private const val BYTES_PER_SAMPLE = 2
        private const val CHANNELS = 1
        private const val WAV_HEADER_BYTES = 44
        private const val DEFAULT_MAX_SECONDS = 120
        private const val MIN_PCM_BYTES = SAMPLE_RATE * BYTES_PER_SAMPLE / 2
        // a working microphone always has some noise floor, blocked capture is near zero
        private const val SILENT_PEAK = 8
        private const val LEVEL_READ_BYTES = SAMPLE_RATE * BYTES_PER_SAMPLE / 25

        // rms on a 60 dB scale, which tracks how loud speech sounds better than raw amplitude
        internal fun levelOf(buffer: ByteArray, count: Int): Float {
            var sum = 0.0
            var samples = 0
            var index = 0
            while (index + 1 < count) {
                val sample = ((buffer[index].toInt() and 0xff) or (buffer[index + 1].toInt() shl 8)).toShort().toDouble()
                sum += sample * sample
                samples++
                index += 2
            }
            if (samples == 0) return 0f
            val rms = kotlin.math.sqrt(sum / samples) / 32768.0
            if (rms <= 0.0) return 0f
            return ((20 * kotlin.math.log10(rms) + 60) / 60).toFloat().coerceIn(0f, 1f)
        }

        private fun peakOf(buffer: ByteArray, count: Int): Int {
            var peak = 0
            var index = 0
            while (index + 1 < count) {
                val sample = (buffer[index].toInt() and 0xff) or (buffer[index + 1].toInt() shl 8)
                peak = maxOf(peak, kotlin.math.abs(sample))
                index += 2
            }
            return peak
        }

        fun start(
            context: Context,
            cacheDir: File,
            suppressNoise: Boolean,
            maxSeconds: Int = DEFAULT_MAX_SECONDS,
            preserveOnFailure: Boolean = false,
            onChunk: ((File) -> Unit)? = null,
            chunkMinSeconds: Int = 3,
            onLevel: ((level: Float, recordedMs: Long) -> Unit)? = null,
            onComplete: (Result<File>) -> Unit,
        ): PcmRecorder {
            require(maxSeconds in 1..28_800)
            val maxPcmBytes = SAMPLE_RATE * BYTES_PER_SAMPLE * maxSeconds
            if (context.checkSelfPermission(Manifest.permission.RECORD_AUDIO) != PackageManager.PERMISSION_GRANTED) {
                throw SecurityException("Microphone permission is not granted.")
            }
            val minimum = AudioRecord.getMinBufferSize(
                SAMPLE_RATE,
                AudioFormat.CHANNEL_IN_MONO,
                AudioFormat.ENCODING_PCM_16BIT,
            )
            require(minimum > 0) { "This device cannot record 16 kHz mono audio." }
            val bufferSize = max(minimum * 2, 4096)
            cacheDir.mkdirs()
            val file = File.createTempFile(if (preserveOnFailure) "lecture-" else "murmur-capture-", ".wav", cacheDir)
            val recorder = try {
                AudioRecord.Builder()
                    .setAudioSource(MediaRecorder.AudioSource.VOICE_RECOGNITION)
                    .setAudioFormat(
                        AudioFormat.Builder()
                            .setSampleRate(SAMPLE_RATE)
                            .setChannelMask(AudioFormat.CHANNEL_IN_MONO)
                            .setEncoding(AudioFormat.ENCODING_PCM_16BIT)
                            .build(),
                    )
                    .setBufferSizeInBytes(bufferSize)
                    .build()
            } catch (error: Exception) {
                file.delete()
                throw error
            }
            if (recorder.state != AudioRecord.STATE_INITIALIZED) {
                recorder.release()
                file.delete()
                error("Microphone could not start.")
            }
            val noiseSuppressor = try {
                if (suppressNoise && NoiseSuppressor.isAvailable()) {
                    NoiseSuppressor.create(recorder.audioSessionId)?.apply { enabled = true }
                } else null
            } catch (error: Exception) {
                recorder.release()
                file.delete()
                throw error
            }
            try {
                recorder.startRecording()
            } catch (error: Exception) {
                noiseSuppressor?.release()
                recorder.release()
                file.delete()
                throw error
            }
            if (recorder.recordingState != AudioRecord.RECORDSTATE_RECORDING) {
                noiseSuppressor?.release()
                recorder.release()
                file.delete()
                error("Microphone did not start recording.")
            }
            return PcmRecorder(file, recorder, noiseSuppressor, maxPcmBytes, preserveOnFailure, onChunk, chunkMinSeconds, onLevel, onComplete).also { capture ->
                Thread({ capture.runCapture(bufferSize) }, "murmur-audio").start()
            }
        }

        fun wavHeader(pcmBytes: Int): ByteArray {
            return ByteBuffer.allocate(WAV_HEADER_BYTES).order(ByteOrder.LITTLE_ENDIAN).apply {
                put("RIFF".toByteArray(Charsets.US_ASCII))
                putInt(36 + pcmBytes)
                put("WAVEfmt ".toByteArray(Charsets.US_ASCII))
                putInt(16)
                putShort(1)
                putShort(CHANNELS.toShort())
                putInt(SAMPLE_RATE)
                putInt(SAMPLE_RATE * BYTES_PER_SAMPLE * CHANNELS)
                putShort((BYTES_PER_SAMPLE * CHANNELS).toShort())
                putShort((BYTES_PER_SAMPLE * 8).toShort())
                put("data".toByteArray(Charsets.US_ASCII))
                putInt(pcmBytes)
            }.array()
        }
    }
}
