package dev.local.murmur

import android.Manifest
import android.content.Context
import android.content.Intent
import android.content.pm.PackageManager
import android.content.res.ColorStateList
import android.graphics.drawable.GradientDrawable
import android.graphics.drawable.RippleDrawable
import android.inputmethodservice.InputMethodService
import android.os.Handler
import android.os.Looper
import android.text.InputType
import android.view.KeyEvent
import android.view.View
import android.view.inputmethod.EditorInfo
import android.view.inputmethod.InputMethodManager
import android.widget.Button
import android.widget.LinearLayout
import android.widget.TextView
import java.io.File
import java.util.concurrent.Executors
import java.util.concurrent.Future

class MurmurInputMethodService : InputMethodService() {
    private val mainHandler = Handler(Looper.getMainLooper())
    private val worker = Executors.newSingleThreadExecutor()
    private val letterButtons = mutableListOf<Pair<Button, Char>>()
    private lateinit var settings: AppSettings
    private val history by lazy { HistoryStore(this) }
    private var statusView: TextView? = null
    private var micButton: Button? = null
    private var shiftButton: Button? = null
    private var shifted = false
    @Volatile private var session = 0L
    private var editor: EditorIdentity? = null
    private var capture: PcmRecorder? = null
    private var onDeviceSession: OnDeviceSpeechSession? = null
    private var onDeviceRecordingEndedAt = 0L
    private var request: TranscriptionClient? = null
    @Volatile private var cleanupRequest: CleanupClient? = null
    private var requestTask: Future<*>? = null
    private var requestFile: File? = null

    override fun onCreate() {
        super.onCreate()
        settings = AppSettings(this)
        cacheDir.listFiles { file -> file.name.startsWith("murmur-capture-") && file.name.endsWith(".wav") }
            ?.forEach { it.delete() }
    }

    override fun onCreateInputView(): View {
        letterButtons.clear()
        val root = LinearLayout(this).apply {
            orientation = LinearLayout.VERTICAL
            setPadding(dp(4), dp(6), dp(4), dp(8))
            setBackgroundColor(getColor(R.color.murmur_keyboard))
        }
        val toolbar = LinearLayout(this).apply { orientation = LinearLayout.HORIZONTAL }
        statusView = TextView(this).apply {
            text = "Murmur"
            textSize = 14f
            setTextColor(getColor(R.color.murmur_muted))
            setPadding(dp(8), 0, dp(8), 0)
            gravity = android.view.Gravity.CENTER_VERTICAL
            maxLines = 2
        }
        toolbar.addView(statusView, LinearLayout.LayoutParams(0, dp(50), 1f))
        micButton = key("Mic", 1.1f, accent = true) { onMicPressed() }.also { toolbar.addView(it) }
        toolbar.addView(key("Settings", 1.3f) { openSettings() })
        root.addView(toolbar)

        listOf("qwertyuiop", "asdfghjkl", "zxcvbnm").forEachIndexed { rowIndex, letters ->
            val row = LinearLayout(this).apply { orientation = LinearLayout.HORIZONTAL }
            if (rowIndex == 2) {
                shiftButton = key("Shift", 1.5f) { toggleShift() }.also { row.addView(it) }
            }
            letters.forEach { letter ->
                val button = key(letter.toString(), 1f) { commitLetter(letter) }
                letterButtons += button to letter
                row.addView(button)
            }
            if (rowIndex == 2) row.addView(key("⌫", 1.5f) { currentInputConnection?.deleteSurroundingTextInCodePoints(1, 0) })
            root.addView(row)
        }
        val bottom = LinearLayout(this).apply { orientation = LinearLayout.HORIZONTAL }
        bottom.addView(key("Switch", 1f) {
            (getSystemService(Context.INPUT_METHOD_SERVICE) as InputMethodManager).showInputMethodPicker()
        })
        bottom.addView(key(",", 1f) { currentInputConnection?.commitText(",", 1) })
        bottom.addView(key("Space", 4f) { currentInputConnection?.commitText(" ", 1) })
        bottom.addView(key(".", 1f) { currentInputConnection?.commitText(".", 1) })
        bottom.addView(key("↵", 1f) {
            currentInputConnection?.sendKeyEvent(KeyEvent(KeyEvent.ACTION_DOWN, KeyEvent.KEYCODE_ENTER))
            currentInputConnection?.sendKeyEvent(KeyEvent(KeyEvent.ACTION_UP, KeyEvent.KEYCODE_ENTER))
        })
        root.addView(bottom)
        refreshVoiceStatus()
        return root
    }

