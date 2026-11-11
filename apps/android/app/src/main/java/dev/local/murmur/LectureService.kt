package dev.local.murmur

import android.Manifest
import android.app.Notification
import android.app.NotificationChannel
import android.app.NotificationManager
import android.app.PendingIntent
import android.app.Service
import android.content.Context
import android.content.Intent
import android.content.pm.PackageManager
import android.content.pm.ServiceInfo
import android.graphics.drawable.Icon
import android.net.Uri
import android.os.Build
import android.os.IBinder
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.flow.update
import java.io.File
import java.text.DateFormat
import java.util.Date

internal data class LectureState(
    val phase: Phase = Phase.IDLE,
    val lectureId: Long = 0L,
    val recordedMs: Long = 0L,
    val paused: Boolean = false,
    val part: Int = 0,
    val parts: Int = 0,
    // the outcome of the last job, shown once the service is idle again
    val message: String? = null,
    val failed: Boolean = false,
) {
    enum class Phase { IDLE, RECORDING, PREPARING, TRANSCRIBING }

    val busy: Boolean get() = phase != Phase.IDLE
}

internal class LectureService : Service() {
    private val history by lazy { HistoryStore(this) }
    private val settings by lazy { AppSettings(this) }
    private var recorder: PcmRecorder? = null
    private var worker: Thread? = null
    @Volatile private var activeId = 0L
    @Volatile private var destroyed = false
    @Volatile private var discarding = false

    override fun onBind(intent: Intent?): IBinder? = null

    override fun onStartCommand(intent: Intent?, flags: Int, startId: Int): Int {
        when (intent?.action) {
            ACTION_STOP -> if (recorder == null) stopSelf() else {
                recorder?.resume()
                recorder?.stop()
            }
            ACTION_PAUSE -> recorder?.let { capture ->
                capture.pause()
                mutable.update { it.copy(paused = true) }
                notifyCurrent()
            }
            ACTION_RESUME -> recorder?.let { capture ->
                capture.resume()
                mutable.update { it.copy(paused = false) }
                notifyCurrent()
            }
            ACTION_DISCARD -> recorder?.let { capture ->
                discarding = true
                capture.cancel()
                recorder = null
                val id = activeId
                activeId = 0L
                if (id != 0L) runCatching { history.deleteLecture(id) }
                    .onFailure { Diagnostics.warn("lecture", "could not delete discarded lecture", it) }
                clearLevels()
                mutable.value = LectureState(message = "Recording discarded.")
                stopSelf()
            }
            ACTION_RECORD -> {
                if (state.value.busy) return START_NOT_STICKY
                if (checkSelfPermission(Manifest.permission.RECORD_AUDIO) != PackageManager.PERMISSION_GRANTED) {
                    finishWith("Microphone permission is needed.", failed = true)
                    return START_NOT_STICKY
                }
                discarding = false
                mutable.value = LectureState(phase = LectureState.Phase.RECORDING)
                clearLevels()
                if (!beginForeground(ServiceInfo.FOREGROUND_SERVICE_TYPE_MICROPHONE)) return START_NOT_STICKY
                runCatching {
                    requireEndpoint()
                    val capture = PcmRecorder.start(this, File(filesDir, "lectures"), settings.noiseSuppressionEnabled,
                        maxSeconds = 28_800, preserveOnFailure = true, onLevel = ::onLevel, onComplete = ::recordingComplete)
                    try {
                        activeId = history.addLecture(capture.file,
                            DateFormat.getDateTimeInstance(DateFormat.MEDIUM, DateFormat.SHORT).format(Date()))
                        recorder = capture
                        mutable.update { it.copy(lectureId = activeId) }
                        notifyCurrent()
                    } catch (error: Exception) {
                        capture.cancel()
                        throw error
                    }
                }.onFailure { error ->
                    Diagnostics.error("lecture", "recording could not start", error)
                    finishWith(error.message ?: "Could not start lecture recording.", failed = true)
                }
            }
            ACTION_IMPORT, ACTION_RETRY -> {
                if (state.value.busy) return START_NOT_STICKY
                val retryId = intent.getLongExtra(EXTRA_ID, 0L)
                mutable.value = LectureState(phase = LectureState.Phase.PREPARING, lectureId = retryId)
                if (!beginForeground(ServiceInfo.FOREGROUND_SERVICE_TYPE_DATA_SYNC)) return START_NOT_STICKY
                val source = intent.data
                val title = intent.getStringExtra(EXTRA_TITLE).orEmpty()
                worker = Thread({
                    var imported: File? = null
                    try {
                        requireEndpoint()
                        val id = if (intent.action == ACTION_IMPORT) {
                            val uri = source ?: error("No audio file selected.")
                            val file = File.createTempFile("lecture-", ".wav", File(filesDir, "lectures").apply { mkdirs() })
                            imported = file
                            LectureAudio.import(this, uri, file)
                            history.addLecture(file, title.ifBlank { "Imported audio" }, "processing")
                        } else {
                            require(retryId > 0) { "Lecture was not found." }
                            val record = history.lecture(retryId) ?: error("Lecture was not found.")
                            require(history.lectureFile(record.fileName).exists()) { "Saved audio is missing." }
                            history.updateLecture(retryId, record.raw, record.finalText, record.durationMs, "processing")
                            retryId
                        }
                        activeId = id
                        imported = null
                        process(id)
                    } catch (error: Exception) {
                        imported?.delete()
                        fail(error)
                    }
                }, "murmur-lecture").also { it.start() }
            }
            else -> stopSelf()
        }
        return START_NOT_STICKY
    }

