package dev.local.murmur

import org.json.JSONObject
import java.io.EOFException
import java.io.File
import java.io.IOException
import java.io.InterruptedIOException
import java.net.ConnectException
import java.net.NoRouteToHostException
import java.net.SocketException
import java.net.SocketTimeoutException
import java.net.URI
import java.time.ZonedDateTime
import java.time.format.DateTimeFormatter
import java.util.concurrent.CountDownLatch
import java.util.concurrent.TimeUnit
import okhttp3.Call
import okhttp3.Callback
import okhttp3.MediaType.Companion.toMediaType
import okhttp3.MultipartBody
import okhttp3.OkHttpClient
import okhttp3.Request
import okhttp3.RequestBody
import okhttp3.RequestBody.Companion.asRequestBody
import okhttp3.Response
import okio.BufferedSink

internal class TranscriptionClient {
    private val cancellation = CountDownLatch(1)
    private val cancelled get() = cancellation.count == 0L
    @Volatile private var activeCall: Call? = null

    fun cancel() {
        cancellation.countDown()
        activeCall?.cancel()
    }

    // onLanguage only fires for on-phone whisper running with automatic detection
    fun transcribe(
        endpoint: TranscriptionEndpoint,
        audioFile: File,
        allowEmpty: Boolean = false,
        retryHttp: Boolean = true,
        onLanguage: ((String) -> Unit)? = null,
    ): String {
        check(!cancelled) { "Transcription cancelled." }
        val audioMs = (audioFile.length() - 44).coerceAtLeast(0) / 32
        val source = endpoint.localModel?.name?.removePrefix("ggml-")?.removeSuffix(".bin") ?: endpoint.uri.host
        val started = System.nanoTime()
        try {
            val text = endpoint.localModel?.let { model ->
                LocalWhisper.transcribe(model, audioFile, endpoint.language, endpoint.translate, onLanguage)
            } ?: upload(endpoint, audioFile, audioMs, retryHttp)
            Diagnostics.info("speech", "$source: ${audioMs / 1000.0}s of audio in ${elapsedMs(started)} ms, ${text.length} chars")
            if (text.isEmpty() && !allowEmpty) throw IOException("No speech returned.")
            return text
        } catch (error: Exception) {
            if (!cancelled) Diagnostics.warn("speech", "$source failed after ${elapsedMs(started)} ms on ${audioMs / 1000.0}s of audio", error)
            throw error
        }
    }

    private fun upload(endpoint: TranscriptionEndpoint, audioFile: File, audioMs: Long, retryHttp: Boolean): String {
        val responseMs = (15_000 + audioMs).coerceIn(20_000, 120_000)
        val uploadMs = audioFile.length() * 1_000 / UPLOAD_BYTES_PER_SECOND
        val deadline = System.nanoTime() + TimeUnit.MILLISECONDS.toNanos(responseMs + uploadMs)
        val multipart = MultipartBody.Builder().setType(MultipartBody.FORM)
            .addFormDataPart("model", endpoint.model)
            .apply {
                if (endpoint.language != "auto") addFormDataPart("language", endpoint.language)
            }
            .addFormDataPart("file", "audio.wav", audioFile.asRequestBody("audio/wav".toMediaType()))
            .build()
        val oneShot = object : RequestBody() {
            override fun contentType() = multipart.contentType()
            override fun contentLength() = multipart.contentLength()
            override fun writeTo(sink: BufferedSink) = multipart.writeTo(sink)
            override fun isOneShot() = true
        }
        val request = Request.Builder().url(endpoint.uri.toASCIIString()).post(oneShot)
            .header("Accept", "application/json")
            .apply {
                endpoint.apiKey?.let { key ->
                    requireValidApiKey(key)
                    header("Authorization", "Bearer $key")
                }
            }.build()
        return try {
            send(request, deadline)
        } catch (error: HttpStatusException) {
            val retryable = error.statusCode in 500..599 || error.statusCode == 408 || error.statusCode == 429
            if (!retryHttp || !retryable) throw error
            val delayMs = error.retryAfterMs ?: 1_000
            if (cancelled || delayMs >= TimeUnit.NANOSECONDS.toMillis(deadline - System.nanoTime())) throw error
            Diagnostics.warn("speech", "retrying ${endpoint.uri.host} once", error)
            cancellation.await(delayMs, TimeUnit.MILLISECONDS)
            send(request, deadline)
        } catch (error: IOException) {
            if (!retryHttp || cancelled || !connectionDropped(error)) throw error
            if (deadline - System.nanoTime() <= TimeUnit.MILLISECONDS.toNanos(300)) throw SpeechConnectionDropped(error)
            Diagnostics.warn("speech", "retrying ${endpoint.uri.host} after a dropped connection", error)
            if (cancellation.await(300, TimeUnit.MILLISECONDS)) throw error
            try {
                send(request, deadline)
            } catch (retryError: IOException) {
                if (!cancelled && connectionDropped(retryError)) throw SpeechConnectionDropped(retryError)
                throw retryError
            }
        }
    }

