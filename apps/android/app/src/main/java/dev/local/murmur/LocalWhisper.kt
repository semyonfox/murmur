package dev.local.murmur

import android.app.ActivityManager
import android.content.Context
import java.io.File
import java.io.FileInputStream
import java.io.FileOutputStream
import java.io.IOException
import java.net.HttpURLConnection
import java.net.URL
import java.nio.ByteBuffer
import java.nio.ByteOrder
import java.security.MessageDigest
import java.util.concurrent.Executors
import java.util.concurrent.TimeUnit

internal enum class SpeechEngine { WHISPER, PARAKEET }

internal data class DownloadableSpeechModel(
    val id: String,
    val label: String,
    val bytes: Long,
    val sha256: String,
    val minimumRamGb: Int,
    val engine: SpeechEngine = SpeechEngine.WHISPER,
) {
    val url: String get() = when (engine) {
        SpeechEngine.WHISPER -> "https://huggingface.co/ggerganov/whisper.cpp/resolve/5359861c739e955e79d9a303bcbc70fb988958b1/ggml-$id.bin"
        SpeechEngine.PARAKEET -> "https://huggingface.co/ggml-org/parakeet-GGUF/resolve/35156454d1a39de06863303dd209fd2bed6ee079/ggml-$id.bin"
    }
}

internal object LocalSpeechModels {
    @Volatile var downloadingId: String? = null
        private set
    @Volatile var downloadPercent: Int = 0
        private set
    @Volatile var downloadMessage: String? = null
        private set
    private var downloadThread: Thread? = null

    @Synchronized fun startDownload(context: Context, model: DownloadableSpeechModel): Boolean {
        if (downloadThread != null) return false
        downloadingId = model.id
        downloadPercent = 0
        downloadMessage = null
        downloadThread = Thread({
            val result = runCatching { download(context.applicationContext, model) { downloadPercent = it } }
            result.onFailure { Diagnostics.warn("models", "download of ${model.id} failed", it) }
            synchronized(this) {
                downloadMessage = result.fold({ "${model.label} is ready." }, { it.message ?: "Download failed." })
                downloadingId = null
                downloadThread = null
            }
        }, "murmur-model-download").also { it.start() }
        return true
    }

    @Synchronized fun cancelDownload() { downloadThread?.interrupt() }

    val catalog = listOf(
        DownloadableSpeechModel("tiny-q8_0", "Whisper Tiny · 44 MB", 43_537_433, "c2085835d3f50733e2ff6e4b41ae8a2b8d8110461e18821b09a15c40c42d1cca", 2),
        DownloadableSpeechModel("base-q8_0", "Whisper Base · 82 MB", 81_768_585, "c577b9a86e7e048a0b7eada054f4dd79a56bbfa911fbdacf900ac5b567cbb7d9", 2),
        DownloadableSpeechModel("small-q8_0", "Whisper Small · 265 MB", 264_464_607, "49c8fb02b65e6049d5fa6c04f81f53b867b5ec9540406812c643f177317f779f", 3),
        DownloadableSpeechModel("large-v3-turbo-q8_0", "Whisper Large v3 Turbo · 874 MB", 874_188_075, "317eb69c11673c9de1e1f0d459b253999804ec71ac4c23c17ecf5fbe24e259a1", 6),
        // parakeet is much faster than whisper on phone CPUs and punctuates by itself,
        // but it covers 25 European languages and cannot translate
        DownloadableSpeechModel("parakeet-tdt-0.6b-v3-q4_0", "Parakeet v3 · 356 MB", 355_615_679, "aa7fe2f5fb47d863ca23e8b1d490632d63a2599f515268b6d6bd656158dad45e", 3, SpeechEngine.PARAKEET),
        DownloadableSpeechModel("parakeet-tdt-0.6b-v3-q8_0", "Parakeet v3 · 669 MB", 668_757_119, "4d64e9e96c2792186d072fde0034df0ad670cf680a2f53069052ead827fd600e", 4, SpeechEngine.PARAKEET),
    )

    fun engineOf(model: File): SpeechEngine =
        catalog.firstOrNull { it.id == model.name.removePrefix("ggml-").removeSuffix(".bin") }?.engine
            ?: SpeechEngine.WHISPER

    fun file(context: Context, model: DownloadableSpeechModel): File =
        File(File(context.filesDir, "speech-models"), "ggml-${model.id}.bin")

