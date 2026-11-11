package dev.local.murmur

import org.json.JSONObject
import java.io.File
import java.io.FileInputStream
import java.io.IOException
import java.net.HttpURLConnection
import java.net.URI
import java.nio.charset.StandardCharsets
import java.util.UUID
import java.util.concurrent.atomic.AtomicBoolean

internal class TranscriptionClient {
    private val cancelled = AtomicBoolean(false)
    @Volatile private var connection: HttpURLConnection? = null

    fun cancel() {
        cancelled.set(true)
        connection?.disconnect()
    }

    fun transcribe(endpoint: TranscriptionEndpoint, audioFile: File, allowEmpty: Boolean = false): String {
        check(!cancelled.get()) { "Transcription cancelled." }
        val audioMs = (audioFile.length() - 44).coerceAtLeast(0) / 32
        val source = endpoint.localModel?.name?.removePrefix("ggml-")?.removeSuffix(".bin") ?: endpoint.uri.host
        val started = System.nanoTime()
        try {
            val text = endpoint.localModel?.let { model ->
                LocalWhisper.transcribe(model, audioFile, endpoint.language, endpoint.translate)
            } ?: upload(endpoint, audioFile, audioMs)
            Diagnostics.info("speech", "$source: ${audioMs / 1000.0}s of audio in ${elapsedMs(started)} ms, ${text.length} chars")
            if (text.isEmpty() && !allowEmpty) throw IOException("No speech returned.")
            return text
        } catch (error: Exception) {
            if (!cancelled.get()) Diagnostics.warn("speech", "$source failed after ${elapsedMs(started)} ms on ${audioMs / 1000.0}s of audio", error)
            throw error
        }
    }

    // one quick retry covers a dropped or stale pooled connection. a timeout is not
    // retried because the server is busy and the user already waited for it
    private fun upload(endpoint: TranscriptionEndpoint, audioFile: File, audioMs: Long): String {
        val started = System.nanoTime()
        return try {
            send(endpoint, audioFile, audioMs)
        } catch (error: IOException) {
            val retryable = when (error) {
                is HttpStatusException -> error.statusCode == 429 || error.statusCode in 500..599
                is java.net.SocketTimeoutException, is java.net.UnknownHostException -> false
                else -> true
            }
            if (!retryable || cancelled.get() || elapsedMs(started) > 10_000) throw error
            Diagnostics.warn("speech", "retrying ${endpoint.uri.host} once", error)
            Thread.sleep(if (error is HttpStatusException) 1_000 else 300)
            check(!cancelled.get()) { "Transcription cancelled." }
            send(endpoint, audioFile, audioMs)
        }
    }

    private fun send(endpoint: TranscriptionEndpoint, audioFile: File, audioMs: Long): String {
        val boundary = "murmur-${UUID.randomUUID()}"
        val languagePart = if (endpoint.language == "auto") "" else
            "--$boundary\r\nContent-Disposition: form-data; name=\"language\"\r\n\r\n${endpoint.language}\r\n"
        val prefix = (
            "--$boundary\r\n" +
                "Content-Disposition: form-data; name=\"model\"\r\n\r\n" +
                endpoint.model + "\r\n" +
                languagePart +
                "--$boundary\r\n" +
                "Content-Disposition: form-data; name=\"file\"; filename=\"audio.wav\"\r\n" +
                "Content-Type: audio/wav\r\n\r\n"
        ).toByteArray(StandardCharsets.UTF_8)
        val suffix = "\r\n--$boundary--\r\n".toByteArray(StandardCharsets.UTF_8)
        val request = (endpoint.uri.toURL().openConnection() as HttpURLConnection).also {
            connection = it
        }
        var reusable = false
        try {
            request.instanceFollowRedirects = false
            request.requestMethod = "POST"
            request.connectTimeout = 10_000
            // hosted servers answer a few seconds of speech in one or two seconds, so a
            // short take should not sit on "Transcribing" for two minutes when one stalls
            request.readTimeout = (15_000 + audioMs).coerceIn(20_000, 120_000).toInt()
            request.doOutput = true
            request.setRequestProperty("Content-Type", "multipart/form-data; boundary=$boundary")
            request.setRequestProperty("Accept", "application/json")
            endpoint.apiKey?.let { request.setRequestProperty("Authorization", "Bearer $it") }
            request.setFixedLengthStreamingMode(prefix.size.toLong() + audioFile.length() + suffix.size)
            check(!cancelled.get()) { "Transcription cancelled." }

            request.outputStream.use { output ->
                output.write(prefix)
                FileInputStream(audioFile).use { input ->
                    val buffer = ByteArray(16 * 1024)
                    while (true) {
                        check(!cancelled.get()) { "Transcription cancelled." }
                        val count = input.read(buffer)
                        if (count < 0) break
                        output.write(buffer, 0, count)
                    }
                }
                output.write(suffix)
            }
            check(!cancelled.get()) { "Transcription cancelled." }
            val code = request.responseCode
            if (code !in 200..299) throw HttpStatusException(code)
            val response = request.inputStream.use { input ->
                val output = ByteArray(1024 * 1024 + 1)
                var used = 0
                while (used < output.size) {
                    val count = input.read(output, used, output.size - used)
                    if (count < 0) break
                    used += count
                }
                if (used == output.size) throw IOException("Transcription response was too large.")
                String(output, 0, used, StandardCharsets.UTF_8)
            }
            check(!cancelled.get()) { "Transcription cancelled." }
            val text = JSONObject(response).optString("text").trim()
            reusable = true
            return text
        } finally {
            connection = null
            // disconnect closes the socket, so only do it when the exchange broke
            if (!reusable) request.disconnect()
        }
    }
}

// opens the TLS connection while the user is still speaking, so the upload and
// cleanup requests reuse a warm socket instead of paying for a fresh handshake
internal fun warmUpConnections(endpoints: List<URI>) {
    val remote = endpoints.filter { it.scheme == "https" }.distinctBy { it.host to it.port }
    if (remote.isEmpty()) return
    Thread({
        for (uri in remote) {
            val request = uri.toURL().openConnection() as HttpURLConnection
            try {
                request.instanceFollowRedirects = false
                request.requestMethod = "HEAD"
                request.connectTimeout = 5_000
                request.readTimeout = 5_000
                request.responseCode
                request.inputStream.close()
            } catch (_: IOException) {
                // a 404 or 405 still leaves a pooled connection behind, and a real
                // network failure will surface again on the actual request
                request.disconnect()
            }
        }
    }, "murmur-warm-up").apply { isDaemon = true }.start()
}

internal fun elapsedMs(startedNanos: Long): Long = (System.nanoTime() - startedNanos) / 1_000_000

internal class HttpStatusException(val statusCode: Int) : IOException("Transcription server returned HTTP $statusCode.")
