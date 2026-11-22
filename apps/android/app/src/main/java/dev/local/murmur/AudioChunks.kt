package dev.local.murmur

import java.io.File
import java.io.IOException
import java.util.concurrent.Callable
import java.util.concurrent.ExecutionException
import java.util.concurrent.ExecutorService
import java.util.concurrent.Future
import kotlin.math.ln
import kotlin.math.pow
import kotlin.math.sqrt

private const val FRAME_SAMPLES = 320
private const val FRAME_BYTES = FRAME_SAMPLES * 2
private const val BYTES_PER_SECOND = 32_000

// splits audio at the quietest 20 ms frame before each limit, so a piece only cuts
// through a word when the speaker never pauses within the search window
internal fun splitAtPauses(samples: FloatArray, maxSamples: Int, searchSamples: Int): List<IntRange> {
    val pieces = mutableListOf<IntRange>()
    var start = 0
    while (samples.size - start > maxSamples) {
        val limit = start + maxSamples
        var cut = limit
        var quietest = Double.MAX_VALUE
        var frame = limit - FRAME_SAMPLES
        val earliest = maxOf(start + FRAME_SAMPLES, limit - searchSamples)
        while (frame >= earliest) {
            var energy = 0.0
            for (index in frame until frame + FRAME_SAMPLES) energy += samples[index] * samples[index]
            if (energy < quietest) {
                quietest = energy
                cut = frame + FRAME_SAMPLES / 2
            }
            frame -= FRAME_SAMPLES
        }
        pieces += start until cut
        start = cut
    }
    if (start < samples.size) pieces += start until samples.size
    return pieces
}

// a cut piece of the recording and where it started, so a dropped silent piece
// does not shift the timestamps of the pieces after it
internal class AudioPiece(val pcm: ByteArray, val offsetBytes: Long)

// frames quieter than this are a pause wherever they fall, on a 16 bit scale
private const val QUIET_LEVEL = 60
// a piece needs this much sound above the pause level to be worth transcribing
private const val SPEECH_FRAMES = 10
// how long a level must last to count as the speaker's, so a slammed door or a
// bumped phone does not make ordinary speech look like a pause
private const val SUSTAINED_FRAMES = 25
private const val LEVEL_BUCKETS = 64
private const val MIN_TAIL_BYTES = BYTES_PER_SECOND / 5

// cuts live 16 kHz mono PCM into pieces at natural pauses while recording continues.
// a pause is a run of frames well below the speech level of the piece, which adapts
// to quiet microphones and noise suppression better than a fixed level. pieces with
// no sound above the pause level are dropped, because whisper tends to invent words
// like "thank you" for silent clips and an endpoint bills for them
internal class PauseChunker(
    minSeconds: Int,
    private val maxBytes: Int = 25 * BYTES_PER_SECOND,
    private val pauseFrames: Int = 20,
) {
    private val minBytes = minSeconds * BYTES_PER_SECOND
    private var data = ByteArray(maxBytes + 64 * 1024)
    private var size = 0
    private var cutBytes = 0L
    private val frameLevels = ArrayList<Int>()
    // frame levels of the current piece in quarter-octave buckets
    private val histogram = IntArray(LEVEL_BUCKETS)
    private var quietRun = 0

    fun add(buffer: ByteArray, count: Int): List<AudioPiece> {
        if (size + count > data.size) data = data.copyOf(maxOf(data.size * 2, size + count))
        System.arraycopy(buffer, 0, data, size, count)
        size += count
        val pieces = mutableListOf<AudioPiece>()
        while ((frameLevels.size + 1) * FRAME_BYTES <= size) {
            val level = frameLevel(frameLevels.size * FRAME_BYTES)
            frameLevels += level
            histogram[bucketOf(level)]++
            quietRun = if (level < maxOf(QUIET_LEVEL, speechLevel() / 8)) quietRun + 1 else 0
            val analysed = frameLevels.size * FRAME_BYTES
            val cutFrame = when {
                analysed >= minBytes && quietRun >= pauseFrames -> frameLevels.size - quietRun / 2
                analysed >= maxBytes -> quietestFrame(frameLevels.size - 5 * BYTES_PER_SECOND / FRAME_BYTES)
                else -> null
            }
            if (cutFrame != null) cut(cutFrame)?.let { pieces += it }
        }
        return pieces
    }

    // the remainder after the last cut, or null when it holds no speech
    fun finish(): AudioPiece? {
        if (size < MIN_TAIL_BYTES || !hasSpeech(frameLevels)) return null
        return AudioPiece(data.copyOf(size), cutBytes)
    }

    // the loudest level the piece has held for half a second in total
    private fun speechLevel(): Int {
        var frames = 0
        for (bucket in LEVEL_BUCKETS - 1 downTo 1) {
            frames += histogram[bucket]
            if (frames >= SUSTAINED_FRAMES) return bucketFloor(bucket)
        }
        return 0
    }

    private fun quietestFrame(from: Int): Int {
        var best = frameLevels.size
        var bestLevel = Int.MAX_VALUE
        for (index in maxOf(1, from) until frameLevels.size) {
            if (frameLevels[index] <= bestLevel) {
                bestLevel = frameLevels[index]
                best = index
            }
        }
        return best
    }

    private fun cut(frame: Int): AudioPiece? {
        val bytes = frame * FRAME_BYTES
        val piece = if (hasSpeech(frameLevels.subList(0, frame))) AudioPiece(data.copyOf(bytes), cutBytes) else null
        System.arraycopy(data, bytes, data, 0, size - bytes)
        size -= bytes
        cutBytes += bytes
        frameLevels.subList(0, frame).clear()
        histogram.fill(0)
        for (level in frameLevels) histogram[bucketOf(level)]++
        quietRun = 0
        return piece
    }

    private fun frameLevel(offset: Int): Int {
        var sum = 0.0
        var index = offset
        while (index < offset + FRAME_BYTES) {
            val sample = (data[index].toInt() and 0xff) or (data[index + 1].toInt() shl 8)
            sum += sample.toDouble() * sample
            index += 2
        }
        return sqrt(sum / FRAME_SAMPLES).toInt()
    }
}

