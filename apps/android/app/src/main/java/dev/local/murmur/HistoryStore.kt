package dev.local.murmur

import android.content.ContentValues
import android.content.Context
import android.database.sqlite.SQLiteDatabase
import android.database.sqlite.SQLiteOpenHelper
import android.util.JsonWriter
import android.util.JsonReader
import java.util.Calendar
import java.util.TimeZone
import kotlin.math.roundToInt

internal data class DictationRecord(
    val id: Long,
    val timestamp: Long,
    val raw: String,
    val finalText: String,
    val durationMs: Long,
)

internal data class DictationStats(
    val totalWords: Int,
    val thisWeekWords: Int,
    val previousWeekWords: Int,
    val dictations: Int,
    val recordedMinutes: Double,
    val recordedWpm: Int?,
    val recordingsWithDuration: Int,
    val weekOverWeekPercent: Double?,
    val weeks: List<WeekStats>,
)

internal data class WeekStats(
    val weekStart: Long,
    val words: Int,
    val recordedWpm: Int?,
)

internal class HistoryStore(private val context: Context) : SQLiteOpenHelper(context, "history.db", null, 2) {
    override fun onCreate(db: SQLiteDatabase) {
        db.execSQL("""
            CREATE TABLE dictations (
                id INTEGER PRIMARY KEY AUTOINCREMENT,
                timestamp INTEGER NOT NULL,
                raw_text TEXT NOT NULL,
                final_text TEXT NOT NULL,
                duration_ms INTEGER NOT NULL,
                legacy_id INTEGER
            )
        """.trimIndent())
        db.execSQL("CREATE INDEX dictations_timestamp ON dictations(timestamp DESC)")
        db.execSQL("CREATE UNIQUE INDEX dictations_legacy_id ON dictations(legacy_id)")
    }

    override fun onUpgrade(db: SQLiteDatabase, oldVersion: Int, newVersion: Int) {
        if (oldVersion < 2) {
            db.execSQL("ALTER TABLE dictations ADD COLUMN legacy_id INTEGER")
            db.execSQL("CREATE UNIQUE INDEX dictations_legacy_id ON dictations(legacy_id)")
        }
    }

    fun add(raw: String, finalText: String, durationMs: Long): Long {
        val id = writableDatabase.insertOrThrow("dictations", null, ContentValues().apply {
            put("timestamp", System.currentTimeMillis())
            put("raw_text", raw)
            put("final_text", finalText)
            put("duration_ms", durationMs.coerceAtLeast(0))
        })
        pruneOlderThan(AppSettings(context).historyRetentionDays)
        return id
    }

    fun pruneOlderThan(days: Int) {
        if (days == 0) return
        val cutoff = System.currentTimeMillis() - days * 86_400_000L
        writableDatabase.delete("dictations", "timestamp < ?", arrayOf(cutoff.toString()))
    }

    fun recent(limit: Int = 50): List<DictationRecord> = readableDatabase.query(
        "dictations", arrayOf("id", "timestamp", "raw_text", "final_text", "duration_ms"),
        null, null, null, null, "timestamp DESC, id DESC", limit.coerceIn(1, 100).toString(),
    ).use { cursor ->
        buildList {
            while (cursor.moveToNext()) add(DictationRecord(
                cursor.getLong(0), cursor.getLong(1), cursor.getString(2), cursor.getString(3), cursor.getLong(4),
            ))
        }
    }

    fun delete(id: Long) {
        writableDatabase.delete("dictations", "id = ?", arrayOf(id.toString()))
    }

    fun contains(id: Long): Boolean = readableDatabase.query(
        "dictations", arrayOf("id"), "id = ?", arrayOf(id.toString()), null, null, null, "1",
    ).use { it.moveToFirst() }

    fun localIdForLegacy(legacyId: Long): Long? = readableDatabase.query(
        "dictations", arrayOf("id"), "legacy_id = ?", arrayOf(legacyId.toString()), null, null, null, "1",
    ).use { if (it.moveToFirst()) it.getLong(0) else null }

    fun writeMigrationRecords(writer: JsonWriter) {
        writer.beginArray()
        readableDatabase.query("dictations", arrayOf("id", "timestamp", "raw_text", "final_text", "duration_ms"),
            null, null, null, null, "id ASC").use { cursor ->
            while (cursor.moveToNext()) {
                writer.beginObject()
                writer.name("id").value(cursor.getLong(0))
                writer.name("timestamp").value(cursor.getLong(1))
                writer.name("raw_text").value(cursor.getString(2))
                writer.name("final_text").value(cursor.getString(3))
                writer.name("duration_ms").value(cursor.getLong(4))
                writer.endObject()
            }
        }
        writer.endArray()
    }

