package dev.local.murmur

import android.annotation.TargetApi
import android.content.Context
import android.content.Intent
import android.os.Build
import android.os.Bundle
import android.os.Handler
import android.os.Looper
import android.speech.RecognitionListener
import android.speech.RecognizerIntent
import android.speech.SpeechRecognizer

@TargetApi(31)
internal class OnDeviceSpeechSession(
    context: Context,
    private val language: String = "auto",
    private val onResult: (String) -> Unit,
    private val onError: (code: Int) -> Unit,
    private val onSpeechEnd: () -> Unit = {},
) {
    private val recognizer: SpeechRecognizer
    private var active = true
    private var waitingForResult = false
    private val handler = Handler(Looper.getMainLooper())
    private val timeout = Runnable { fail(SpeechRecognizer.ERROR_CLIENT) }

    init {
        require(Build.VERSION.SDK_INT >= 31 && SpeechRecognizer.isOnDeviceRecognitionAvailable(context)) {
            "On-device recognition is unavailable on this phone."
        }
        recognizer = SpeechRecognizer.createOnDeviceSpeechRecognizer(context)
        recognizer.setRecognitionListener(object : RecognitionListener {
            override fun onReadyForSpeech(params: Bundle?) {
                if (!waitingForResult) handler.removeCallbacks(timeout)
            }
            override fun onBeginningOfSpeech() = Unit
            override fun onRmsChanged(rmsdB: Float) = Unit
            override fun onBufferReceived(buffer: ByteArray?) = Unit
            override fun onEndOfSpeech() {
                if (!active) return
                waitForResult()
                onSpeechEnd()
            }
            override fun onPartialResults(partialResults: Bundle?) = Unit
            override fun onEvent(eventType: Int, params: Bundle?) = Unit
            override fun onError(error: Int) = fail(error)
            override fun onResults(results: Bundle?) {
                if (!active) return
                val text = results?.getStringArrayList(SpeechRecognizer.RESULTS_RECOGNITION)
                    ?.firstOrNull()?.trim().orEmpty()
                close()
                if (text.isBlank()) this@OnDeviceSpeechSession.onError(NO_SPEECH)
                else this@OnDeviceSpeechSession.onResult(text)
            }
        })
    }

    fun start() {
        handler.postDelayed(timeout, TIMEOUT_MS)
        recognizer.startListening(Intent(RecognizerIntent.ACTION_RECOGNIZE_SPEECH).apply {
            putExtra(RecognizerIntent.EXTRA_LANGUAGE_MODEL, RecognizerIntent.LANGUAGE_MODEL_FREE_FORM)
            putExtra(RecognizerIntent.EXTRA_PARTIAL_RESULTS, false)
            if (language != "auto") putExtra(RecognizerIntent.EXTRA_LANGUAGE, language)
        })
    }

    fun stop() {
        if (!active) return
        waitForResult()
        try {
            recognizer.stopListening()
        } catch (_: RuntimeException) {
            fail(SpeechRecognizer.ERROR_CLIENT)
        }
    }

    private fun waitForResult() {
        if (waitingForResult) return
        waitingForResult = true
        handler.removeCallbacks(timeout)
        handler.postDelayed(timeout, TIMEOUT_MS)
    }

    private fun fail(code: Int) {
        if (!active) return
        close()
        onError(code)
    }

    fun close() {
        if (!active) return
        active = false
        handler.removeCallbacks(timeout)
        runCatching { recognizer.cancel() }
        runCatching { recognizer.destroy() }
    }

    companion object {
        private const val TIMEOUT_MS = 10_000L

        // the recogniser's own code for a take with nothing recognised
        const val NO_SPEECH = SpeechRecognizer.ERROR_NO_MATCH
    }
}