    private fun onLevel(level: Float, recordedMs: Long) {
        mutable.update { if (it.phase == LectureState.Phase.RECORDING) it.copy(recordedMs = recordedMs) else it }
        levelTrail.update { trail -> (if (trail.size >= LEVEL_HISTORY) trail.drop(1) else trail) + level }
    }

    private fun clearLevels() {
        levelTrail.value = emptyList()
    }

    private fun recordingComplete(result: Result<File>) {
        recorder = null
        clearLevels()
        val id = activeId
        if (id == 0L || discarding) return
        if (destroyed) {
            history.lecture(id)?.let { history.updateLecture(id, it.raw, it.finalText,
                LectureAudio.durationMs(result.getOrNull() ?: history.lectureFile(it.fileName)), "failed") }
            return
        }
        result.onSuccess { file ->
            val duration = LectureAudio.durationMs(file)
            history.updateLecture(id, "", "", duration, "processing")
            mutable.value = LectureState(phase = LectureState.Phase.TRANSCRIBING, lectureId = id, recordedMs = duration)
            if (!beginForeground(ServiceInfo.FOREGROUND_SERVICE_TYPE_DATA_SYNC)) {
                fail(IllegalStateException("Could not continue transcription in the background. Retry from Lectures."))
                return@onSuccess
            }
            worker = Thread({ process(id) }, "murmur-lecture").also { it.start() }
        }.onFailure { fail(it) }
    }

    private fun process(id: Long) {
        try {
            val record = history.lecture(id) ?: error("Lecture was deleted.")
            val file = history.lectureFile(record.fileName)
            val duration = LectureAudio.durationMs(file)
            val endpoint = requireEndpoint()
            val cleanup = settings.cleanupEndpointOrNull()
            val raw = StringBuilder()
            val cleaned = StringBuilder()
            LectureAudio.forEachChunk(file, cacheDir) { chunk, index, total ->
                mutable.value = LectureState(phase = LectureState.Phase.TRANSCRIBING, lectureId = id,
                    recordedMs = duration, part = index, parts = total)
                notifyCurrent()
                val piece = transcribePart(endpoint, chunk, index, total)
                if (piece.isNotBlank()) {
                    val stamp = LectureTranscript.stamp(index * LectureAudio.CHUNK_SECONDS * 1000L)
                    raw.append(stamp).append(' ').append(piece).append('\n')
                    history.updateLecture(id, raw.toString().trim(), cleaned.toString().trim(), duration, "processing")
                    val prepared = settings.prepareTranscript(piece)
                    val finalPiece = if (cleanup == null) prepared else runCatching {
                        CleanupClient().clean(cleanup, prepared)
                    }.getOrElse { error ->
                        settings.lastCleanupFailure = "Lecture cleanup failed (${describeFailure(error, "unknown error")}), raw text was used."
                        prepared
                    }
                    cleaned.append(stamp).append(' ').append(finalPiece).append('\n')
                    history.updateLecture(id, raw.toString().trim(), cleaned.toString().trim(), duration, "processing")
                }
                mutable.update { it.copy(part = index + 1) }
            }
            history.updateLecture(id, raw.toString().trim(), cleaned.toString().trim(), duration, "done")
            mutable.value = LectureState(lectureId = id, message = "Transcript ready.")
        } catch (error: Exception) {
            fail(error)
        } finally {
            if (settings.localModelId != null) LocalWhisper.release()
            activeId = 0
            worker = null
            stopSelf()
        }
    }