    override fun onStartInput(attribute: EditorInfo?, restarting: Boolean) {
        super.onStartInput(attribute, restarting)
        session++
        cancelCurrentWork()
        editor = attribute?.let(::identityOf)
        refreshVoiceStatus()
    }

    override fun onStartInputView(info: EditorInfo?, restarting: Boolean) {
        super.onStartInputView(info, restarting)
        refreshVoiceStatus()
    }

    override fun onFinishInputView(finishingInput: Boolean) {
        session++
        cancelCurrentWork()
        super.onFinishInputView(finishingInput)
    }

    override fun onFinishInput() {
        session++
        cancelCurrentWork()
        editor = null
        super.onFinishInput()
    }

    override fun onDestroy() {
        session++
        cancelCurrentWork()
        worker.shutdownNow()
        super.onDestroy()
    }

    private fun onMicPressed() {
        if (onDeviceSession != null) {
            if (onDeviceRecordingEndedAt == 0L) onDeviceRecordingEndedAt = android.os.SystemClock.elapsedRealtime()
            micButton?.isEnabled = false
            showStatus("Finishing on-device recognition…")
            onDeviceSession?.stop()
            return
        }
        if (capture != null) {
            micButton?.isEnabled = false
            showStatus("Finishing recording…")
            capture?.stop()
            return
        }
        if (request != null || requestTask != null) {
            cancelTranscription()
            refreshVoiceStatus()
            return
        }
        val currentEditor = currentInputEditorInfo ?: return
        if (isSensitive(currentEditor)) {
            showStatus("Voice is off in this field.")
            return
        }
        if (checkSelfPermission(Manifest.permission.RECORD_AUDIO) != PackageManager.PERMISSION_GRANTED) {
            showStatus("Allow microphone in the Murmur app.")
            return
        }
        val cleanup = try { settings.cleanupEndpointOrNull() } catch (_: Exception) {
            showStatus("Check cleanup settings in Murmur.")
            return
        }
        val recordingSession = session
        val recordingEditor = identityOf(currentEditor)
        if (settings.useOnDeviceRecognition) {
            startOnDevice(cleanup, recordingSession, recordingEditor)
            return
        }
        val endpoint = try {
            settings.endpointOrNull()
        } catch (_: Exception) {
            showStatus("Check the saved endpoint or key in Murmur.")
            return
        }
        if (endpoint == null) {
            showStatus("Set a transcription endpoint in Murmur.")
            return
        }
        if (endpoint.uri.host.equals("openrouter.ai", ignoreCase = true) && endpoint.apiKey == null) {
            showStatus("Add your OpenRouter key in Murmur.")
            return
        }
        try {
            capture = PcmRecorder.start(this, cacheDir, settings.noiseSuppressionEnabled) { result ->
                capture = null
                micButton?.isEnabled = true
                val file = result.getOrNull()
                if (recordingSession != session || recordingEditor != editor || !isInputViewShown) {
                    file?.delete()
                    return@start
                }
                if (file == null) {
                    showStatus(result.exceptionOrNull()?.message ?: "Recording failed.")
                    return@start
                }
                beginTranscription(endpoint, cleanup, file, recordingSession, recordingEditor)
            }
            micButton?.text = "Stop"
            showStatus("Recording… Tap Stop to transcribe.")
        } catch (_: SecurityException) {
            showStatus("Microphone access was denied.")
        } catch (error: Exception) {
            showStatus(error.message ?: "Microphone could not start.")
        }
    }

