package dev.local.murmur

import android.Manifest
import android.accessibilityservice.AccessibilityService
import android.content.BroadcastReceiver
import android.content.Context
import android.content.Intent
import android.content.IntentFilter
import android.content.pm.PackageManager
import android.graphics.PixelFormat
import android.graphics.Rect
import android.graphics.drawable.GradientDrawable
import android.os.Build
import android.os.Handler
import android.os.Looper
import android.os.SystemClock
import android.text.InputType
import android.text.TextUtils
import android.view.Gravity
import android.view.MotionEvent
import android.view.View
import android.view.ViewConfiguration
import android.view.WindowInsets
import android.view.WindowManager
import android.view.accessibility.AccessibilityEvent
import android.view.accessibility.AccessibilityNodeInfo
import android.view.accessibility.AccessibilityWindowInfo
import android.view.inputmethod.EditorInfo
import android.widget.ImageView
import android.widget.LinearLayout
import android.widget.TextView
import java.io.File
import java.util.concurrent.Executors
import java.util.concurrent.Future
import kotlin.math.roundToInt

class MurmurAccessibilityService : AccessibilityService() {
    private val mainHandler = Handler(Looper.getMainLooper())
    private val worker = Executors.newSingleThreadExecutor()
    private val settings by lazy { AppSettings(this) }
    private val history by lazy { HistoryStore(this) }
    private val windowManager by lazy { getSystemService(Context.WINDOW_SERVICE) as WindowManager }
    private var bubble: LinearLayout? = null
    private var bubbleParams: WindowManager.LayoutParams? = null
    private var bubbleAttached = false
    private var bubbleIcon: ImageView? = null
    private var bubbleTitle: TextView? = null
    private var cancelButton: TextView? = null
    private var bubbleTouchActive = false
    private var bubbleDragging = false
    private var touchStartX = 0f
    private var touchStartY = 0f
    private var windowStartX = 0
    private var windowStartY = 0
    private var capture: PcmRecorder? = null
    private var onDeviceSession: OnDeviceSpeechSession? = null
    private var request: TranscriptionClient? = null
    @Volatile private var cleanupRequest: CleanupClient? = null
    private var requestTask: Future<*>? = null
    private var requestFile: File? = null
    private var recordingStartedAt = 0L
    private var recordingEndedAt = 0L
    @Volatile private var session = 0L
    private var mode = Mode.IDLE
    private var message = ""
    private var destroyed = false
    private var readyReceiverRegistered = false

    private val refreshBubble = Runnable { syncBubble() }
    private val refreshTimer = object : Runnable {
        override fun run() {
            if (mode == Mode.RECORDING) {
                renderBubble()
                mainHandler.postDelayed(this, 1_000)
            }
        }
    }
    private val clearMessage = Runnable {
        if (mode == Mode.MESSAGE) {
            mode = Mode.IDLE
            syncBubble()
        }
    }
    private val readyReceiver = object : BroadcastReceiver() {
        override fun onReceive(context: Context, intent: Intent) {
            if (intent.action != MurmurReadyService.ACTION_READY_STATE_CHANGED) return
            if (!MurmurReadyService.isActive) cancelCurrentWork()
            syncBubble()
        }
    }

    override fun onServiceConnected() {
        super.onServiceConnected()
        if (Build.VERSION.SDK_INT < Build.VERSION_CODES.TIRAMISU) return
        if (!readyReceiverRegistered) {
            registerReceiver(
                readyReceiver,
                IntentFilter(MurmurReadyService.ACTION_READY_STATE_CHANGED),
                Context.RECEIVER_NOT_EXPORTED,
            )
            readyReceiverRegistered = true
        }
        syncBubble()
    }

    override fun onAccessibilityEvent(event: AccessibilityEvent?) {
        if (Build.VERSION.SDK_INT < Build.VERSION_CODES.TIRAMISU || destroyed) return
        mainHandler.removeCallbacks(refreshBubble)
        mainHandler.post(refreshBubble)
        mainHandler.postDelayed(refreshBubble, 180)
    }

    override fun onInterrupt() {
        cancelCurrentWork()
        hideBubble()
    }

    override fun onDestroy() {
        destroyed = true
        session++
        cancelCurrentWork()
        mainHandler.removeCallbacksAndMessages(null)
        if (readyReceiverRegistered) unregisterReceiver(readyReceiver)
        readyReceiverRegistered = false
        hideBubble()
        worker.shutdownNow()
        super.onDestroy()
    }