    // nobody is waiting on a lecture in real time, so a part gets a second chance
    // after a pause instead of failing the whole lecture on one bad request
    private fun transcribePart(endpoint: TranscriptionEndpoint, chunk: File, index: Int, total: Int): String {
        try {
            return TranscriptionClient().transcribe(endpoint, chunk, allowEmpty = true)
        } catch (error: java.io.IOException) {
            val permanent = error is HttpStatusException && error.statusCode in 400..499 && error.statusCode != 408 && error.statusCode != 429
            if (permanent || endpoint.localModel != null) throw error
            Diagnostics.warn("lecture", "part ${index + 1} of $total failed, retrying in 5 s", error)
            Thread.sleep(5_000)
            return TranscriptionClient().transcribe(endpoint, chunk, allowEmpty = true)
        }
    }

    private fun requireEndpoint(): TranscriptionEndpoint {
        check(!settings.useOnDeviceRecognition) { "Select a downloaded model or endpoint in Settings → Recognition for lectures." }
        return settings.activeEndpointOrNull() ?: error("Set up a speech model or transcription endpoint in Settings → Recognition.")
    }

    private fun fail(error: Throwable) {
        val id = activeId
        Diagnostics.error("lecture", "lecture $id failed", error)
        if (id != 0L) {
            history.lecture(id)?.let { history.updateLecture(id, it.raw, it.finalText, it.durationMs, "failed") }
        }
        activeId = 0
        mutable.value = LectureState(lectureId = id, failed = true,
            message = describeFailure(error, "Lecture could not be transcribed").let {
                if ("Retry" in it) it else it.trimEnd('.') + ". Retry from the lecture."
            })
        stopSelf()
    }

    private fun finishWith(message: String, failed: Boolean) {
        mutable.value = LectureState(message = message, failed = failed)
        stopSelf()
    }

