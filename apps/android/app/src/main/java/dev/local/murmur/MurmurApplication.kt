package dev.local.murmur

import android.app.Application

// services like the bubble start without the activity, so diagnostics hook in here
class MurmurApplication : Application() {
    override fun onCreate() {
        super.onCreate()
        Diagnostics.install(this)
        runCatching { HistoryStore(this).use { it.removeOrphanedRecordings() } }
            .onFailure { Diagnostics.warn("history", "could not remove orphaned recordings", it) }
    }
}