    fun isInstalled(context: Context, model: DownloadableSpeechModel): Boolean = file(context, model).length() == model.bytes

    fun canRun(context: Context, model: DownloadableSpeechModel): Boolean {
        val manager = context.getSystemService(Context.ACTIVITY_SERVICE) as ActivityManager
        val info = ActivityManager.MemoryInfo()
        manager.getMemoryInfo(info)
        return info.totalMem >= (model.minimumRamGb * 1024L - 512L) * 1024L * 1024L
    }

    fun download(context: Context, model: DownloadableSpeechModel, progress: (Int) -> Unit) {
        val destination = file(context, model)
        destination.parentFile?.mkdirs()
        val pending = File(destination.parentFile, "${destination.name}.part")
        if (pending.length() > model.bytes) pending.delete()
        if (pending.length() == model.bytes) {
            val digest = MessageDigest.getInstance("SHA-256")
            FileInputStream(pending).use { input ->
                val buffer = ByteArray(64 * 1024)
                while (true) {
                    val count = input.read(buffer)
                    if (count < 0) break
                    digest.update(buffer, 0, count)
                }
            }
            if (digest.digest().joinToString("") { "%02x".format(it) } == model.sha256) {
                require(pending.renameTo(destination)) { "Could not save the model." }
                return
            }
            pending.delete()
        }
        val existing = pending.length()
        require(destination.parentFile?.usableSpace ?: 0L > (model.bytes - existing) * 2) {
            "Not enough free storage for this model."
        }
        val request = URL(model.url).openConnection() as HttpURLConnection
        try {
            request.instanceFollowRedirects = true
            request.connectTimeout = 15_000
            request.readTimeout = 30_000
            if (existing > 0) request.setRequestProperty("Range", "bytes=$existing-")
            val code = request.responseCode
            require(code == 200 || code == 206) { "Model download returned HTTP $code." }
            val append = code == 206 && existing > 0 &&
                request.getHeaderField("Content-Range")?.startsWith("bytes $existing-") == true
            if (code == 206 && !append) throw IOException("Model server returned an invalid range.")
            val digest = MessageDigest.getInstance("SHA-256")
            var received = if (append) existing else 0L
            if (append) FileInputStream(pending).use { input ->
                val buffer = ByteArray(64 * 1024)
                while (true) {
                    check(!Thread.currentThread().isInterrupted) { "Download cancelled." }
                    val count = input.read(buffer)
                    if (count < 0) break
                    digest.update(buffer, 0, count)
                }
            }
            request.inputStream.use { input ->
                FileOutputStream(pending, append).use { output ->
                    val buffer = ByteArray(64 * 1024)
                    while (true) {
                        check(!Thread.currentThread().isInterrupted) { "Download cancelled." }
                        val count = input.read(buffer)
                        if (count < 0) break
                        received += count
                        if (received > model.bytes) {
                            pending.delete()
                            throw IOException("Model download exceeded expected size.")
                        }
                        digest.update(buffer, 0, count)
                        output.write(buffer, 0, count)
                        progress((received * 100 / model.bytes).toInt())
                    }
                    output.fd.sync()
                }
            }
            if (received != model.bytes) throw IOException("Download stopped early. Tap Download to resume.")
            if (digest.digest().joinToString("") { "%02x".format(it) } != model.sha256) {
                pending.delete()
                throw IOException("Model checksum did not match. Try downloading it again.")
            }
            require(pending.renameTo(destination)) { "Could not save the model." }
        } finally {
            request.disconnect()
            if (Thread.currentThread().isInterrupted) pending.delete()
        }
    }
}

internal object LocalWhisper {
    init { System.loadLibrary("murmur_whisper") }

    private external fun nativeOpen(path: String): Long
    private external fun nativeClose(context: Long)
    private external fun nativeTranscribe(context: Long, samples: FloatArray, language: String, translate: Boolean): ByteArray?
    private external fun nativeOpenParakeet(path: String): Long
    private external fun nativeCloseParakeet(context: Long)
    private external fun nativeTranscribeParakeet(context: Long, samples: FloatArray): ByteArray?
    private var loadedPath: String? = null
    private var loadedEngine = SpeechEngine.WHISPER
    private var context: Long = 0
    private var lastUsedAt = 0L
    private val idleReleaser = Executors.newSingleThreadScheduledExecutor { task ->
        Thread(task, "murmur-whisper-idle").apply { isDaemon = true }
    }
    private const val IDLE_RELEASE_MS = 120_000L
    private const val PARAKEET_PIECE_SAMPLES = 20 * 16_000
    private const val PARAKEET_SEARCH_SAMPLES = 6 * 16_000