    private fun beginForeground(type: Int): Boolean {
        val manager = getSystemService(NotificationManager::class.java)
        manager.createNotificationChannel(NotificationChannel(CHANNEL_ID, "Lecture recording", NotificationManager.IMPORTANCE_LOW))
        return try {
            if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.Q) startForeground(NOTIFICATION_ID, notification(), type)
            else startForeground(NOTIFICATION_ID, notification())
            true
        } catch (error: SecurityException) {
            Diagnostics.error("lecture", "foreground start refused", error)
            finishWith("Android could not start the lecture service.", failed = true)
            false
        } catch (error: IllegalStateException) {
            Diagnostics.error("lecture", "foreground start refused in the background", error)
            finishWith("Open Murmur to start a lecture.", failed = true)
            false
        }
    }

    private fun notifyCurrent() {
        if (!state.value.busy) return
        getSystemService(NotificationManager::class.java).notify(NOTIFICATION_ID, notification())
    }

    private fun serviceAction(action: String, code: Int) = PendingIntent.getService(this, code,
        Intent(this, LectureService::class.java).setAction(action),
        PendingIntent.FLAG_UPDATE_CURRENT or PendingIntent.FLAG_IMMUTABLE)

    private fun notification(): Notification {
        val current = state.value
        val open = PendingIntent.getActivity(this, 0,
            Intent(this, MainActivity::class.java).putExtra(MainActivity.EXTRA_OPEN_LECTURE, current.lectureId)
                .addFlags(Intent.FLAG_ACTIVITY_SINGLE_TOP),
            PendingIntent.FLAG_UPDATE_CURRENT or PendingIntent.FLAG_IMMUTABLE)
        val builder = Notification.Builder(this, CHANNEL_ID)
            .setSmallIcon(R.drawable.ic_murmur_notification)
            .setContentIntent(open)
            .setCategory(Notification.CATEGORY_SERVICE)
            .setVisibility(Notification.VISIBILITY_PUBLIC)
            .setOnlyAlertOnce(true)
            .setOngoing(true)
        when (current.phase) {
            LectureState.Phase.RECORDING -> {
                builder.setContentTitle(if (current.paused) "Lecture paused" else "Recording lecture")
                if (current.paused) {
                    builder.setContentText("Paused at ${LectureTranscript.clock(current.recordedMs)}")
                } else {
                    builder.setContentText("Tap to open the recorder")
                        .setUsesChronometer(true).setShowWhen(true)
                        .setWhen(System.currentTimeMillis() - current.recordedMs)
                }
                val toggle = if (current.paused) Notification.Action.Builder(
                    Icon.createWithResource(this, R.drawable.ic_microphone), "Resume", serviceAction(ACTION_RESUME, 2))
                else Notification.Action.Builder(
                    Icon.createWithResource(this, R.drawable.ic_pause), "Pause", serviceAction(ACTION_PAUSE, 3))
                builder.addAction(toggle.build())
                builder.addAction(Notification.Action.Builder(
                    Icon.createWithResource(this, R.drawable.ic_stop), "Finish", serviceAction(ACTION_STOP, 1)).build())
            }
            LectureState.Phase.PREPARING -> builder.setContentTitle("Preparing lecture audio")
                .setProgress(0, 0, true)
            LectureState.Phase.TRANSCRIBING -> builder.setContentTitle("Transcribing lecture")
                .setContentText(if (current.parts > 0) "Part ${minOf(current.part + 1, current.parts)} of ${current.parts}" else "Starting…")
                .setProgress(current.parts.coerceAtLeast(1), current.part, current.parts == 0)
            LectureState.Phase.IDLE -> builder.setContentTitle("Murmur lecture")
        }
        return builder.build()
    }

    override fun onDestroy() {
        destroyed = true
        recorder?.stop()
        worker?.interrupt()
        if (state.value.busy) {
            Diagnostics.warn("lecture", "service destroyed while ${state.value.phase.name.lowercase()}")
            mutable.value = LectureState(lectureId = state.value.lectureId, failed = true,
                message = "Lecture stopped. Retry from Lectures.")
        }
        clearLevels()
        stopForeground(STOP_FOREGROUND_REMOVE)
        super.onDestroy()
    }

    override fun onTimeout(startId: Int, fgsType: Int) {
        Diagnostics.error("lecture", "android ended the foreground job (type $fgsType) at its time limit")
        worker?.interrupt()
        mutable.value = LectureState(lectureId = state.value.lectureId, failed = true,
            message = "Android stopped this lecture job. Retry from Lectures.")
        stopSelf()
    }

    companion object {
        const val LEVEL_HISTORY = 160
        private const val ACTION_RECORD = "ie.semyon.murmur.action.LECTURE_RECORD"
        private const val ACTION_STOP = "ie.semyon.murmur.action.LECTURE_STOP"
        private const val ACTION_PAUSE = "ie.semyon.murmur.action.LECTURE_PAUSE"
        private const val ACTION_RESUME = "ie.semyon.murmur.action.LECTURE_RESUME"
        private const val ACTION_DISCARD = "ie.semyon.murmur.action.LECTURE_DISCARD"
        private const val ACTION_IMPORT = "ie.semyon.murmur.action.LECTURE_IMPORT"
        private const val ACTION_RETRY = "ie.semyon.murmur.action.LECTURE_RETRY"
        private const val EXTRA_ID = "lecture_id"
        private const val EXTRA_TITLE = "lecture_title"
        private const val CHANNEL_ID = "lecture"
        private const val NOTIFICATION_ID = 103

        private val mutable = MutableStateFlow(LectureState())
        val state: StateFlow<LectureState> = mutable.asStateFlow()
        private val levelTrail = MutableStateFlow<List<Float>>(emptyList())
        val levels: StateFlow<List<Float>> = levelTrail.asStateFlow()

        val isActive: Boolean get() = state.value.busy

        fun consumeMessage() = mutable.update { if (it.busy) it else it.copy(message = null, failed = false) }

        private fun command(context: Context, action: String) =
            context.startService(Intent(context, LectureService::class.java).setAction(action)).let { }

        fun record(context: Context) =
            context.startForegroundService(Intent(context, LectureService::class.java).setAction(ACTION_RECORD)).let { }
        fun finish(context: Context) = command(context, ACTION_STOP)
        fun pause(context: Context) = command(context, ACTION_PAUSE)
        fun resume(context: Context) = command(context, ACTION_RESUME)
        fun discard(context: Context) = command(context, ACTION_DISCARD)
        fun import(context: Context, uri: Uri, title: String) = context.startForegroundService(
            Intent(context, LectureService::class.java).setAction(ACTION_IMPORT).setData(uri)
                .addFlags(Intent.FLAG_GRANT_READ_URI_PERMISSION).putExtra(EXTRA_TITLE, title),
        ).let { }
        fun retry(context: Context, id: Long) = context.startForegroundService(
            Intent(context, LectureService::class.java).setAction(ACTION_RETRY).putExtra(EXTRA_ID, id),
        ).let { }
    }
}