    fun importMigrationRecords(reader: JsonReader): Int {
        val db = writableDatabase
        var added = 0
        db.beginTransaction()
        try {
            reader.beginArray()
            while (reader.hasNext()) {
                reader.beginObject()
                require(reader.nextName() == "id")
                val legacyId = reader.nextLong()
                require(reader.nextName() == "timestamp")
                val timestamp = reader.nextLong()
                require(reader.nextName() == "raw_text")
                val raw = reader.nextString()
                require(reader.nextName() == "final_text")
                val finalText = reader.nextString()
                require(reader.nextName() == "duration_ms")
                val durationMs = reader.nextLong()
                reader.endObject()
                require(legacyId > 0 && timestamp > 0 && durationMs >= 0)
                val inserted = db.insertWithOnConflict("dictations", null, ContentValues().apply {
                    put("legacy_id", legacyId)
                    put("timestamp", timestamp)
                    put("raw_text", raw)
                    put("final_text", finalText)
                    put("duration_ms", durationMs)
                }, SQLiteDatabase.CONFLICT_IGNORE)
                if (inserted > 0) added++
            }
            reader.endArray()
            db.setTransactionSuccessful()
        } finally {
            db.endTransaction()
        }
        return added
    }

    fun writeExport(writer: JsonWriter) {
        writer.beginObject()
        writer.name("version").value(1)
        writer.name("exported_at").value(System.currentTimeMillis())
        writer.name("dictations").beginArray()
        readableDatabase.query("dictations", arrayOf("timestamp", "raw_text", "final_text", "duration_ms"),
            null, null, null, null, "timestamp DESC, id DESC").use { cursor ->
            while (cursor.moveToNext()) {
                writer.beginObject()
                writer.name("timestamp").value(cursor.getLong(0))
                writer.name("raw_text").value(cursor.getString(1))
                writer.name("final_text").value(cursor.getString(2))
                writer.name("duration_ms").value(cursor.getLong(3))
                writer.endObject()
            }
        }
        writer.endArray().endObject()
        writer.flush()
    }

    fun stats(): DictationStats = readableDatabase.query(
        "dictations", arrayOf("timestamp", "raw_text", "final_text", "duration_ms"),
        null, null, null, null, null,
    ).use { cursor ->
        calculateStats(sequence {
            while (cursor.moveToNext()) {
                yield(DictationRecord(0, cursor.getLong(0), cursor.getString(1), cursor.getString(2), cursor.getLong(3)))
            }
        }, System.currentTimeMillis())
    }
}

internal fun calculateStats(
    records: Sequence<DictationRecord>,
    nowMs: Long,
    zone: TimeZone = TimeZone.getDefault(),
): DictationStats {
    fun mondayOf(timestamp: Long): Long = Calendar.getInstance(zone).apply {
        timeInMillis = timestamp
        add(Calendar.DAY_OF_YEAR, -((get(Calendar.DAY_OF_WEEK) + 5) % 7))
        set(Calendar.HOUR_OF_DAY, 0)
        set(Calendar.MINUTE, 0)
        set(Calendar.SECOND, 0)
        set(Calendar.MILLISECOND, 0)
    }.timeInMillis

    class WeekBucket(var words: Int = 0, var rawWords: Int = 0, var durationMs: Long = 0)
    val currentMonday = Calendar.getInstance(zone).apply { timeInMillis = mondayOf(nowMs) }
    val weekStarts = (7 downTo 0).map { offset ->
        (currentMonday.clone() as Calendar).apply { add(Calendar.WEEK_OF_YEAR, -offset) }.timeInMillis
    }
    val buckets = weekStarts.associateWith { WeekBucket() }
    var totalWords = 0
    var rawWords = 0
    var durationMs = 0L
    var recordingsWithDuration = 0
    var dictations = 0

    for (record in records) {
        val words = countWords(record.finalText)
        if (words == 0) continue
        totalWords += words
        dictations++
        val bucket = buckets[mondayOf(record.timestamp)]
        if (bucket != null) bucket.words += words
        val rawCount = countWords(record.raw)
        if (record.durationMs > 0 && rawCount > 0) {
            rawWords += rawCount
            durationMs += record.durationMs
            recordingsWithDuration++
            if (bucket != null) {
                bucket.rawWords += rawCount
                bucket.durationMs += record.durationMs
            }
        }
    }

    val weeks = weekStarts.map { start ->
        val bucket = buckets.getValue(start)
        WeekStats(start, bucket.words, if (bucket.durationMs > 0)
            (bucket.rawWords * 60_000.0 / bucket.durationMs).roundToInt() else null)
    }
    val current = weeks.last().words
    val previous = weeks[weeks.lastIndex - 1].words
    return DictationStats(
        totalWords = totalWords,
        thisWeekWords = current,
        previousWeekWords = previous,
        dictations = dictations,
        recordedMinutes = durationMs / 60_000.0,
        recordedWpm = if (durationMs > 0) (rawWords * 60_000.0 / durationMs).roundToInt() else null,
        recordingsWithDuration = recordingsWithDuration,
        weekOverWeekPercent = if (previous > 0) (current - previous) * 100.0 / previous else null,
        weeks = weeks,
    )
}

private fun countWords(value: String): Int = value.split(Regex("\\s+")).count { token ->
    token.any(Char::isLetterOrDigit)
}
