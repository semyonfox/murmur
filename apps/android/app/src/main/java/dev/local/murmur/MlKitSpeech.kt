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

    private class Client(val locale: Locale, val mode: Int, val recognizer: SpeechRecognizer)

    // regional preference extensions such as en-IE-u-mu-celsius make every mode unavailable
    fun localeFor(language: String): Locale =
        if (language == "auto") Locale.getDefault().stripExtensions() else Locale.forLanguageTag(language)

    // the warmed client for this language, or null so the caller keeps the older recogniser.
    // never blocks, prewarm fills it
    @Synchronized
    fun cached(language: String): Ready? =
        if (Build.VERSION.SDK_INT < MIN_SDK) null else client?.takeIf { it.locale == localeFor(language) }?.let { Ready(it.recognizer, it.mode) }

    // true once prewarm found no usable mode for this language
    fun isUnsupported(language: String) = unsupported == localeFor(language)

    // finds a usable mode, downloads the system model if the phone offers one, then loads it
    // so the first dictation does not pay for it. the weights come from google play services,
    // no user audio is involved. call when the user has chosen phone recognition
    fun prewarm(language: String) {
        val locale = localeFor(language)
        if (Build.VERSION.SDK_INT < MIN_SDK || cached(language) != null || unsupported == locale || !prewarming.compareAndSet(false, true)) return
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

    class Ready internal constructor(internal val recognizer: SpeechRecognizer, val mode: Int) {
        val label get() = if (mode == SpeechRecognizerOptions.Mode.MODE_ADVANCED) "ml kit advanced" else "ml kit basic"
    }

    // one dictation. feed() is called from the capture thread and never blocks it
    @RequiresApi(MIN_SDK)
    class LiveSession(private val ready: Ready) {
        private val audio = Channel<ByteArray>(Channel.UNLIMITED)
        private val pipe = ParcelFileDescriptor.createPipe()
        private val sessionScope = CoroutineScope(SupervisorJob() + Dispatchers.IO + logFailures)
        // non-blocking so a recogniser that stops reading cannot wedge this coroutine
        private val writer = sessionScope.launch {
            val fd = pipe[1].fileDescriptor
            Os.fcntlInt(fd, OsConstants.F_SETFL, OsConstants.O_NONBLOCK)
            try {
                for (chunk in audio) {
                    var offset = 0
                    while (offset < chunk.size) {
                        ensureActive()
                        try {
                            offset += Os.write(fd, chunk, offset, chunk.size - offset)
                        } catch (error: ErrnoException) {
                            if (error.errno != OsConstants.EAGAIN) throw error
                            delay(10)
                        }
                    }
                }
            } finally {
                // end of stream is what makes the recogniser flush its last words
                runCatching { pipe[1].close() }
            }
        }
        private val transcript = sessionScope.async {
            val request = speechRecognizerRequest {
                audioSource = AudioSource.fromPfd(pipe[0], AudioSource.Mode.STREAMING, PCM_16K_MONO)
            }
            try {
                collect(ready.recognizer, request)
            } finally {
                withContext(NonCancellable) { runCatching { ready.recognizer.stopRecognition() } }
            }
        }

        fun feed(buffer: ByteArray, count: Int) {
            audio.trySend(buffer.copyOf(count))
        }

        // closes the stream and waits for the recogniser to flush. call off the main thread
        fun finish(): String = try {
            audio.close()
            runBlocking { transcript.await() }
        } finally {
            cancel()
        }

        fun cancel() {
            audio.close()
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

    private val PCM_16K_MONO: AudioFormat = AudioFormat.Builder()
        .setSampleRate(16_000)
        .setChannelMask(AudioFormat.CHANNEL_IN_MONO)
        .setEncoding(AudioFormat.ENCODING_PCM_16BIT)
        .build()
}
