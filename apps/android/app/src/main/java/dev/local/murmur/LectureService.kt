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
import android.net.Uri
import android.os.Build
import android.os.IBinder
import java.io.File
import java.text.DateFormat
import java.util.Date

internal class LectureService : Service() {
    private val history by lazy { HistoryStore(this) }
    private val settings by lazy { AppSettings(this) }
    private var recorder: PcmRecorder? = null
    private var worker: Thread? = null
    @Volatile private var activeId = 0L
    @Volatile private var destroyed = false

    override fun onBind(intent: Intent?): IBinder? = null

    override fun onStartCommand(intent: Intent?, flags: Int, startId: Int): Int {
        when (intent?.action) {
            ACTION_STOP -> if (recorder == null) stopSelf() else recorder?.stop()
            ACTION_RECORD -> {
                if (isActive) return START_NOT_STICKY
                if (checkSelfPermission(Manifest.permission.RECORD_AUDIO) != PackageManager.PERMISSION_GRANTED) {
                    publish("Microphone permission is needed.", false)
                    stopSelf()
                    return START_NOT_STICKY
                }
                if (!beginForeground("Recording lecture", ServiceInfo.FOREGROUND_SERVICE_TYPE_MICROPHONE)) return START_NOT_STICKY
                runCatching {
                    requireEndpoint()
                    val capture = PcmRecorder.start(this, File(filesDir, "lectures"), settings.noiseSuppressionEnabled,
                        maxSeconds = 28_800, preserveOnFailure = true, onComplete = ::recordingComplete)
                    try {
                        activeId = history.addLecture(capture.file,
                            DateFormat.getDateTimeInstance(DateFormat.MEDIUM, DateFormat.SHORT).format(Date()))
                        recorder = capture
                        publish("Recording lecture · tap Finish when done", true)
                    } catch (error: Exception) {
                        capture.cancel()
                        throw error
                    }
                }.onFailure { failStart(it) }
            }
            ACTION_IMPORT, ACTION_RETRY -> {
                if (isActive) return START_NOT_STICKY
                if (!beginForeground("Preparing lecture audio", ServiceInfo.FOREGROUND_SERVICE_TYPE_DATA_SYNC)) return START_NOT_STICKY
                publish("Preparing lecture audio…", true)
                val source = intent.data
                val retryId = intent.getLongExtra(EXTRA_ID, 0L)
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

    private fun recordingComplete(result: Result<File>) {
        recorder = null
        val id = activeId
        if (id == 0L) return
        if (destroyed) {
            history.lecture(id)?.let { history.updateLecture(id, it.raw, it.finalText,
                LectureAudio.durationMs(result.getOrNull() ?: history.lectureFile(it.fileName)), "failed") }
            return
        }
        result.onSuccess { file ->
            val duration = LectureAudio.durationMs(file)
            history.updateLecture(id, "", "", duration, "processing")
            if (!beginForeground("Transcribing lecture", ServiceInfo.FOREGROUND_SERVICE_TYPE_DATA_SYNC)) {
                fail(IllegalStateException("Could not continue transcription in the background. Retry from Lectures."))
                return@onSuccess
            }
            publish("Transcribing lecture…", true)
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
                publish("Transcribing part ${index + 1} of $total…", true)
                val piece = TranscriptionClient().transcribe(endpoint, chunk, allowEmpty = true)
                if (piece.isNotBlank()) {
                    val stamp = "[%02d:%02d] ".format((index * 120) / 60, (index * 120) % 60)
                    raw.append(stamp).append(piece).append('\n')
                    history.updateLecture(id, raw.toString().trim(), cleaned.toString().trim(), duration, "processing")
                    val prepared = settings.prepareTranscript(piece)
                    val finalPiece = if (cleanup == null) prepared else runCatching {
                        CleanupClient().clean(cleanup, prepared)
                    }.getOrElse { error ->
                        settings.lastCleanupFailure = error.message ?: "Cleanup failed."
                        prepared
                    }
                    cleaned.append(stamp).append(finalPiece).append('\n')
                    history.updateLecture(id, raw.toString().trim(), cleaned.toString().trim(), duration, "processing")
                }
            }
            history.updateLecture(id, raw.toString().trim(), cleaned.toString().trim(), duration, "done")
            publish("Lecture transcript ready", false)
        } catch (error: Exception) {
            fail(error)
        } finally {
            if (settings.localModelId != null) LocalWhisper.release()
            activeId = 0
            worker = null
            stopSelf()
        }
    }

    private fun requireEndpoint(): TranscriptionEndpoint {
        check(!settings.useOnDeviceRecognition) { "Select a downloaded model or endpoint in Models → Recognition for lectures." }
        return settings.activeEndpointOrNull() ?: error("Set up a speech model or transcription endpoint in Models → Recognition.")
    }

    private fun failStart(error: Throwable) {
        publish(error.message ?: "Could not start lecture recording.", false)
        stopSelf()
    }

    private fun fail(error: Throwable) {
        val id = activeId
        if (id != 0L) {
            history.lecture(id)?.let { history.updateLecture(id, it.raw, it.finalText, it.durationMs, "failed") }
        }
        publish(error.message ?: "Lecture could not be transcribed. Retry from Lectures.", false)
        activeId = 0
        stopSelf()
    }

    private fun beginForeground(label: String, type: Int): Boolean {
        val manager = getSystemService(NotificationManager::class.java)
        manager.createNotificationChannel(NotificationChannel(CHANNEL_ID, "Lecture recording", NotificationManager.IMPORTANCE_LOW))
        return try {
            if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.Q) startForeground(NOTIFICATION_ID, notification(label), type)
            else startForeground(NOTIFICATION_ID, notification(label))
            true
        } catch (_: SecurityException) {
            publish("Android could not start the lecture service.", false)
            stopSelf()
            false
        } catch (_: IllegalStateException) {
            publish("Open Murmur to start a lecture.", false)
            stopSelf()
            false
        }
    }

    private fun notification(label: String): Notification {
        val open = PendingIntent.getActivity(this, 0, Intent(this, MainActivity::class.java),
            PendingIntent.FLAG_UPDATE_CURRENT or PendingIntent.FLAG_IMMUTABLE)
        val builder = Notification.Builder(this, CHANNEL_ID)
            .setSmallIcon(R.drawable.ic_murmur_notification)
            .setContentTitle("Murmur lecture")
            .setContentText(label)
            .setContentIntent(open)
            .setCategory(Notification.CATEGORY_SERVICE)
            .setVisibility(Notification.VISIBILITY_PRIVATE)
            .setOngoing(true)
        if (recorder != null || label.startsWith("Recording")) {
            val finish = PendingIntent.getService(this, 1, Intent(this, LectureService::class.java).setAction(ACTION_STOP),
                PendingIntent.FLAG_UPDATE_CURRENT or PendingIntent.FLAG_IMMUTABLE)
            builder.addAction(Notification.Action.Builder(null, "Finish", finish).build())
        }
        return builder.build()
    }

    private fun publish(message: String, active: Boolean) {
        status = message
        isActive = active
        if (active) getSystemService(NotificationManager::class.java).notify(NOTIFICATION_ID, notification(message))
        sendBroadcast(Intent(ACTION_STATE_CHANGED).setPackage(packageName))
    }

    override fun onDestroy() {
        destroyed = true
        recorder?.stop()
        worker?.interrupt()
        if (isActive) publish("Lecture stopped. Retry from Lectures.", false)
        stopForeground(STOP_FOREGROUND_REMOVE)
        super.onDestroy()
    }

    override fun onTimeout(startId: Int, fgsType: Int) {
        worker?.interrupt()
        publish("Android stopped this lecture job. Retry from Lectures.", false)
        stopSelf()
    }

    companion object {
        const val ACTION_STATE_CHANGED = "ie.semyon.murmur.action.LECTURE_STATE_CHANGED"
        private const val ACTION_RECORD = "ie.semyon.murmur.action.LECTURE_RECORD"
        private const val ACTION_STOP = "ie.semyon.murmur.action.LECTURE_STOP"
        private const val ACTION_IMPORT = "ie.semyon.murmur.action.LECTURE_IMPORT"
        private const val ACTION_RETRY = "ie.semyon.murmur.action.LECTURE_RETRY"
        private const val EXTRA_ID = "lecture_id"
        private const val EXTRA_TITLE = "lecture_title"
        private const val CHANNEL_ID = "lecture"
        private const val NOTIFICATION_ID = 103

        @Volatile var isActive = false
            private set
        @Volatile var status = "Ready to record or import audio."
            private set

        fun record(context: Context) = context.startForegroundService(Intent(context, LectureService::class.java).setAction(ACTION_RECORD)).let { Unit }
        fun finish(context: Context) = context.startService(Intent(context, LectureService::class.java).setAction(ACTION_STOP)).let { Unit }
        fun import(context: Context, uri: Uri, title: String) = context.startForegroundService(
            Intent(context, LectureService::class.java).setAction(ACTION_IMPORT).setData(uri)
                .addFlags(Intent.FLAG_GRANT_READ_URI_PERMISSION).putExtra(EXTRA_TITLE, title),
        ).let { Unit }
        fun retry(context: Context, id: Long) = context.startForegroundService(
            Intent(context, LectureService::class.java).setAction(ACTION_RETRY).putExtra(EXTRA_ID, id),
        ).let { Unit }
    }
}
