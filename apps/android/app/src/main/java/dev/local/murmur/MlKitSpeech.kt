package dev.local.murmur

import android.content.Context
import android.media.AudioFormat
import android.os.Build
import android.os.ParcelFileDescriptor
import android.os.SystemClock
import android.system.ErrnoException
import android.system.Os
import android.system.OsConstants
import androidx.annotation.RequiresApi
import com.google.mlkit.genai.common.DownloadStatus
import com.google.mlkit.genai.common.FeatureStatus
import com.google.mlkit.genai.common.audio.AudioSource
import com.google.mlkit.genai.speechrecognition.SpeechRecognition
import com.google.mlkit.genai.speechrecognition.SpeechRecognizer
import com.google.mlkit.genai.speechrecognition.SpeechRecognizerOptions
import com.google.mlkit.genai.speechrecognition.SpeechRecognizerResponse
import com.google.mlkit.genai.speechrecognition.speechRecognizerOptions
import com.google.mlkit.genai.speechrecognition.speechRecognizerRequest
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.CoroutineExceptionHandler
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.NonCancellable
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.async
import kotlinx.coroutines.cancel
import kotlinx.coroutines.channels.Channel
import kotlinx.coroutines.delay
import kotlinx.coroutines.ensureActive
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.launch
import kotlinx.coroutines.runBlocking
import kotlinx.coroutines.withContext
import kotlinx.coroutines.withTimeout
import java.io.IOException
import java.util.Locale
import java.util.concurrent.atomic.AtomicBoolean

// google's on-device ml kit recogniser, fed live from murmur's own microphone capture
// so transcription runs while the user speaks and stopping only flushes the tail.
// the approach follows t3 code's android voice module (MIT, pingdotgg/t3code
// apps/mobile/modules/t3-voice-transcription at dfeaf7b137), rewritten for murmur
internal object MlKitSpeech {
    private val modes = listOf(
        SpeechRecognizerOptions.Mode.MODE_ADVANCED,
        SpeechRecognizerOptions.Mode.MODE_BASIC,
    )
    // a launch that throws on a supervisor scope would otherwise crash the app
    private val logFailures = CoroutineExceptionHandler { _, error -> Diagnostics.warn("mlkit", "background work failed", error) }
    private val scope = CoroutineScope(SupervisorJob() + Dispatchers.IO + logFailures)
    private var client: Client? = null
    private val prewarming = AtomicBoolean(false)
    // a locale with no usable mode, so fallback dictations do not probe again until restart
    @Volatile private var unsupported: Locale? = null

    private class Client(val locale: Locale, val mode: Int, val recognizer: SpeechRecognizer) {
        var inUse = false
    }

    // regional preference extensions such as en-IE-u-mu-celsius make every mode unavailable.
    // the phone's own language keeps its region, so en on an Irish phone asks for en-IE
    fun localeFor(language: String): Locale {
        val device = Locale.getDefault().stripExtensions()
        return if (language == "auto" || language == device.language) device else Locale.forLanguageTag(language)
    }

    // reserve the warmed client until its session has stopped, including on cancellation
    @Synchronized
    @RequiresApi(MIN_SDK)
    fun startLive(language: String): LiveSession? {
        if (prewarming.get()) return null
        val ready = client?.takeIf { !it.inUse && it.locale == localeFor(language) } ?: return null
        ready.inUse = true
        return try {
            LiveSession(ready.recognizer, ready.mode)
        } catch (error: Exception) {
            ready.inUse = false
            Diagnostics.warn("mlkit", "could not start a live session", error)
            null
        }
    }

    // true once prewarm found no usable mode for this language
    fun isUnsupported(language: String) = unsupported == localeFor(language)

    // finds a usable mode, downloads the system model if the phone offers one, then loads it
    // so the first dictation does not pay for it. the weights come from google play services,
    // no user audio is involved. call when the user has chosen phone recognition
    @Synchronized
    fun prewarm(language: String) {
        val locale = localeFor(language)
        if (Build.VERSION.SDK_INT < MIN_SDK || client?.inUse == true || client?.locale == locale ||
            unsupported == locale || !prewarming.compareAndSet(false, true)
        ) return
        scope.launch {
            try { warm(locale) } finally { prewarming.set(false) }
        }
    }

    private suspend fun warm(locale: Locale) {
        for (mode in modes) {
            val recognizer = SpeechRecognition.getClient(speechRecognizerOptions {
                this.locale = locale
                preferredMode = mode
            })
            val usable = try {
                val status = recognizer.checkStatus()
                if (status == FeatureStatus.DOWNLOADABLE || status == FeatureStatus.DOWNLOADING) {
                    val started = SystemClock.elapsedRealtime()
                    val result = recognizer.download().first { it is DownloadStatus.DownloadCompleted || it is DownloadStatus.DownloadFailed }
                    if (result is DownloadStatus.DownloadFailed) throw result.e
                    Diagnostics.info("mlkit", "speech model for ${locale.toLanguageTag()} downloaded in ${SystemClock.elapsedRealtime() - started} ms")
                }
                if (recognizer.checkStatus() == FeatureStatus.AVAILABLE) {
                    val started = SystemClock.elapsedRealtime()
                    recognizer.warmup()
                    Diagnostics.info("mlkit", "mode $mode for ${locale.toLanguageTag()} warmed in ${SystemClock.elapsedRealtime() - started} ms")
                    true
                } else false
            } catch (error: Exception) {
                Diagnostics.warn("mlkit", "mode $mode for ${locale.toLanguageTag()} is unusable", error)
                false
            }
            if (!usable) {
                recognizer.close()
                continue
            }
            synchronized(this@MlKitSpeech) {
                client?.recognizer?.close()
                client = Client(locale, mode, recognizer)
            }
            return
        }
        unsupported = locale
        Diagnostics.info("mlkit", "no on-device mode for ${locale.toLanguageTag()}, keeping the phone speech service")
    }

