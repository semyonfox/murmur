package dev.local.murmur

import android.content.Context
import android.util.JsonReader
import org.json.JSONObject
import java.io.InputStreamReader
import java.nio.charset.StandardCharsets

internal object MigrationTransfer {
    data class Result(val added: Int, val keysUnavailable: Boolean)

    fun importFromOldApp(context: Context): Result {
        require(context.packageName == "ie.semyon.murmur")
        val descriptor = context.contentResolver.openFileDescriptor(MigrationProvider.SNAPSHOT_URI, "r")
            ?: error("The previous Murmur app is not ready to transfer data.")
        JsonReader(InputStreamReader(android.os.ParcelFileDescriptor.AutoCloseInputStream(descriptor), StandardCharsets.UTF_8)).use { reader ->
            reader.beginObject()
            require(reader.nextName() == "version" && reader.nextInt() == 1)
            require(reader.nextName() == "settings")
            val snapshot = JSONObject(reader.nextString())
            require(reader.nextName() == "dictations")
            val history = HistoryStore(context)
            val added = history.use { it.importMigrationRecords(reader) }
            reader.endObject()
            require(reader.peek() == android.util.JsonToken.END_DOCUMENT)
            val oldLatestId = snapshot.optLong("latest_history_id", 0L)
            val mappedId = if (oldLatestId > 0) HistoryStore(context).use { it.localIdForLegacy(oldLatestId) } else null
            AppSettings(context).restoreMigration(snapshot, mappedId)
            return Result(added, snapshot.optBoolean("speech_key_unreadable") || snapshot.optBoolean("cleanup_key_unreadable"))
        }
    }
}