    private fun syncBubble() {
        if (destroyed || Build.VERSION.SDK_INT < Build.VERSION_CODES.TIRAMISU || !bubbleAllowed()) {
            if (!destroyed && (capture != null || onDeviceSession != null || request != null || cleanupRequest != null)) cancelCurrentWork(false)
            hideBubble()
            return
        }
        if (activeEditorIsSensitive()) {
            if (capture != null || onDeviceSession != null || request != null || cleanupRequest != null) cancelCurrentWork(false)
            hideBubble()
            return
        }
        if (mode == Mode.IDLE && focusedEditable() == null) {
            hideBubble()
            return
        }
        showBubble()
        renderBubble()
    }

    private fun bubbleAllowed(): Boolean =
        getSharedPreferences("murmur", Context.MODE_PRIVATE).getBoolean("bubble_disclosure_accepted", false) &&
            MurmurReadyService.isActive

    private fun focusedEditable(): FocusTarget? {
        if (Build.VERSION.SDK_INT < Build.VERSION_CODES.TIRAMISU) return null
        val method = inputMethod ?: return null
        if (!method.currentInputStarted || method.currentInputConnection == null) return null
        val editor = method.currentInputEditorInfo ?: return null
        if (isSensitive(editor)) return null
        val node = windows.asSequence()
            .filter { it.type == AccessibilityWindowInfo.TYPE_APPLICATION && (it.isFocused || it.isActive) }
            .mapNotNull { it.root?.findFocus(AccessibilityNodeInfo.FOCUS_INPUT) }
            .firstOrNull { it.packageName?.toString() == editor.packageName }
            ?: return null
        if (!node.isEditable || node.isPassword || node.packageName?.toString() != editor.packageName ||
            node.packageName?.toString() == packageName
        ) return null
        return FocusTarget(node, EditorIdentity(editor.packageName, editor.fieldId, editor.inputType))
    }

    private fun activeEditorIsSensitive(): Boolean {
        if (Build.VERSION.SDK_INT < Build.VERSION_CODES.TIRAMISU) return false
        val editor = inputMethod?.currentInputEditorInfo
        if (editor != null && isSensitive(editor)) return true
        return windows.asSequence()
            .filter { it.type == AccessibilityWindowInfo.TYPE_APPLICATION && (it.isFocused || it.isActive) }
            .mapNotNull { it.root?.findFocus(AccessibilityNodeInfo.FOCUS_INPUT) }
            .any { it.isPassword }
    }

    private fun isSensitive(editor: EditorInfo): Boolean {
        if (editor.imeOptions and EditorInfo.IME_FLAG_NO_PERSONALIZED_LEARNING != 0) return true
        if (editor.inputType and InputType.TYPE_MASK_CLASS != InputType.TYPE_CLASS_TEXT) return true
        val variation = editor.inputType and InputType.TYPE_MASK_VARIATION
        return variation == InputType.TYPE_TEXT_VARIATION_PASSWORD ||
            variation == InputType.TYPE_TEXT_VARIATION_VISIBLE_PASSWORD ||
            variation == InputType.TYPE_TEXT_VARIATION_WEB_PASSWORD
    }

    private fun onBubblePressed() {
        when (mode) {
            Mode.RECORDING -> stopRecording()
            Mode.STOPPING -> Unit
            Mode.TRANSCRIBING, Mode.CLEANING -> cancelCurrentWork()
            Mode.IDLE, Mode.MESSAGE -> startRecording()
        }
    }