    private fun release(recognizer: SpeechRecognizer, reusable: Boolean) {
        synchronized(this) {
            val current = client ?: return
            if (current.recognizer !== recognizer) return
            if (reusable) {
                current.inUse = false
                return
            }
            client = null
        }
        scope.launch { recognizer.close() }
    }

    // one dictation. feed() is called from the capture thread and never blocks it
    @RequiresApi(MIN_SDK)
    class LiveSession(private val recognizer: SpeechRecognizer, mode: Int) {
        val label = if (mode == SpeechRecognizerOptions.Mode.MODE_ADVANCED) "ml kit advanced" else "ml kit basic"
        private val audio = Channel<ByteArray>(256)
        private val failed = AtomicBoolean(false)
        private val pipe = ParcelFileDescriptor.createPipe()
        private val sessionScope = CoroutineScope(SupervisorJob() + Dispatchers.IO + logFailures)
        // non-blocking so a recogniser that stops reading cannot wedge this coroutine
        private val writer = sessionScope.launch {
            try {
                val fd = pipe[1].fileDescriptor
                Os.fcntlInt(fd, OsConstants.F_SETFL, OsConstants.O_NONBLOCK)
                for (chunk in audio) {
                    var offset = 0
                    var lastWrite = SystemClock.elapsedRealtime()
                    while (offset < chunk.size) {
                        ensureActive()
                        try {
                            val written = Os.write(fd, chunk, offset, chunk.size - offset)
                            if (written > 0) {
                                offset += written
                                lastWrite = SystemClock.elapsedRealtime()
                            }
                        } catch (error: ErrnoException) {
                            if (error.errno != OsConstants.EAGAIN) throw error
                            delay(10)
                        }
                        if (SystemClock.elapsedRealtime() - lastWrite >= SPEECH_TIMEOUT_MS) {
                            throw IOException("On-device recognition stopped reading audio.")
                        }
                    }
                }
            } catch (error: Exception) {
                ensureActive()
                fail()
                throw error
            } finally {
                // end of stream is what makes the recogniser flush its last words
                runCatching { pipe[1].close() }
            }
        }
        @Volatile private var stopped = false
        private val transcript = sessionScope.async {
            try {
                val request = speechRecognizerRequest {
                    audioSource = AudioSource.fromPfd(pipe[0], AudioSource.Mode.STREAMING, PCM_16K_MONO)
                }
                collect(recognizer, request)
            } finally {
                withContext(NonCancellable) {
                    stopped = runCatching { withTimeout(2_000) { recognizer.stopRecognition() } }
                        .onFailure { Diagnostics.warn("mlkit", "could not stop recognition", it) }
                        .isSuccess
                }
            }
        }.also { task ->
            task.invokeOnCompletion { error ->
                release(recognizer, stopped && !failed.get() && (error == null || error is CancellationException))
            }
        }

        fun feed(buffer: ByteArray, count: Int) {
            val result = audio.trySend(buffer.copyOf(count))
            if (result.isFailure && !result.isClosed) fail()
        }

        // a full pipe during start and missing EOF acknowledgement during finish
        // both indicate that the cached recogniser can no longer serve this take
        fun finish(): String = try {
            audio.close()
            runBlocking { withTimeout(SPEECH_TIMEOUT_MS) { transcript.await() } }
        } catch (error: Exception) {
            fail()
            throw error
        } finally {
            cancel()
        }

        private fun fail() {
            if (!failed.compareAndSet(false, true)) return
            cancel()
        }

        fun cancel() {
            audio.cancel()
            sessionScope.cancel()
            runCatching { pipe[0].close() }
            runCatching { pipe[1].close() }
        }

        private suspend fun collect(recognizer: SpeechRecognizer, request: com.google.mlkit.genai.speechrecognition.SpeechRecognizerRequest): String {
            val text = StringBuilder()
            var partial = ""
            var completed = false
            recognizer.startRecognition(request).collect { response ->
                when (response) {
                    // the sdk supplies separators between final utterances
                    is SpeechRecognizerResponse.FinalTextResponse -> {
                        text.append(response.text)
                        partial = ""
                    }
                    is SpeechRecognizerResponse.PartialTextResponse -> partial = response.text
                    is SpeechRecognizerResponse.ErrorResponse -> throw response.e
                    is SpeechRecognizerResponse.CompletedResponse -> completed = true
                    else -> Unit
                }
            }
            if (!completed) throw IOException("Speech recognition did not finish the recording.")
            // some sessions end on a trailing partial instead of a last final result
            return text.append(partial).toString().trim()
        }
    }

    // the pipe needs fcntl (api 30), and the phone speech service this replaces needs 31
    const val MIN_SDK = 31
    private const val SPEECH_TIMEOUT_MS = 10_000L

    private val PCM_16K_MONO: AudioFormat = AudioFormat.Builder()
        .setSampleRate(16_000)
        .setChannelMask(AudioFormat.CHANNEL_IN_MONO)
        .setEncoding(AudioFormat.ENCODING_PCM_16BIT)
        .build()
}