    private fun send(request: Request, deadline: Long): String {
        if (deadline - System.nanoTime() <= 0) throw SocketTimeoutException("Transcription deadline expired.")
        val call = http.newCall(request)
        call.timeout().deadlineNanoTime(deadline)
        activeCall = call
        try {
            check(!cancelled && !Thread.currentThread().isInterrupted) { "Transcription cancelled." }
            return call.execute().use { response ->
                if (!response.isSuccessful) throw HttpStatusException(response.code, retryAfterMs(response.header("Retry-After")))
                val source = response.body?.source() ?: throw IOException("Missing transcription response.")
                if (source.request(MAX_RESPONSE_BYTES + 1)) throw IOException("Transcription response was too large.")
                parseSpeechResponse(source.readUtf8())
            }
        } catch (error: InterruptedIOException) {
            if (!cancelled && deadline - System.nanoTime() <= 0) {
                throw SocketTimeoutException("Transcription deadline expired.").apply { initCause(error) }
            }
            throw error
        } finally {
            activeCall = null
        }
    }

    companion object {
        private const val UPLOAD_BYTES_PER_SECOND = 16_000
        private const val MAX_RESPONSE_BYTES = 1024L * 1024
        private val http = OkHttpClient.Builder()
            .followRedirects(false)
            .followSslRedirects(false)
            .retryOnConnectionFailure(false)
            .readTimeout(0, TimeUnit.MILLISECONDS)
            .writeTimeout(0, TimeUnit.MILLISECONDS)
            .build()

        fun warmUp(uri: URI) {
            if (uri.scheme != "https") return
            val request = Request.Builder().url(uri.toASCIIString()).head().build()
            val call = http.newCall(request)
            call.timeout().timeout(5, TimeUnit.SECONDS)
            call.enqueue(object : Callback {
                override fun onResponse(call: Call, response: Response) = response.close()
                override fun onFailure(call: Call, e: IOException) = Unit
            })
        }
    }
}

internal class SpeechConnectionDropped(cause: IOException) : IOException(cause.message, cause)

private fun connectionDropped(error: IOException): Boolean = when (error) {
    is ConnectException, is NoRouteToHostException, is InterruptedIOException -> false
    is SocketException, is EOFException -> true
    // okhttp wraps an eof before the response headers in an io exception
    else -> error.cause is EOFException
}

internal fun retryAfterMs(value: String?): Long? {
    val header = value?.trim()?.takeIf { it.isNotEmpty() } ?: return null
    if (header.all { it in '0'..'9' }) {
        return TimeUnit.SECONDS.toMillis(header.toLongOrNull() ?: Long.MAX_VALUE)
    }
    return runCatching {
        val retryAt = ZonedDateTime.parse(header, DateTimeFormatter.RFC_1123_DATE_TIME).toInstant().toEpochMilli()
        (retryAt - System.currentTimeMillis()).coerceAtLeast(0)
    }.getOrNull()
}

internal fun elapsedMs(startedNanos: Long): Long = (System.nanoTime() - startedNanos) / 1_000_000

internal class HttpStatusException(val statusCode: Int, val retryAfterMs: Long? = null) : IOException("Transcription server returned HTTP $statusCode.")

internal fun parseSpeechResponse(response: String): String {
    val text = try {
        JSONObject(response).opt("text")
    } catch (_: org.json.JSONException) {
        null
    }
    return (text as? String)?.trim() ?: throw IOException("Invalid transcription server response.")
}