    private fun startRecording() {
        if (!bubbleAllowed()) {
            hideBubble()
            return
        }
        val target = focusedEditable() ?: run {
            showMessage("Tap a text field first")
            return
        }
        if (checkSelfPermission(Manifest.permission.RECORD_AUDIO) != PackageManager.PERMISSION_GRANTED) {
            showMessage("Allow microphone in Murmur")
            return
        }
        val cleanup = try { settings.cleanupEndpointOrNull() } catch (_: Exception) {
            showMessage("Check cleanup settings")
            return
        }
        if (settings.useOnDeviceRecognition) {
            startOnDevice(target, cleanup)
            return
        }
        val endpoint = try {
            settings.activeEndpointOrNull()
        } catch (_: Exception) {
            showMessage("Check Murmur settings")
            return
        }
        if (endpoint == null) {
            showMessage("Set a transcription endpoint")
            return
        }
        if (endpoint.uri.host.equals("openrouter.ai", ignoreCase = true) && endpoint.apiKey == null) {
            showMessage("Add your OpenRouter key")
            return
        }
        val recordingSession = ++session
        try {
            capture = PcmRecorder.start(this, cacheDir, settings.noiseSuppressionEnabled) { result ->
                capture = null
                mainHandler.removeCallbacks(refreshTimer)
                val file = result.getOrNull()
                if (recordingSession != session || destroyed || !bubbleAllowed()) {
                    file?.delete()
                    return@start
                }
                if (file == null) {
                    showMessage(result.exceptionOrNull()?.message ?: "Recording failed")
                    return@start
                }
                beginTranscription(endpoint, cleanup, file, recordingSession, target)
            }
            mode = Mode.RECORDING
            recordingStartedAt = SystemClock.elapsedRealtime()
            mainHandler.removeCallbacks(refreshTimer)
            mainHandler.post(refreshTimer)
            syncBubble()
        } catch (_: SecurityException) {
            showMessage("Microphone access was denied")
        } catch (_: Exception) {
            showMessage("Microphone could not start")
        }
    }

    private fun stopRecording() {
        if (mode != Mode.RECORDING) return
        mode = Mode.STOPPING
        mainHandler.removeCallbacks(refreshTimer)
        renderBubble()
        if (onDeviceSession != null) {
            if (recordingEndedAt == 0L) recordingEndedAt = SystemClock.elapsedRealtime()
            onDeviceSession?.stop()
        } else capture?.stop()
    }

    private fun startOnDevice(target: FocusTarget, cleanup: CleanupEndpoint?) {
        val recordingSession = ++session
        recordingStartedAt = SystemClock.elapsedRealtime()
        recordingEndedAt = 0L
        try {
            val recognizer = OnDeviceSpeechSession(this, settings.speechLanguage, onResult = { raw ->
                onDeviceSession = null
                if (recordingSession != session || destroyed) return@OnDeviceSpeechSession
                mainHandler.removeCallbacks(refreshTimer)
                mode = Mode.TRANSCRIBING
                renderBubble()
                val durationMs = (recordingEndedAt.takeIf { it > 0L } ?: SystemClock.elapsedRealtime()) - recordingStartedAt
                requestTask = worker.submit {
                    processTranscript(raw, cleanup, recordingSession, target, durationMs, null)
                }
            }, onError = {
                onDeviceSession = null
                if (recordingSession == session) showMessage("On-device recognition failed")
            }, onSpeechEnd = {
                if (recordingEndedAt == 0L) recordingEndedAt = SystemClock.elapsedRealtime()
            })
            onDeviceSession = recognizer
            recognizer.start()
            mode = Mode.RECORDING
            mainHandler.post(refreshTimer)
            syncBubble()
        } catch (_: Exception) {
            onDeviceSession?.close()
            onDeviceSession = null
            showMessage("On-device recognition unavailable")
        }
    }

    private fun beginTranscription(
        endpoint: TranscriptionEndpoint,
        cleanup: CleanupEndpoint?,
        file: File,
        recordingSession: Long,
        target: FocusTarget,
    ) {
        val client = TranscriptionClient()
        request = client
        requestFile = file
        mode = Mode.TRANSCRIBING
        renderBubble()
        requestTask = worker.submit {
            try {
                val raw = client.transcribe(endpoint, file)
                val durationMs = ((file.length() - 44).coerceAtLeast(0) * 1000 / 32_000)
                processTranscript(raw, cleanup, recordingSession, target, durationMs, client)
            } catch (error: Exception) {
                mainHandler.post {
                    if (request === client && recordingSession == session && !destroyed) {
                        showMessage(if (error is HttpStatusException) "Server returned HTTP ${error.statusCode}"
                            else error.message ?: "Transcription failed")
                    }
                }
            } finally {
                if (endpoint.localModel != null) LocalWhisper.release()
                file.delete()
                mainHandler.post {
                    if (request === client) {
                        request = null
                        cleanupRequest = null
                        requestTask = null
                        requestFile = null
                    }
                }
            }
        }
    }

