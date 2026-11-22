package dev.local.murmur

import android.app.Application

// services like the bubble start without the activity, so diagnostics hook in here
class MurmurApplication : Application() {
    override fun onCreate() {
        super.onCreate()
        Diagnostics.install(this)
        runCatching { HistoryStore(this).use { history ->
            history.removeOrphanedRecordings()
            history.pruneExpired()
        } }
            .onFailure { Diagnostics.warn("history", "could not apply recording retention", it) }
    }
}
