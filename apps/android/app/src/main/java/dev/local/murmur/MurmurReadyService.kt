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
import android.os.Build
import android.os.IBinder

internal class MurmurReadyService : Service() {
    override fun onBind(intent: Intent?): IBinder? = null

    override fun onStartCommand(intent: Intent?, flags: Int, startId: Int): Int {
        if (intent?.action == ACTION_STOP) {
            updateReady(false)
            stopSelf()
            return START_NOT_STICKY
        }
        if (intent?.action != ACTION_START ||
            checkSelfPermission(Manifest.permission.RECORD_AUDIO) != PackageManager.PERMISSION_GRANTED
        ) {
            stopSelf()
            return START_NOT_STICKY
        }

        createNotificationChannel()
        try {
            if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.Q) {
                startForeground(NOTIFICATION_ID, notification(), ServiceInfo.FOREGROUND_SERVICE_TYPE_MICROPHONE)
            } else {
                startForeground(NOTIFICATION_ID, notification())
            }
        } catch (_: SecurityException) {
            stopSelf()
            return START_NOT_STICKY
        } catch (_: IllegalStateException) {
            stopSelf()
            return START_NOT_STICKY
        }

        updateReady(true)
        return START_NOT_STICKY
    }

    override fun onDestroy() {
        updateReady(false)
        stopForeground(STOP_FOREGROUND_REMOVE)
        super.onDestroy()
    }

    private fun createNotificationChannel() {
        val manager = getSystemService(NotificationManager::class.java)
        manager.createNotificationChannel(
            NotificationChannel(CHANNEL_ID, "Voice dictation", NotificationManager.IMPORTANCE_LOW),
        )
    }

    private fun notification(): Notification {
        val openIntent = PendingIntent.getActivity(
            this,
            0,
            Intent(this, MainActivity::class.java),
            PendingIntent.FLAG_UPDATE_CURRENT or PendingIntent.FLAG_IMMUTABLE,
        )
        val stopIntent = PendingIntent.getService(
            this,
            0,
            Intent(this, MurmurReadyService::class.java).setAction(ACTION_STOP),
            PendingIntent.FLAG_UPDATE_CURRENT or PendingIntent.FLAG_IMMUTABLE,
        )
        return Notification.Builder(this, CHANNEL_ID)
            .setSmallIcon(android.R.drawable.ic_btn_speak_now)
            .setContentTitle("Murmur voice ready")
            .setContentText("Tap the popup in a text field to dictate")
            .setContentIntent(openIntent)
            .setCategory(Notification.CATEGORY_SERVICE)
            .setVisibility(Notification.VISIBILITY_PRIVATE)
            .setOngoing(true)
            .addAction(Notification.Action.Builder(null, "Stop", stopIntent).build())
            .build()
    }

    private fun updateReady(active: Boolean) {
        if (isActive == active) return
        isActive = active
        sendBroadcast(
            Intent(ACTION_READY_STATE_CHANGED)
                .setPackage(packageName)
                .putExtra(EXTRA_IS_ACTIVE, active),
        )
    }

    companion object {
        const val ACTION_READY_STATE_CHANGED = "ie.semyon.murmur.action.READY_STATE_CHANGED"
        const val EXTRA_IS_ACTIVE = "is_active"

        private const val ACTION_START = "ie.semyon.murmur.action.START_READY"
        private const val ACTION_STOP = "ie.semyon.murmur.action.STOP_READY"
        private const val CHANNEL_ID = "voice_ready"
        private const val NOTIFICATION_ID = 102

        @Volatile
        var isActive = false
            private set

        fun start(context: Context) {
            context.startForegroundService(
                Intent(context, MurmurReadyService::class.java).setAction(ACTION_START),
            )
        }

        fun stop(context: Context) {
            context.stopService(Intent(context, MurmurReadyService::class.java))
        }
    }
}
