package dev.local.murmur

import java.io.File
import java.net.ConnectException
import java.net.SocketTimeoutException
import java.net.UnknownHostException
import java.util.concurrent.Callable
import java.util.concurrent.ExecutionException
import java.util.concurrent.ExecutorService
import java.util.concurrent.Future
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

// cuts live 16 kHz mono PCM into pieces at natural pauses while recording continues.
// a pause is a run of frames well below the loudest speech heard in the piece, which
// adapts to quiet microphones and noise suppression better than a fixed level
internal class PauseChunker(
    minSeconds: Int,
    private val maxBytes: Int = 25 * BYTES_PER_SECOND,
    private val pauseFrames: Int = 20,
) {
    private val minBytes = minSeconds * BYTES_PER_SECOND
    private var data = ByteArray(maxBytes + 64 * 1024)
    private var size = 0
    private val frameLevels = ArrayList<Int>()
    private var quietRun = 0
    private var loudest = 0
    private var overallLoudest = 0

    fun add(buffer: ByteArray, count: Int): List<ByteArray> {
        if (size + count > data.size) data = data.copyOf(maxOf(data.size * 2, size + count))
        System.arraycopy(buffer, 0, data, size, count)
        size += count
        val pieces = mutableListOf<ByteArray>()
        while ((frameLevels.size + 1) * FRAME_BYTES <= size) {
            val level = frameLevel(frameLevels.size * FRAME_BYTES)
            frameLevels += level
            loudest = maxOf(loudest, level)
            overallLoudest = maxOf(overallLoudest, level)
            quietRun = if (isQuiet(level)) quietRun + 1 else 0
            val analysed = frameLevels.size * FRAME_BYTES
            val cutFrame = when {
                analysed >= minBytes && quietRun >= pauseFrames -> frameLevels.size - quietRun / 2
                analysed >= maxBytes -> quietestFrame(frameLevels.size - 5 * BYTES_PER_SECOND / FRAME_BYTES)
                else -> null
            }
            if (cutFrame != null) pieces += cut(cutFrame)
        }
        return pieces
    }

    // the remainder after the last cut, or null when it holds no speech, because
    // whisper tends to invent words like "thank you" for silent clips
    fun finish(): ByteArray? {
        if (size < BYTES_PER_SECOND / 2 || loudest * 4 < overallLoudest) return null
        return data.copyOf(size)
    }

    private fun isQuiet(level: Int): Boolean = level < maxOf(60, loudest / 8)

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

    private fun cut(frame: Int): ByteArray {
        val bytes = frame * FRAME_BYTES
        val piece = data.copyOf(bytes)
        System.arraycopy(data, bytes, data, 0, size - bytes)
        size -= bytes
        frameLevels.subList(0, frame).clear()
        loudest = frameLevels.maxOrNull() ?: 0
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

    // runs on the single worker thread after every queued part, so they are all done.
    // a failed part falls back to the whole recording rather than losing the take,
    // unless the server is unreachable or too slow, where sending more audio only
    // makes the user wait twice for the same failure
    fun finish(fullRecording: File): String {
        val joined = try {
            parts.joinToString(" ") { it.get().trim() }.replace(Regex(" {2,}"), " ").trim()
        } catch (error: ExecutionException) {
            val cause = error.cause ?: error
            if (cause is SocketTimeoutException || cause is UnknownHostException || cause is ConnectException) throw cause
            Diagnostics.warn("speech", "a live part failed, sending the whole take instead", cause)
            null
        }
        if (joined != null && joined.isNotEmpty()) return joined
        return client.transcribe(endpoint, fullRecording)
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