    @Synchronized fun release() {
        if (context != 0L) Diagnostics.info("speech", "released ${loadedPath?.substringAfterLast('/')}")
        if (context != 0L) when (loadedEngine) {
            SpeechEngine.WHISPER -> nativeClose(context)
            SpeechEngine.PARAKEET -> nativeCloseParakeet(context)
        }
        context = 0
        loadedPath = null
    }

    // loading a model takes seconds, so dictation keeps it warm between takes
    // and only frees the memory after the user has been idle for a while
    @Synchronized fun releaseWhenIdle() {
        lastUsedAt = System.nanoTime()
        idleReleaser.schedule({ releaseIfIdle() }, IDLE_RELEASE_MS, TimeUnit.MILLISECONDS)
    }

    @Synchronized private fun releaseIfIdle() {
        if (System.nanoTime() - lastUsedAt >= TimeUnit.MILLISECONDS.toNanos(IDLE_RELEASE_MS)) release()
    }

    // called when recording starts so the load overlaps with the user speaking
    @Synchronized fun preload(model: File) {
        if (!model.isFile) return
        try {
            load(model)
        } catch (error: Exception) {
            // transcribe loads again and reports the failure to the user
            Diagnostics.warn("speech", "preloading ${model.name} failed", error)
        }
        // a cancelled take never reaches transcribe, so schedule the release here too
        releaseWhenIdle()
    }

    private fun load(model: File) {
        lastUsedAt = System.nanoTime()
        if (loadedPath == model.absolutePath && context != 0L) return
        release()
        val engine = LocalSpeechModels.engineOf(model)
        val started = System.nanoTime()
        context = when (engine) {
            SpeechEngine.WHISPER -> nativeOpen(model.absolutePath)
            SpeechEngine.PARAKEET -> nativeOpenParakeet(model.absolutePath)
        }
        if (context == 0L) throw IOException("Could not load the selected model.")
        Diagnostics.info("speech", "loaded ${model.name} in ${elapsedMs(started)} ms")
        loadedEngine = engine
        loadedPath = model.absolutePath
    }

    @Synchronized fun transcribe(model: File, wav: File, language: String, translate: Boolean): String {
        check(model.isFile) { "Download the selected model first." }
        if (wav.length() > 16L * 1024 * 1024) throw IOException("Audio part is too large for local transcription.")
        val bytes = FileInputStream(wav).use { it.readBytes() }
        if (bytes.size < 44 || String(bytes, 0, 4) != "RIFF" || String(bytes, 8, 4) != "WAVE") {
            throw IOException("Expected a WAV recording.")
        }
        val header = ByteBuffer.wrap(bytes).order(ByteOrder.LITTLE_ENDIAN)
        if (header.getShort(20).toInt() != 1 || header.getShort(22).toInt() != 1 ||
            header.getInt(24) != 16_000 || header.getShort(34).toInt() != 16) {
            throw IOException("Expected mono 16 kHz PCM audio.")
        }
        val samples = FloatArray((bytes.size - 44) / 2) { index -> header.getShort(44 + index * 2) / 32768f }
        load(model)
        return when (loadedEngine) {
            SpeechEngine.WHISPER -> nativeTranscribe(context, samples, language.substringBefore('-'), translate)
                ?.toString(Charsets.UTF_8)?.trim()
                ?: throw IOException("On-phone transcription failed.")
            SpeechEngine.PARAKEET -> transcribeParakeet(samples)
        }
    }

    // parakeet attends over the whole clip, so memory and time grow quadratically.
    // on a test machine 33 s took 8 s but 8 minutes took 9 minutes and 7 GB,
    // so lectures and long takes go through in pieces of at most 20 seconds
    private fun transcribeParakeet(samples: FloatArray): String =
        splitAtPauses(samples, PARAKEET_PIECE_SAMPLES, PARAKEET_SEARCH_SAMPLES).joinToString(" ") { piece ->
            nativeTranscribeParakeet(context, samples.copyOfRange(piece.first, piece.last + 1))
                ?.toString(Charsets.UTF_8)?.trim()
                ?: throw IOException("On-phone transcription failed.")
        }.replace(Regex(" {2,}"), " ").trim()

}
