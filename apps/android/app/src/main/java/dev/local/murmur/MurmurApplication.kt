package dev.local.murmur

import android.app.Application
import java.io.File

// services like the bubble start without the activity, so diagnostics hook in here
class MurmurApplication : Application() {
    override fun onCreate() {
        super.onCreate()
        Diagnostics.install(this)
        LectureAudio.removeTemporaryRecordings(File(filesDir, "lectures"), cacheDir)
        runCatching {
            HistoryStore(this).use { history ->
                runCatching { history.recoverLectures() }
                    .onFailure { Diagnostics.warn("history", "could not recover saved lecture recordings", it) }
                history.pruneExpired()
            }
        }.onFailure { Diagnostics.warn("history", "could not apply recording retention", it) }
    }
}
