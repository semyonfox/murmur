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
import android.os.Handler
import android.os.IBinder
import android.os.Looper
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.flow.update
import java.io.File
import java.text.DateFormat
import java.util.Date
import java.util.concurrent.ExecutorService
import java.util.concurrent.Executors

internal data class LectureState(
    // the lecture being recorded, or 0
    val recordingId: Long = 0L,
    val recordedMs: Long = 0L,
    val paused: Boolean = false,
    // the lecture the worker is transcribing now. parts stays 0 while that lecture is
    // still being recorded, because its length is not known yet
    val transcribingId: Long = 0L,
    val part: Int = 0,
    val parts: Int = 0,
    // lectures with work waiting behind the current one
    val queuedIds: List<Long> = emptyList(),
    // imported files still being decoded
    val preparing: Int = 0,
    // set once when a recording ends normally, so the recorder can open its lecture
    val finishedRecordingId: Long = 0L,
    // the outcome of the last finished job, shown once
    val message: String? = null,
    val failed: Boolean = false,
) {
    val recording: Boolean get() = recordingId != 0L
    val busy: Boolean get() = recording || preparing > 0 || transcribingId != 0L || queuedIds.isNotEmpty()

    // recording, transcribing or waiting its turn
    fun isActive(id: Long): Boolean = id != 0L && (id == recordingId || id == transcribingId || id in queuedIds)
}

// records lectures and transcribes them on one worker thread. a recording is cut at
// pauses and each piece is transcribed while recording continues, so a transcript is
// ready soon after the lecture ends. imports and retries queue behind live pieces,
// and a new lecture can be recorded while older ones are still transcribing
internal class LectureService : Service() {
    private val history by lazy { HistoryStore(this) }
    private val settings by lazy { AppSettings(this) }
    private val mainHandler = Handler(Looper.getMainLooper())
    private val worker: ExecutorService = Executors.newSingleThreadExecutor { Thread(it, "murmur-lecture") }
    private val cleanupWorker: ExecutorService = Executors.newSingleThreadExecutor { Thread(it, "murmur-lecture-cleanup") }
    // lecture id to the number of its tasks still queued or running
    private val pending = LinkedHashMap<Long, Int>()
    private val speechClient = TranscriptionClient()
    private var recorder: PcmRecorder? = null
    private var recordingJob: Job? = null
    private var foregroundType = 0
    @Volatile private var destroyed = false

    // both workers take the job lock when saving a transcript snapshot
    private class Job(
        val id: Long,
        endpoint: TranscriptionEndpoint,
        val cleanup: CleanupEndpoint?,
        val retry: Boolean = false,
    ) {
        @Volatile var endpoint = endpoint
        val raw = StringBuilder()
        val cleaned = mutableListOf<String>()
        @Volatile var durationMs = 0L
        @Volatile var pieces = 0
        @Volatile var recordingDone = false
        @Volatile var discarded = false
        @Volatile var failure: Throwable? = null
    }

    override fun onBind(intent: Intent?): IBinder? = null

