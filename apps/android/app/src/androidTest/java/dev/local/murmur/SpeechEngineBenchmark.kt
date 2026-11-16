package dev.local.murmur

import android.content.Intent
import android.media.AudioFormat
import android.os.Bundle
import android.os.ParcelFileDescriptor
import android.os.SystemClock
import android.speech.RecognitionListener
import android.speech.RecognizerIntent
import android.speech.SpeechRecognizer
import android.util.Log
import androidx.annotation.RequiresApi
import androidx.test.ext.junit.runners.AndroidJUnit4
import androidx.test.filters.SdkSuppress
import androidx.test.platform.app.InstrumentationRegistry
import org.junit.Assume.assumeTrue
import org.junit.Test
import org.junit.runner.RunWith
import java.nio.ByteBuffer
import java.nio.ByteOrder
import java.util.concurrent.CountDownLatch
import java.util.concurrent.TimeUnit

// before/after comparison of the two on-device engines behind "phone speech service".
// both get the same clip in real time through their pcm inputs. latency is the time from the
// last audio byte (the moment a user taps stop) to the final text. run on a physical phone:
//   ./gradlew :app:connectedDebugAndroidTest -Pandroid.testInstrumentationRunnerArguments.class=dev.local.murmur.SpeechEngineBenchmark
//   adb logcat -s MurmurBench
@RunWith(AndroidJUnit4::class)
class SpeechEngineBenchmark {
    private val instrumentation = InstrumentationRegistry.getInstrumentation()
    private val app = instrumentation.targetContext
    private val pcm by lazy { readPcm(instrumentation.context.assets.open("jfk.wav").use { it.readBytes() }) }

    @Test
    // android 13 added the audio source extra that lets the service take the same pcm
    @SdkSuppress(minSdkVersion = 33)
    fun speechService() {
        assumeTrue("no on-device speech service", SpeechRecognizer.isOnDeviceRecognitionAvailable(app))
        report("speech service", List(TRIALS) { speechServiceTrial() })
    }

    @Test
    @SdkSuppress(minSdkVersion = MlKitSpeech.MIN_SDK)
    fun mlKit() {
        MlKitSpeech.prewarm(LANGUAGE)
        val started = SystemClock.elapsedRealtime()
        var ready = MlKitSpeech.cached(LANGUAGE)
        // the first run may download the model
        while (ready == null && !MlKitSpeech.isUnsupported(LANGUAGE) && SystemClock.elapsedRealtime() - started < 180_000) {
            Thread.sleep(250)
            ready = MlKitSpeech.cached(LANGUAGE)
        }
        assumeTrue("ml kit speech recognition is unavailable on this phone", ready != null)
        Log.i(TAG, "ml kit ready after ${SystemClock.elapsedRealtime() - started} ms (${ready!!.label})")
        report(ready.label, List(TRIALS) {
            val session = MlKitSpeech.LiveSession(ready)
            replay { buffer, count -> session.feed(buffer, count) }
            val stopped = SystemClock.elapsedRealtime()
            val text = session.finish()
            Trial(SystemClock.elapsedRealtime() - stopped, text)
        })
    }