    private fun processTranscript(
        raw: String, cleanup: CleanupEndpoint?, recordingSession: Long,
        target: FocusTarget, durationMs: Long, client: TranscriptionClient?,
    ) {
        if (recordingSession != session || (client != null && request !== client)) return
        val cleaner = cleanup?.let { CleanupClient().also { cleanupRequest = it } }
        if (recordingSession != session || destroyed) {
            cleaner?.cancel()
            return
        }
        if (cleaner != null) mainHandler.post {
            if (recordingSession == session) {
                mode = Mode.CLEANING
                renderBubble()
            }
        }
        var cleanupFailure: String? = null
        val prepared = settings.prepareTranscript(raw)
        val cleaned = try { cleanup?.let { cleaner?.clean(it, prepared) } } catch (error: Exception) {
            cleanupFailure = if (error is HttpStatusException) "Last cleanup failed (HTTP ${error.statusCode}); raw text was used."
                else "Last cleanup failed; raw text was used."
            null
        }
        val transcript = cleaned ?: prepared
        mainHandler.post {
            if (recordingSession != session || destroyed || (client != null && request !== client)) return@post
            val historyId = runCatching { history.add(raw, transcript, durationMs) }.getOrNull()
            settings.saveTranscript(raw, transcript, historyId)
            if (cleaner != null) settings.lastCleanupFailure = cleanupFailure
            cleanupRequest = null
            if (client == null) requestTask = null
            val suffix = if (cleaner != null && cleaned == null) " · online cleanup failed" else ""
            showMessage((if (insertAtCursor(target, transcript)) "Sent to field · saved" else "Saved in Murmur · field changed") + suffix)
        }
    }

    private fun insertAtCursor(target: FocusTarget, transcript: String): Boolean {
        if (Build.VERSION.SDK_INT < Build.VERSION_CODES.TIRAMISU || !bubbleAllowed()) return false
        val current = focusedEditable() ?: return false
        if (current.node != target.node || current.editor != target.editor) return false
        val connection = inputMethod?.currentInputConnection ?: return false
        return try {
            val context = connection.getSurroundingText(1, 0, 0)
            val before = context?.text?.take(context.selectionStart.coerceAtLeast(0))?.lastOrNull()
            val leadingSpace = before?.isLetterOrDigit() == true && transcript.firstOrNull()?.isLetterOrDigit() == true
            connection.commitText(if (leadingSpace) " $transcript" else transcript, 1, null)
            true
        } catch (_: RuntimeException) {
            false
        }
    }

    private fun cancelCurrentWork(updateBubble: Boolean = true) {
        session++
        capture?.cancel()
        capture = null
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
        mainHandler.removeCallbacks(refreshTimer)
        mainHandler.removeCallbacks(clearMessage)
        mode = Mode.IDLE
        if (updateBubble) syncBubble()
    }

    private fun showMessage(text: String) {
        message = text
        mode = Mode.MESSAGE
        syncBubble()
        mainHandler.removeCallbacks(clearMessage)
        mainHandler.postDelayed(clearMessage, 3_000)
    }

    private fun showBubble() {
        if (bubble == null) createBubble()
        val view = bubble ?: return
        if (!bubbleAttached) {
            val params = WindowManager.LayoutParams(
                WindowManager.LayoutParams.WRAP_CONTENT,
                WindowManager.LayoutParams.WRAP_CONTENT,
                WindowManager.LayoutParams.TYPE_ACCESSIBILITY_OVERLAY,
                WindowManager.LayoutParams.FLAG_NOT_FOCUSABLE or WindowManager.LayoutParams.FLAG_NOT_TOUCH_MODAL,
                PixelFormat.TRANSLUCENT,
            ).apply {
                gravity = Gravity.TOP or Gravity.START
            }
            try {
                windowManager.addView(view, params)
                bubbleParams = params
                bubbleAttached = true
                view.post { updateBubblePlacement() }
            } catch (_: WindowManager.BadTokenException) {
                return
            } catch (_: SecurityException) {
                return
            }
        } else {
            updateBubblePlacement()
        }
    }

    private fun hideBubble() {
        if (!bubbleAttached) return
        try {
            bubble?.let { windowManager.removeViewImmediate(it) }
        } catch (_: IllegalArgumentException) {
            // the system can remove an accessibility overlay before the service is destroyed
        }
        bubbleAttached = false
        bubbleParams = null
        bubbleTouchActive = false
        bubbleDragging = false
    }

    private data class BubbleBounds(val minX: Int, val maxX: Int, val minY: Int, val maxY: Int)

