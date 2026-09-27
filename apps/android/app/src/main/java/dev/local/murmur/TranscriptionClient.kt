package dev.local.murmur

import org.json.JSONObject
import java.io.File
import java.io.FileInputStream
import java.io.IOException
import java.net.HttpURLConnection
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
        val boundary = "murmur-${UUID.randomUUID()}"
        val prefix = (
            "--$boundary\r\n" +
                "Content-Disposition: form-data; name=\"model\"\r\n\r\n" +
                endpoint.model + "\r\n" +
                "--$boundary\r\n" +
                "Content-Disposition: form-data; name=\"file\"; filename=\"audio.wav\"\r\n" +
                "Content-Type: audio/wav\r\n\r\n"
        ).toByteArray(StandardCharsets.UTF_8)
        val suffix = "\r\n--$boundary--\r\n".toByteArray(StandardCharsets.UTF_8)
        val request = (endpoint.uri.toURL().openConnection() as HttpURLConnection).also {
            connection = it
        }
        try {
            request.instanceFollowRedirects = false
            request.requestMethod = "POST"
            request.connectTimeout = 15_000
            request.readTimeout = 120_000
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
            if (text.isEmpty() && !allowEmpty) throw IOException("No speech returned.")
            return text
        } finally {
            connection = null
            request.disconnect()
        }
    }
}

internal class HttpStatusException(val statusCode: Int) : IOException("Transcription server returned HTTP $statusCode.")