    @RequiresApi(33)
    private fun speechServiceTrial(): Trial {
        val pipe = ParcelFileDescriptor.createPipe()
        val done = CountDownLatch(1)
        var text = ""
        var stopped = 0L
        var finished = 0L
        lateinit var recognizer: SpeechRecognizer
        instrumentation.runOnMainSync {
            recognizer = SpeechRecognizer.createOnDeviceSpeechRecognizer(app)
            recognizer.setRecognitionListener(object : RecognitionListener {
                override fun onResults(results: Bundle?) {
                    finished = SystemClock.elapsedRealtime()
                    text = results?.getStringArrayList(SpeechRecognizer.RESULTS_RECOGNITION)?.firstOrNull().orEmpty()
                    done.countDown()
                }
                override fun onError(error: Int) {
                    finished = SystemClock.elapsedRealtime()
                    text = "<error $error>"
                    done.countDown()
                }
                override fun onReadyForSpeech(params: Bundle?) = Unit
                override fun onBeginningOfSpeech() = Unit
                override fun onRmsChanged(rmsdB: Float) = Unit
                override fun onBufferReceived(buffer: ByteArray?) = Unit
                override fun onEndOfSpeech() = Unit
                override fun onPartialResults(partialResults: Bundle?) = Unit
                override fun onEvent(eventType: Int, params: Bundle?) = Unit
            })
            recognizer.startListening(Intent(RecognizerIntent.ACTION_RECOGNIZE_SPEECH).apply {
                putExtra(RecognizerIntent.EXTRA_LANGUAGE_MODEL, RecognizerIntent.LANGUAGE_MODEL_FREE_FORM)
                putExtra(RecognizerIntent.EXTRA_LANGUAGE, LANGUAGE)
                putExtra(RecognizerIntent.EXTRA_AUDIO_SOURCE, pipe[0])
                putExtra(RecognizerIntent.EXTRA_AUDIO_SOURCE_CHANNEL_COUNT, 1)
                putExtra(RecognizerIntent.EXTRA_AUDIO_SOURCE_ENCODING, AudioFormat.ENCODING_PCM_16BIT)
                putExtra(RecognizerIntent.EXTRA_AUDIO_SOURCE_SAMPLING_RATE, 16_000)
            })
        }
        ParcelFileDescriptor.AutoCloseOutputStream(pipe[1]).use { output ->
            // the service can close its end when it decides speech is over, same as with the mic
            runCatching { replay { buffer, count -> output.write(buffer, 0, count) } }
        }
        stopped = SystemClock.elapsedRealtime()
        instrumentation.runOnMainSync { recognizer.stopListening() }
        done.await(60, TimeUnit.SECONDS)
        instrumentation.runOnMainSync { recognizer.destroy() }
        pipe[0].close()
        return Trial(if (finished > 0) (finished - stopped).coerceAtLeast(0) else -1, text)
    }

    // writes 100 ms buffers on the wall clock, the way the microphone delivers them
    private fun replay(write: (ByteArray, Int) -> Unit) {
        val start = SystemClock.elapsedRealtime()
        var offset = 0
        while (offset < pcm.size) {
            val count = minOf(BUFFER_BYTES, pcm.size - offset)
            write(pcm.copyOfRange(offset, offset + count), count)
            offset += count
            val due = start + offset * 1000L / BYTES_PER_SECOND
            val wait = due - SystemClock.elapsedRealtime()
            if (wait > 0) Thread.sleep(wait)
        }
    }

    private fun report(engine: String, trials: List<Trial>) {
        val latencies = trials.map { it.latencyMs }.sorted()
        trials.forEachIndexed { index, trial ->
            Log.i(TAG, "$engine trial ${index + 1}: ${trial.latencyMs} ms, wer ${"%.1f".format(wer(trial.text) * 100)}%, \"${trial.text}\"")
        }
        Log.i(TAG, "$engine summary: median ${latencies[latencies.size / 2]} ms, " +
            "min ${latencies.first()} ms, max ${latencies.last()} ms, " +
            "mean wer ${"%.1f".format(trials.map { wer(it.text) }.average() * 100)}%")
    }

    private class Trial(val latencyMs: Long, val text: String)

    companion object {
        private const val TAG = "MurmurBench"
        private const val TRIALS = 5
        private const val LANGUAGE = "en-US"
        private const val BYTES_PER_SECOND = 32_000
        private const val BUFFER_BYTES = BYTES_PER_SECOND / 10
        private const val REFERENCE = "and so my fellow americans ask not what your country can do for you ask what you can do for your country"

        private fun words(text: String) = text.lowercase().replace(Regex("[^a-z' ]"), " ").split(' ').filter { it.isNotBlank() }

        fun wer(text: String): Double {
            val expected = words(REFERENCE)
            val actual = words(text)
            var previous = IntArray(actual.size + 1) { it }
            for (i in expected.indices) {
                val current = IntArray(actual.size + 1)
                current[0] = i + 1
                for (j in actual.indices) {
                    current[j + 1] = minOf(previous[j + 1] + 1, current[j] + 1, previous[j] + if (expected[i] == actual[j]) 0 else 1)
                }
                previous = current
            }
            return previous[actual.size].toDouble() / expected.size
        }

        // jfk.wav carries a LIST chunk before its data, so walk the chunks instead of assuming 44 bytes
        fun readPcm(wav: ByteArray): ByteArray {
            val buffer = ByteBuffer.wrap(wav).order(ByteOrder.LITTLE_ENDIAN)
            var offset = 12
            while (offset + 8 <= wav.size) {
                val id = String(wav, offset, 4, Charsets.US_ASCII)
                val size = buffer.getInt(offset + 4)
                if (id == "data") return wav.copyOfRange(offset + 8, minOf(wav.size, offset + 8 + size))
                offset += 8 + size + (size and 1)
            }
            error("no data chunk")
        }
    }
}