    override fun onStartCommand(intent: Intent?, flags: Int, startId: Int): Int {
        when (intent?.action) {
            ACTION_STOP -> recorder?.let { capture ->
                capture.resume()
                capture.stop()
            } ?: stopIfIdle()
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
                val job = recordingJob
                recordingJob = null
                recorder = null
                job?.discarded = true
                capture.cancel()
                if (job != null) runCatching { history.deleteLecture(job.id) }
                    .onFailure { Diagnostics.warn("lecture", "could not delete discarded lecture", it) }
                clearLevels()
                mutable.update { it.copy(recordingId = 0L, recordedMs = 0L, paused = false,
                    message = "Recording discarded.", failed = false) }
                stopIfIdle()
            }
            ACTION_RECORD -> startRecording()
            ACTION_IMPORT -> startImport(intent)
            ACTION_RETRY -> startRetry(intent.getLongExtra(EXTRA_ID, 0L), intent.getStringExtra(EXTRA_LANGUAGE))
            else -> stopIfIdle()
        }
        return START_NOT_STICKY
    }

    private fun startRecording() {
        if (recorder != null) return
        if (checkSelfPermission(Manifest.permission.RECORD_AUDIO) != PackageManager.PERMISSION_GRANTED) {
            finishWith("Microphone permission is needed.", failed = true)
            return
        }
        if (!enterForeground(ServiceInfo.FOREGROUND_SERVICE_TYPE_MICROPHONE)) return
        clearLevels()
        runCatching {
            val endpoint = requireEndpoint()
            val cleanup = settings.cleanupEndpointOrNull()
            lateinit var job: Job
            // pieces of at least a minute, cut at the first pause after that and never
            // longer than two, keep whisper's context and the timestamps useful
            val capture = PcmRecorder.start(this, File(filesDir, "lectures"), settings.noiseSuppressionEnabled,
                maxSeconds = 28_800, preserveOnFailure = true,
                onChunk = { piece, offsetMs -> livePiece(job, piece, offsetMs) }, chunkMinSeconds = 60, chunkMaxSeconds = 120,
                onLevel = ::onLevel, onComplete = { result -> recordingComplete(job, result) })
            try {
                val id = history.addLecture(capture.file,
                    DateFormat.getDateTimeInstance(DateFormat.MEDIUM, DateFormat.SHORT).format(Date()))
                job = Job(id, endpoint, cleanup)
                recordingJob = job
                recorder = capture
                mutable.update { it.copy(recordingId = id, recordedMs = 0L, paused = false, finishedRecordingId = 0L) }
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

    // runs on the main thread, in the order the recorder cut the pieces
    private fun livePiece(job: Job, piece: File, offsetMs: Long) {
        if (job.discarded) {
            piece.delete()
            return
        }
        val index = job.pieces++
        enqueue(job.id) {
            try {
                if (job.discarded || job.failure != null) return@enqueue
                showPart(job.id, index, if (job.recordingDone) job.pieces else 0)
                transcribeInto(job, piece, offsetMs)
                mutable.update { it.copy(part = index + 1) }
            } catch (error: Exception) {
                // the full recording is kept, so the lecture can be transcribed again from it
                if (!job.discarded) job.failure = error
            } finally {
                piece.delete()
            }
        }
    }

    private fun recordingComplete(job: Job, result: Result<File>) {
        if (recordingJob === job) {
            recordingJob = null
            recorder = null
        }
        clearLevels()
        if (job.discarded) return
        mutable.update { it.copy(recordingId = 0L, recordedMs = 0L, paused = false,
            finishedRecordingId = if (result.isSuccess) job.id else 0L) }
        if (destroyed) {
            markFailed(job.id, result.getOrNull()?.let(LectureAudio::durationMs))
            return
        }
        result.onSuccess { file ->
            job.durationMs = LectureAudio.durationMs(file)
            job.recordingDone = true
            // a short lecture never reached a cut, so nothing was transcribed live
            if (job.pieces == 0) enqueue(job.id) { transcribeWhole(job, file) }
            else enqueue(job.id) { finishLive(job) }
        }.onFailure { error ->
            enqueue(job.id) { fail(job, error) }
        }
        stopIfIdle()
    }

    private fun startImport(intent: Intent) {
        if (!enterForeground(workType())) return
        val source = intent.data
        val title = intent.getStringExtra(EXTRA_TITLE).orEmpty()
        mutable.update { it.copy(preparing = it.preparing + 1) }
        notifyCurrent()
        // decoding runs beside the worker, so a long import does not hold up live pieces
        Thread({
            var imported: File? = null
            try {
                val endpoint = requireEndpoint()
                val cleanup = settings.cleanupEndpointOrNull()
                val uri = source ?: error("No audio file selected.")
                val file = File.createTempFile("lecture-", ".wav", File(filesDir, "lectures").apply { mkdirs() })
                imported = file
                LectureAudio.import(this, uri, file)
                val id = history.addLecture(file, title.ifBlank { "Imported audio" }, "processing")
                imported = null
                val job = Job(id, endpoint, cleanup).apply { durationMs = LectureAudio.durationMs(file) }
                mainHandler.post { enqueue(id) { transcribeWhole(job, file) } }
            } catch (error: Exception) {
                imported?.delete()
                Diagnostics.error("lecture", "import failed", error)
                mutable.update { it.copy(failed = true, message = describeFailure(error, "Audio could not be imported")) }
            } finally {
                mutable.update { it.copy(preparing = (it.preparing - 1).coerceAtLeast(0)) }
                mainHandler.post { stopIfIdle() }
            }
        }, "murmur-lecture-import").start()
    }

    private fun startRetry(id: Long, language: String?) {
        if (!enterForeground(workType())) return
        runCatching {
            require(id > 0) { "Lecture was not found." }
            check(!state.value.isActive(id)) { "This lecture is already being transcribed." }
            val record = history.lecture(id) ?: error("Lecture was not found.")
            val file = history.lectureFile(record.fileName)
            require(file.exists()) { "Saved audio is missing." }
            val endpoint = requireEndpoint().let { if (language != null) it.withLanguage(language) else it }
            val job = Job(id, endpoint, settings.cleanupEndpointOrNull(), retry = true)
                .apply { durationMs = LectureAudio.recoverRecording(file) }
            enqueue(id) { transcribeWhole(job, file) }
        }.onFailure { error ->
            Diagnostics.error("lecture", "retry could not start", error)
            mutable.update { it.copy(failed = true, message = describeFailure(error, "Could not transcribe again")) }
            stopIfIdle()
        }
    }

    private fun enqueue(id: Long, executor: ExecutorService = worker, task: () -> Unit) {
        synchronized(pending) {
            if (destroyed || executor.isShutdown) return
            pending[id] = (pending[id] ?: 0) + 1
            executor.execute {
                try {
                    task()
                } finally {
                    synchronized(pending) {
                        val left = pending.getValue(id) - 1
                        if (left > 0) pending[id] = left else pending.remove(id)
                        if (!destroyed) {
                            if (left == 0) mutable.update {
                                if (it.transcribingId == id) it.copy(transcribingId = 0L, part = 0, parts = 0) else it
                            }
                            publishQueue()
                        }
                    }
                    mainHandler.post { if (!destroyed) stopIfIdle() }
                }
            }
            publishQueue()
        }
    }

    // called with the pending lock held
    private fun publishQueue() {
        mutable.update { current -> current.copy(queuedIds = pending.keys.filter { it != current.transcribingId }) }
        notifyCurrent()
    }

    private fun showPart(id: Long, index: Int, total: Int) = synchronized(pending) {
        mutable.update {
            it.copy(transcribingId = id, part = index, parts = total, queuedIds = pending.keys.filter { queued -> queued != id })
        }
        notifyCurrent()
    }

    private val hasPendingWork: Boolean get() = synchronized(pending) { pending.isNotEmpty() }

    // the whole saved file in fixed parts, for imports, retries and short recordings
    private fun transcribeWhole(job: Job, file: File) {
        try {
            LectureAudio.forEachChunk(file, cacheDir) { chunk, index, total, offsetMs ->
                check(!job.discarded) { "Lecture was deleted." }
                showPart(job.id, index, total)
                transcribeInto(job, chunk, offsetMs)
                mutable.update { it.copy(part = index + 1) }
            }
            complete(job)
        } catch (error: Exception) {
            fail(job, error)
        }
    }

    private fun finishLive(job: Job) {
        val failure = job.failure
        if (failure != null) fail(job, failure) else complete(job)
    }

    private fun transcribeInto(job: Job, chunk: File, offsetMs: Long) {
        val piece = transcribePart(job, chunk)
        if (piece.isBlank()) return
        val stamp = LectureTranscript.stamp(offsetMs)
        val prepared = settings.prepareTranscript(piece)
        val part = synchronized(job) {
            if (destroyed || job.discarded) return
            job.raw.append(stamp).append(' ').append(piece).append('\n')
            job.cleaned.add("$stamp $prepared")
            save(job, if (job.failure == null) "processing" else "failed")
            job.cleaned.lastIndex
        }
        val cleanup = job.cleanup ?: return
        enqueue(job.id, cleanupWorker) {
            if (destroyed || job.discarded || job.failure != null) return@enqueue
            val cleaned = runCatching { CleanupClient().clean(cleanup, prepared) }.getOrElse { error ->
                if (!destroyed && !job.discarded) {
                    settings.lastCleanupFailure = "Lecture cleanup failed (${describeFailure(error, "unknown error")}), uncleaned text was used."
                }
                return@enqueue
            }
            try {
                synchronized(job) {
                    if (destroyed || job.discarded || job.failure != null) return@enqueue
                    job.cleaned[part] = "$stamp $cleaned"
                    save(job, "processing")
                }
            } catch (error: Exception) {
                fail(job, error)
            }
        }
    }

    // keep the first detected language for the rest of the lecture
    private fun transcribePart(job: Job, chunk: File): String {
        var detected: String? = null
        val endpoint = job.endpoint
        val text = speechClient.transcribe(endpoint, chunk, allowEmpty = true) { detected = it }
        val code = detected
        if (code != null && text.isNotBlank() && endpoint.language == SpeechLanguages.AUTO) {
            job.endpoint = endpoint.withLanguage(code)
            Diagnostics.info("lecture", "lecture ${job.id} detected $code, keeping it for the rest")
        }
        return text
    }

    private fun save(job: Job, status: String) {
        if (job.discarded || (job.retry && status != "done")) return
        history.updateLecture(job.id, job.raw.toString().trim(), job.cleaned.joinToString("\n").trim(), job.durationMs, status)
    }

    private fun complete(job: Job) {
        if (job.cleanup == null) finishTranscript(job)
        else {
            enqueue(job.id, cleanupWorker) {
                try {
                    finishTranscript(job)
                } catch (error: Exception) {
                    fail(job, error)
                }
            }
            synchronized(pending) {
                if (destroyed) return
                mutable.update {
                    if (it.transcribingId == job.id) {
                        it.copy(transcribingId = 0L, part = 0, parts = 0, queuedIds = pending.keys.toList())
                    } else it
                }
                notifyCurrent()
            }
        }
    }

    private fun finishTranscript(job: Job) = synchronized(job) {
        if (destroyed || job.discarded || job.failure != null || history.lecture(job.id) == null) return@synchronized
        save(job, "done")
        mutable.update { it.copy(message = "Transcript ready.", failed = false) }
    }

    private fun fail(job: Job, error: Throwable): Unit = synchronized(job) {
        if (destroyed || job.discarded) return@synchronized
        job.failure = error
        Diagnostics.error("lecture", "lecture ${job.id} failed", error)
        if (!job.retry) markFailed(job.id, job.durationMs.takeIf { it > 0 })
        mainHandler.post {
            if (!destroyed && !job.discarded) {
                mutable.update { it.copy(failed = true,
                    message = describeFailure(error, "Lecture could not be transcribed").let { text ->
                        if ("Retry" in text) text else text.trimEnd('.') + ". Retry from the lecture."
                    }) }
            }
        }
    }

    private fun markFailed(id: Long, durationMs: Long?) {
        runCatching {
            history.lecture(id)?.let { history.updateLecture(id, it.raw, it.finalText, durationMs ?: it.durationMs, "failed") }
        }.onFailure { Diagnostics.warn("lecture", "could not mark lecture $id as failed", it) }
    }

    private fun onLevel(level: Float, recordedMs: Long) {
        mutable.update { if (it.recording) it.copy(recordedMs = recordedMs) else it }
        levelTrail.update { trail -> (if (trail.size >= LEVEL_HISTORY) trail.drop(1) else trail) + level }
    }

    private fun clearLevels() {
        levelTrail.value = emptyList()
    }

    private fun requireEndpoint(): TranscriptionEndpoint {
        check(!settings.useOnDeviceRecognition) { "Select a downloaded model or endpoint in Settings → Recognition for lectures." }
        return settings.activeEndpointOrNull() ?: error("Set up a speech model or transcription endpoint in Settings → Recognition.")
    }

    private fun finishWith(message: String, failed: Boolean) {
        mutable.update { it.copy(message = message, failed = failed) }
        stopIfIdle()
    }

    // the microphone type covers transcription during a recording. after it, the
    // queue keeps running under data sync, which android caps at a few hours a day
    private fun workType(): Int =
        if (recorder != null) ServiceInfo.FOREGROUND_SERVICE_TYPE_MICROPHONE else ServiceInfo.FOREGROUND_SERVICE_TYPE_DATA_SYNC

    // main thread only
    private fun stopIfIdle() {
        if (recorder != null || state.value.preparing > 0 || hasPendingWork) {
            if (foregroundType != workType() && !enterForeground(workType())) {
                Diagnostics.warn("lecture", "could not keep transcribing in the background")
            }
            return
        }
        // dictation keeps the model warm between takes, so let it go idle instead of freeing it now
        if (settings.localModelId != null) LocalWhisper.releaseWhenIdle()
        mutable.update { it.copy(transcribingId = 0L, part = 0, parts = 0, queuedIds = emptyList()) }
        stopForeground(STOP_FOREGROUND_REMOVE)
        foregroundType = 0
        stopSelf()
    }

    private fun enterForeground(type: Int): Boolean {
        val manager = getSystemService(NotificationManager::class.java)
        manager.createNotificationChannel(NotificationChannel(CHANNEL_ID, "Lecture recording", NotificationManager.IMPORTANCE_LOW))
        return try {
            if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.Q) startForeground(NOTIFICATION_ID, notification(), type)
            else startForeground(NOTIFICATION_ID, notification())
            foregroundType = type
            true
        } catch (error: SecurityException) {
            Diagnostics.error("lecture", "foreground start refused", error)
            mutable.update { it.copy(failed = true, message = "Android could not start the lecture service.") }
            if (foregroundType == 0) stopSelf()
            false
        } catch (error: IllegalStateException) {
            Diagnostics.error("lecture", "foreground start refused in the background", error)
            mutable.update { it.copy(failed = true, message = "Open Murmur to start a lecture.") }
            if (foregroundType == 0) stopSelf()
            false
        }
    }

    private fun notifyCurrent() {
        if (foregroundType == 0) return
        getSystemService(NotificationManager::class.java).notify(NOTIFICATION_ID, notification())
    }

    private fun serviceAction(action: String, code: Int) = PendingIntent.getService(this, code,
        Intent(this, LectureService::class.java).setAction(action),
        PendingIntent.FLAG_UPDATE_CURRENT or PendingIntent.FLAG_IMMUTABLE)

    private fun notification(): Notification {
        val current = state.value
        val openId = if (current.recording) current.recordingId else current.transcribingId
        val open = PendingIntent.getActivity(this, 0,
            Intent(this, MainActivity::class.java).putExtra(MainActivity.EXTRA_OPEN_LECTURE, openId)
                .addFlags(Intent.FLAG_ACTIVITY_SINGLE_TOP),
            PendingIntent.FLAG_UPDATE_CURRENT or PendingIntent.FLAG_IMMUTABLE)
        val builder = Notification.Builder(this, CHANNEL_ID)
            .setSmallIcon(R.drawable.ic_murmur_notification)
            .setContentIntent(open)
            .setCategory(Notification.CATEGORY_SERVICE)
            .setVisibility(Notification.VISIBILITY_PUBLIC)
            .setOnlyAlertOnce(true)
            .setOngoing(true)
        val waiting = current.queuedIds.count { it != current.recordingId }
        val queueNote = if (waiting > 0) " · $waiting more waiting" else ""
        when {
            current.recording -> {
                builder.setContentTitle(if (current.paused) "Lecture paused" else "Recording lecture")
                if (current.paused) {
                    builder.setContentText("Paused at ${LectureTranscript.clock(current.recordedMs)}")
                } else {
                    builder.setContentText("Transcribes as you go$queueNote")
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
            current.transcribingId != 0L -> builder.setContentTitle("Transcribing lecture")
                .setContentText((if (current.parts > 0) "Part ${minOf(current.part + 1, current.parts)} of ${current.parts}"
                    else "Finishing the last parts") + queueNote)
                .setProgress(current.parts.coerceAtLeast(1), current.part, current.parts == 0)
            current.preparing > 0 -> builder.setContentTitle("Preparing lecture audio").setProgress(0, 0, true)
            else -> builder.setContentTitle("Murmur lecture").setContentText(if (waiting > 0) "$waiting waiting" else "")
        }
        return builder.build()
    }

    override fun onDestroy() {
        destroyed = true
        speechClient.cancel()
        recorder?.stop()
        synchronized(pending) {
            worker.shutdownNow()
            cleanupWorker.shutdownNow()
        }
        if (state.value.busy) {
            Diagnostics.warn("lecture", "service destroyed with lecture work still running")
            mutable.value = LectureState(failed = true, message = "Lecture stopped. Retry from Lectures.")
        }
        clearLevels()
        stopForeground(STOP_FOREGROUND_REMOVE)
        super.onDestroy()
    }

    override fun onTimeout(startId: Int, fgsType: Int) {
        destroyed = true
        speechClient.cancel()
        Diagnostics.error("lecture", "android ended the foreground job (type $fgsType) at its time limit")
        synchronized(pending) {
            worker.shutdownNow()
            cleanupWorker.shutdownNow()
        }
        mutable.value = LectureState(failed = true, message = "Android stopped transcription for today. Retry from Lectures.")
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
        private const val EXTRA_LANGUAGE = "lecture_language"
        private const val CHANNEL_ID = "lecture"
        private const val NOTIFICATION_ID = 103

        private val mutable = MutableStateFlow(LectureState())
        val state: StateFlow<LectureState> = mutable.asStateFlow()
        private val levelTrail = MutableStateFlow<List<Float>>(emptyList())
        val levels: StateFlow<List<Float>> = levelTrail.asStateFlow()

        val isActive: Boolean get() = state.value.busy

        fun consumeMessage() = mutable.update { it.copy(message = null, failed = false) }
        fun consumeFinishedRecording() = mutable.update { it.copy(finishedRecordingId = 0L) }

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
        // language replaces the recognition language for this run only
        fun retry(context: Context, id: Long, language: String? = null) = context.startForegroundService(
            Intent(context, LectureService::class.java).setAction(ACTION_RETRY).putExtra(EXTRA_ID, id)
                .apply { if (language != null) putExtra(EXTRA_LANGUAGE, language) },
        ).let { }
    }
}