// a short word lasts about a fifth of a second, so anything with less sound
// than that above the pause level is silence or a click
private fun hasSpeech(levels: List<Int>): Boolean =
    levels.count { it >= QUIET_LEVEL } >= minOf(SPEECH_FRAMES, maxOf(1, levels.size / 4))

private fun bucketOf(level: Int): Int =
    if (level <= 0) 0 else minOf(LEVEL_BUCKETS - 1, 1 + (4 * ln(level.toDouble()) / ln(2.0)).toInt())

private fun bucketFloor(bucket: Int): Int = 2.0.pow((bucket - 1) / 4.0).toInt()

// transcribes pieces of a dictation while the user is still speaking, so stopping
// only waits for the last few seconds instead of the whole recording
internal class ChunkedTranscription(
    private val worker: ExecutorService,
    val client: TranscriptionClient,
    val endpoint: TranscriptionEndpoint,
) {
    private val parts = mutableListOf<Future<String>>()
    val started: Boolean get() = parts.isNotEmpty()

    // most takes are a few seconds, so pieces can be short. local whisper is the
    // exception because it always encodes a full 30 second window, so every extra
    // piece costs as much as a long one
    val minChunkSeconds: Int get() =
        if (endpoint.localModel?.let(LocalSpeechModels::engineOf) == SpeechEngine.WHISPER) 12 else 3

    fun add(file: File) {
        parts += worker.submit(Callable {
            try { client.transcribe(endpoint, file, allowEmpty = true) } finally { file.delete() }
        })
    }

    // runs on the single worker after every queued part
    fun finish(fullRecording: File): String {
        val joined = try {
            parts.map { it.get().trim() }.filter(String::isNotEmpty).joinToString(" ")
        } catch (error: ExecutionException) {
            if (endpoint.localModel == null) throw error.cause ?: error
            ""
        }
        if (joined.isNotEmpty()) return joined
        if (endpoint.localModel != null) return client.transcribe(endpoint, fullRecording)
        throw IOException("No speech returned.")
    }

    fun cancel() {
        client.cancel()
        parts.forEach { it.cancel(true) }
    }
}

internal fun pcmToWav(pcm: ByteArray, directory: File): File {
    val file = File.createTempFile("murmur-chunk-", ".wav", directory)
    file.outputStream().use { output ->
        output.write(PcmRecorder.wavHeader(pcm.size))
        output.write(pcm)
    }
    return file
}