    private fun startOnDevice(cleanup: CleanupEndpoint?, recordingSession: Long, recordingEditor: EditorIdentity) {
        val startedAt = android.os.SystemClock.elapsedRealtime()
        onDeviceRecordingEndedAt = 0L
        try {
            val recognizer = OnDeviceSpeechSession(this, onResult = { raw ->
                onDeviceSession = null
                if (recordingSession != session) return@OnDeviceSpeechSession
                micButton?.isEnabled = true
                micButton?.text = "Cancel"
                showStatus("Processing transcript…")
                val durationMs = (onDeviceRecordingEndedAt.takeIf { it > 0L }
                    ?: android.os.SystemClock.elapsedRealtime()) - startedAt
                requestTask = worker.submit {
                    processTranscript(raw, cleanup, recordingSession, recordingEditor, durationMs, null)
                }
            }, onError = {
                onDeviceSession = null
                micButton?.isEnabled = true
                micButton?.text = "Mic"
                if (recordingSession == session) showStatus("On-device recognition failed.")
            }, onSpeechEnd = {
                if (onDeviceRecordingEndedAt == 0L) onDeviceRecordingEndedAt = android.os.SystemClock.elapsedRealtime()
            })
            onDeviceSession = recognizer
            recognizer.start()
            micButton?.text = "Stop"
            showStatus("Listening on this phone… Tap Stop to finish.")
        } catch (_: Exception) {
            onDeviceSession?.close()
            onDeviceSession = null
            showStatus("On-device recognition unavailable.")
        }
    }

    private fun beginTranscription(endpoint: TranscriptionEndpoint, cleanup: CleanupEndpoint?, file: File, recordingSession: Long, recordingEditor: EditorIdentity) {
        val client = TranscriptionClient()
        request = client
        requestFile = file
        micButton?.text = "Cancel"
        showStatus("Transcribing…")
        requestTask = worker.submit {
            try {
                val raw = client.transcribe(endpoint, file)
                val durationMs = ((file.length() - 44).coerceAtLeast(0) * 1000 / 32_000)
                processTranscript(raw, cleanup, recordingSession, recordingEditor, durationMs, client)
            } catch (error: Exception) {
                mainHandler.post {
                    if (request === client) showStatus(
                        if (error is HttpStatusException) error.message ?: "Transcription failed."
                        else "Transcription failed. Check the endpoint and connection."
                    )
                }
            } finally {
                file.delete()
                mainHandler.post {
                    if (request === client) {
                        request = null
                        cleanupRequest = null
                        requestTask = null
                        requestFile = null
                        micButton?.text = "Mic"
                    }
                }
            }
        }
    }

    private fun processTranscript(
        raw: String, cleanup: CleanupEndpoint?, recordingSession: Long,
        recordingEditor: EditorIdentity, durationMs: Long, client: TranscriptionClient?,
    ) {
        if (recordingSession != session || (client != null && request !== client)) return
        val cleaner = cleanup?.let { CleanupClient().also { cleanupRequest = it } }
        if (recordingSession != session) {
            cleaner?.cancel()
            return
        }
        if (cleaner != null) mainHandler.post { if (recordingSession == session) showStatus("Cleaning transcript…") }
        var cleanupFailure: String? = null
        val cleaned = try { cleanup?.let { cleaner?.clean(it, raw) } } catch (error: Exception) {
            cleanupFailure = if (error is HttpStatusException) "Last cleanup failed (HTTP ${error.statusCode}); raw text was used."
                else "Last cleanup failed; raw text was used."
            null
        }
        val transcript = cleaned ?: raw
        mainHandler.post {
            if (recordingSession != session || (client != null && request !== client)) return@post
            val historyId = runCatching { history.add(raw, transcript, durationMs) }.getOrNull()
            settings.saveTranscript(raw, cleaned, historyId)
            if (cleaner != null) settings.lastCleanupFailure = cleanupFailure
            cleanupRequest = null
            if (client == null) requestTask = null
            micButton?.isEnabled = true
            micButton?.text = "Mic"
            if (recordingEditor == editor &&
                recordingEditor == currentInputEditorInfo?.let(::identityOf) && isInputViewShown
            ) {
                val connection = currentInputConnection
                val before = connection?.getTextBeforeCursor(1, 0)
                val leadingSpace = before?.lastOrNull()?.isLetterOrDigit() == true &&
                    transcript.firstOrNull()?.isLetterOrDigit() == true
                val inserted = connection?.commitText(if (leadingSpace) " $transcript" else transcript, 1) == true
                val status = if (inserted) "Inserted. Copy stays in Murmur." else "Saved in Murmur. Insertion failed."
                showStatus(if (cleaner != null && cleaned == null) "$status Cleanup failed; raw used." else status)
            } else showStatus("Saved in Murmur. Editor changed.")
        }
    }

