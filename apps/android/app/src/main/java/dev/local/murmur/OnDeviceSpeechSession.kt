package dev.local.murmur

import android.annotation.TargetApi
import android.content.Context
import android.content.Intent
import android.os.Build
import android.os.Bundle
import android.speech.RecognitionListener
import android.speech.RecognizerIntent
import android.speech.SpeechRecognizer

@TargetApi(31)
internal class OnDeviceSpeechSession(
    context: Context,
    private val onResult: (String) -> Unit,
    private val onError: () -> Unit,
    private val onSpeechEnd: () -> Unit = {},
) {
    private val recognizer: SpeechRecognizer
    private var active = true

    init {
        require(Build.VERSION.SDK_INT >= 31 && SpeechRecognizer.isOnDeviceRecognitionAvailable(context)) {
            "On-device recognition is unavailable on this phone."
        }
        recognizer = SpeechRecognizer.createOnDeviceSpeechRecognizer(context)
        recognizer.setRecognitionListener(object : RecognitionListener {
            override fun onReadyForSpeech(params: Bundle?) = Unit
            override fun onBeginningOfSpeech() = Unit
            override fun onRmsChanged(rmsdB: Float) = Unit
            override fun onBufferReceived(buffer: ByteArray?) = Unit
            override fun onEndOfSpeech() = onSpeechEnd()
            override fun onPartialResults(partialResults: Bundle?) = Unit
            override fun onEvent(eventType: Int, params: Bundle?) = Unit
            override fun onError(error: Int) {
                if (active) {
                    close()
                    this@OnDeviceSpeechSession.onError()
                }
            }
            override fun onResults(results: Bundle?) {
                if (!active) return
                val text = results?.getStringArrayList(SpeechRecognizer.RESULTS_RECOGNITION)
                    ?.firstOrNull()?.trim().orEmpty()
                close()
                if (text.isBlank()) this@OnDeviceSpeechSession.onError()
                else this@OnDeviceSpeechSession.onResult(text)
            }
        })
    }

    fun start() {
        recognizer.startListening(Intent(RecognizerIntent.ACTION_RECOGNIZE_SPEECH).apply {
            putExtra(RecognizerIntent.EXTRA_LANGUAGE_MODEL, RecognizerIntent.LANGUAGE_MODEL_FREE_FORM)
            putExtra(RecognizerIntent.EXTRA_PARTIAL_RESULTS, false)
        })
    }

    fun stop() = recognizer.stopListening()

    fun close() {
        if (!active) return
        active = false
        recognizer.cancel()
        recognizer.destroy()
    }
}