    private fun bubbleBounds(view: View): BubbleBounds {
        if (Build.VERSION.SDK_INT < Build.VERSION_CODES.TIRAMISU) return BubbleBounds(0, 0, 0, 0)
        val metrics = windowManager.currentWindowMetrics
        val screenWidth = metrics.bounds.width()
        val screenHeight = metrics.bounds.height()
        val topInset = metrics.windowInsets.getInsetsIgnoringVisibility(WindowInsets.Type.systemBars()).top
        val keyboard = windows.firstOrNull { it.type == AccessibilityWindowInfo.TYPE_INPUT_METHOD }
        val keyboardRect = Rect()
        keyboard?.getBoundsInScreen(keyboardRect)
        val keyboardTop = keyboardRect.top.takeIf { keyboard != null && it in 1 until screenHeight }
            ?: screenHeight - dp(72)
        val minX = dp(8)
        val minY = topInset + dp(8)
        return BubbleBounds(
            minX,
            (screenWidth - view.width - dp(8)).coerceAtLeast(minX),
            minY,
            (keyboardTop - view.height - dp(8)).coerceAtLeast(minY),
        )
    }

    private fun updateBubblePlacement() {
        if (!bubbleAttached || bubbleTouchActive) return
        val view = bubble ?: return
        val params = bubbleParams ?: return
        if (view.width == 0 || view.height == 0) return
        val bounds = bubbleBounds(view)
        val nextX = bounds.minX + ((bounds.maxX - bounds.minX) * settings.bubbleXFraction).roundToInt()
        val nextY = bounds.minY + ((bounds.maxY - bounds.minY) * settings.bubbleYFraction).roundToInt()
        if (params.x == nextX && params.y == nextY) return
        params.x = nextX
        params.y = nextY
        try {
            windowManager.updateViewLayout(view, params)
        } catch (_: IllegalArgumentException) {
            bubbleAttached = false
            bubbleParams = null
        }
    }

    private fun onBubbleTouch(view: View, event: MotionEvent): Boolean {
        val params = bubbleParams ?: return false
        when (event.actionMasked) {
            MotionEvent.ACTION_DOWN -> {
                bubbleTouchActive = true
                bubbleDragging = false
                touchStartX = event.rawX
                touchStartY = event.rawY
                windowStartX = params.x
                windowStartY = params.y
            }
            MotionEvent.ACTION_MOVE -> {
                val dx = event.rawX - touchStartX
                val dy = event.rawY - touchStartY
                val slop = ViewConfiguration.get(this).scaledTouchSlop
                if (!bubbleDragging && dx * dx + dy * dy > slop * slop) bubbleDragging = true
                if (bubbleDragging) {
                    val bounds = bubbleBounds(view)
                    params.x = (windowStartX + dx.roundToInt()).coerceIn(bounds.minX, bounds.maxX)
                    params.y = (windowStartY + dy.roundToInt()).coerceIn(bounds.minY, bounds.maxY)
                    windowManager.updateViewLayout(view, params)
                }
            }
            MotionEvent.ACTION_UP, MotionEvent.ACTION_CANCEL -> {
                val moved = bubbleDragging
                if (moved) {
                    val bounds = bubbleBounds(view)
                    settings.bubbleXFraction = if (bounds.maxX == bounds.minX) 0f
                        else (params.x - bounds.minX).toFloat() / (bounds.maxX - bounds.minX)
                    settings.bubbleYFraction = if (bounds.maxY == bounds.minY) 0f
                        else (params.y - bounds.minY).toFloat() / (bounds.maxY - bounds.minY)
                }
                bubbleTouchActive = false
                bubbleDragging = false
                if (!moved && event.actionMasked == MotionEvent.ACTION_UP) view.performClick()
            }
        }
        return true
    }

