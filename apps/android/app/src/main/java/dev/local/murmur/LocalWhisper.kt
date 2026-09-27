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

internal data class DownloadableSpeechModel(
    val id: String,
    val label: String,
    val bytes: Long,
    val sha256: String,
    val minimumRamGb: Int,
) {
    val url: String get() = "https://huggingface.co/ggerganov/whisper.cpp/resolve/5359861c739e955e79d9a303bcbc70fb988958b1/ggml-$id.bin"
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
    )

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
    private var loadedPath: String? = null
    private var context: Long = 0

    @Synchronized fun release() {
        if (context != 0L) nativeClose(context)
        context = 0
        loadedPath = null
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
        if (loadedPath != model.absolutePath) {
            release()
            context = nativeOpen(model.absolutePath)
            if (context == 0L) throw IOException("Could not load the selected model.")
            loadedPath = model.absolutePath
        }
        return nativeTranscribe(context, samples, language.substringBefore('-'), translate)
            ?.toString(Charsets.UTF_8)?.trim()
            ?: throw IOException("On-phone transcription failed.")
    }
}
