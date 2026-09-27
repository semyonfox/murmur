package dev.local.murmur

import android.content.ContentProvider
import android.content.ContentValues
import android.content.pm.PackageManager
import android.database.Cursor
import android.net.Uri
import android.os.Binder
import android.os.ParcelFileDescriptor
import android.util.JsonWriter
import java.io.FileOutputStream
import java.io.OutputStreamWriter
import java.nio.charset.StandardCharsets

class MigrationProvider : ContentProvider() {
    override fun onCreate(): Boolean = true

    override fun openFile(uri: Uri, mode: String): ParcelFileDescriptor {
        val app = requireNotNull(context)
        require(app.packageName == LEGACY_PACKAGE && uri == SNAPSHOT_URI && mode == "r")
        val caller = Binder.getCallingUid()
        val packages = app.packageManager.getPackagesForUid(caller).orEmpty()
        if (NEW_PACKAGE !in packages ||
            app.packageManager.checkSignatures(caller, android.os.Process.myUid()) != PackageManager.SIGNATURE_MATCH
        ) throw SecurityException("Migration is available only to the signed Murmur app.")

        val (readEnd, writeEnd) = ParcelFileDescriptor.createReliablePipe()
        Thread {
            try {
                val writer = JsonWriter(OutputStreamWriter(FileOutputStream(writeEnd.fileDescriptor), StandardCharsets.UTF_8))
                writer.beginObject()
                writer.name("version").value(1)
                writer.name("settings").value(AppSettings(app).migrationSnapshot().toString())
                writer.name("dictations")
                HistoryStore(app).use { it.writeMigrationRecords(writer) }
                writer.endObject()
                writer.flush()
                writeEnd.close()
            } catch (_: Exception) {
                runCatching { writeEnd.closeWithError("Could not read old Murmur data.") }
            }
        }.start()
        return readEnd
    }

    override fun getType(uri: Uri): String? = if (uri == SNAPSHOT_URI) "application/json" else null
    override fun query(uri: Uri, projection: Array<out String>?, selection: String?, selectionArgs: Array<out String>?, sortOrder: String?): Cursor? = null
    override fun insert(uri: Uri, values: ContentValues?): Uri? = null
    override fun delete(uri: Uri, selection: String?, selectionArgs: Array<out String>?): Int = 0
    override fun update(uri: Uri, values: ContentValues?, selection: String?, selectionArgs: Array<out String>?): Int = 0

    companion object {
        private const val LEGACY_PACKAGE = "dev.local.murmur"
        private const val NEW_PACKAGE = "ie.semyon.murmur"
        val SNAPSHOT_URI: Uri = Uri.parse("content://dev.local.murmur.migration/snapshot")
    }
}