    private fun createBubble() {
        val surface = GradientDrawable().apply {
            setColor(getColor(R.color.murmur_surface))
            cornerRadius = dp(28).toFloat()
            setStroke(dp(1), getColor(R.color.murmur_border))
        }
        val root = LinearLayout(this).apply {
            orientation = LinearLayout.HORIZONTAL
            gravity = Gravity.CENTER_VERTICAL
            background = surface
            elevation = dp(8).toFloat()
            setPadding(dp(4), dp(4), dp(4), dp(4))
            isClickable = true
            setOnClickListener { onBubblePressed() }
            setOnTouchListener { view, event -> onBubbleTouch(view, event) }
            addOnLayoutChangeListener { view, left, top, right, bottom, oldLeft, oldTop, oldRight, oldBottom ->
                if (right - left != oldRight - oldLeft || bottom - top != oldBottom - oldTop) {
                    view.post { updateBubblePlacement() }
                }
            }
        }
        bubbleIcon = ImageView(this).apply {
            setImageResource(R.drawable.ic_microphone)
            imageTintList = android.content.res.ColorStateList.valueOf(getColor(R.color.murmur_on_accent))
            background = GradientDrawable().apply {
                shape = GradientDrawable.OVAL
                setColor(getColor(R.color.murmur_accent))
            }
            setPadding(dp(7), dp(7), dp(7), dp(7))
        }
        root.addView(bubbleIcon, LinearLayout.LayoutParams(dp(36), dp(36)))
        bubbleTitle = TextView(this).apply {
            textSize = 13f
            setTextColor(getColor(R.color.murmur_text))
            maxLines = 1
            maxWidth = dp(150)
            ellipsize = TextUtils.TruncateAt.END
            visibility = View.GONE
        }
        root.addView(bubbleTitle, LinearLayout.LayoutParams(
            LinearLayout.LayoutParams.WRAP_CONTENT,
            LinearLayout.LayoutParams.WRAP_CONTENT,
        ).apply { marginStart = dp(8); marginEnd = dp(4) })
        cancelButton = TextView(this).apply {
            text = "×"
            textSize = 24f
            gravity = Gravity.CENTER
            setTextColor(getColor(R.color.murmur_text))
            contentDescription = "Cancel dictation"
            visibility = View.GONE
            setOnClickListener { cancelCurrentWork() }
        }
        root.addView(cancelButton, LinearLayout.LayoutParams(dp(40), dp(40)))
        bubble = root
    }

    private fun renderBubble() {
        val title: String?
        val color: Int
        when (mode) {
            Mode.IDLE -> {
                title = null
                color = getColor(R.color.murmur_accent)
            }
            Mode.RECORDING -> {
                val elapsed = ((SystemClock.elapsedRealtime() - recordingStartedAt) / 1_000).coerceAtLeast(0)
                title = "%d:%02d  Done".format(elapsed / 60, elapsed % 60)
                color = getColor(R.color.murmur_recording)
            }
            Mode.STOPPING -> {
                title = "Finishing…"
                color = getColor(R.color.murmur_busy)
            }
            Mode.TRANSCRIBING -> {
                title = "Transcribing…"
                color = getColor(R.color.murmur_busy)
            }
            Mode.CLEANING -> {
                title = "Cleaning…"
                color = getColor(R.color.murmur_busy)
            }
            Mode.MESSAGE -> {
                title = message
                color = getColor(R.color.murmur_accent)
            }
        }
        bubbleTitle?.text = title
        bubbleTitle?.visibility = if (title == null) View.GONE else View.VISIBLE
        bubbleIcon?.background = GradientDrawable().apply {
            shape = GradientDrawable.OVAL
            setColor(color)
        }
        val size = settings.bubbleSizeDp
        bubble?.minimumWidth = dp(size)
        bubble?.minimumHeight = dp(size)
        bubbleIcon?.layoutParams?.let { params ->
            params.width = dp(size - 8)
            params.height = dp(size - 8)
            bubbleIcon?.layoutParams = params
        }
        bubble?.alpha = settings.bubbleOpacityPercent / 100f
        cancelButton?.visibility = if (mode == Mode.RECORDING || mode == Mode.STOPPING || mode == Mode.TRANSCRIBING || mode == Mode.CLEANING) View.VISIBLE else View.GONE
        bubble?.contentDescription = when (mode) {
            Mode.RECORDING -> "Murmur listening. Tap to finish dictation."
            Mode.TRANSCRIBING -> "Murmur transcribing. Tap to cancel."
            Mode.CLEANING -> "Murmur cleaning. Tap to cancel."
            Mode.STOPPING -> "Murmur finishing recording."
            Mode.MESSAGE -> "Murmur. $message"
            Mode.IDLE -> "Murmur. Tap to speak."
        }
        bubble?.post { updateBubblePlacement() }
    }

    private fun dp(value: Int): Int = (resources.displayMetrics.density * value).toInt()

    private data class EditorIdentity(val packageName: String?, val fieldId: Int, val inputType: Int)
    private data class FocusTarget(val node: AccessibilityNodeInfo, val editor: EditorIdentity)
    private enum class Mode { IDLE, RECORDING, STOPPING, TRANSCRIBING, CLEANING, MESSAGE }
}