    private fun cancelCurrentWork() {
        capture?.cancel()
        capture = null
        cancelTranscription()
        micButton?.isEnabled = true
        micButton?.text = "Mic"
    }

    private fun cancelTranscription() {
        onDeviceSession?.close()
        onDeviceSession = null
        request?.cancel()
        cleanupRequest?.cancel()
        requestTask?.cancel(true)
        requestFile?.delete()
        request = null
        cleanupRequest = null
        requestTask = null
        requestFile = null
        micButton?.text = "Mic"
    }

    private fun refreshVoiceStatus() {
        val current = currentInputEditorInfo
        showStatus(
            when {
                current == null -> "Murmur"
                isSensitive(current) -> "Voice off in this field"
                checkSelfPermission(Manifest.permission.RECORD_AUDIO) != PackageManager.PERMISSION_GRANTED -> "Allow microphone in Murmur app"
                settings.useOnDeviceRecognition -> "Tap Mic for on-device recognition"
                settings.endpointUrl == AppSettings.DEFAULT_ENDPOINT && !settings.hasApiKey -> "Add OpenRouter key in Murmur app"
                else -> "Tap Mic to dictate"
            }
        )
    }

    private fun isSensitive(info: EditorInfo): Boolean {
        if (info.imeOptions and EditorInfo.IME_FLAG_NO_PERSONALIZED_LEARNING != 0) return true
        if (info.inputType and InputType.TYPE_MASK_CLASS != InputType.TYPE_CLASS_TEXT) return true
        val variation = info.inputType and InputType.TYPE_MASK_VARIATION
        return variation == InputType.TYPE_TEXT_VARIATION_PASSWORD ||
            variation == InputType.TYPE_TEXT_VARIATION_VISIBLE_PASSWORD ||
            variation == InputType.TYPE_TEXT_VARIATION_WEB_PASSWORD
    }

    private fun openSettings() {
        cancelCurrentWork()
        startActivity(Intent(this, MainActivity::class.java).addFlags(Intent.FLAG_ACTIVITY_NEW_TASK))
    }

    private fun showStatus(message: String) {
        statusView?.text = message
    }

    private fun commitLetter(letter: Char) {
        currentInputConnection?.commitText(if (shifted) letter.uppercaseChar().toString() else letter.toString(), 1)
        if (shifted) toggleShift()
    }

    private fun toggleShift() {
        shifted = !shifted
        shiftButton?.text = if (shifted) "SHIFT" else "Shift"
        letterButtons.forEach { (button, letter) -> button.text = if (shifted) letter.uppercaseChar().toString() else letter.toString() }
    }

    private fun key(text: String, weight: Float, accent: Boolean = false, action: () -> Unit): Button = Button(this).apply {
        this.text = text
        textSize = 14f
        isAllCaps = false
        stateListAnimator = null
        setTextColor(getColor(if (accent) R.color.murmur_on_accent else R.color.murmur_text))
        val shape = GradientDrawable().apply {
            setColor(getColor(if (accent) R.color.murmur_accent else R.color.murmur_key))
            cornerRadius = dp(8).toFloat()
        }
        background = RippleDrawable(ColorStateList.valueOf(getColor(R.color.murmur_border)), shape, null)
        minWidth = 0
        minimumWidth = 0
        minHeight = 0
        minimumHeight = 0
        setPadding(0, 0, 0, 0)
        layoutParams = LinearLayout.LayoutParams(0, dp(49), weight).apply {
            setMargins(dp(2), dp(2), dp(2), dp(2))
        }
        setOnClickListener { action() }
    }

    private fun dp(value: Int): Int = (resources.displayMetrics.density * value).toInt()

    private fun identityOf(info: EditorInfo): EditorIdentity = EditorIdentity(info.packageName, info.fieldId, info.inputType)

    private data class EditorIdentity(val packageName: String?, val fieldId: Int, val inputType: Int)
}
