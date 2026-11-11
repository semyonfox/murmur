package dev.local.murmur

import android.app.ActivityManager
import android.app.ApplicationExitInfo
import android.content.Context
import android.os.Build
import android.util.Log
import androidx.core.content.edit
import org.json.JSONException
import java.io.File
import java.text.SimpleDateFormat
import java.util.Date
import java.util.Locale
import java.util.concurrent.Executors

// a small log kept on the phone so daily bugs can be diagnosed without a crash
// service. it records what failed and how long things took, never transcripts,
// audio, keys or full urls, and only leaves the phone when the user shares it
internal object Diagnostics {
    private const val MAX_BYTES = 256 * 1024
    private const val MAX_MESSAGE = 300
    private val writer = Executors.newSingleThreadExecutor { task ->
        Thread(task, "murmur-diagnostics").apply { isDaemon = true }
    }
    private val time = SimpleDateFormat("yyyy-MM-dd HH:mm:ss.SSS", Locale.ROOT)
    private val urlQuery = Regex("""\?[^\s"']*""")
    @Volatile private var directory: File? = null

    fun install(context: Context) {
        if (directory != null) return
        directory = File(context.filesDir, "diagnostics").apply { mkdirs() }
        val previous = Thread.getDefaultUncaughtExceptionHandler()
        Thread.setDefaultUncaughtExceptionHandler { thread, error ->
            // written synchronously because the process dies right after this
            runCatching { append(line('E', "crash", "uncaught on ${thread.name}", error)) }
            previous?.uncaughtException(thread, error)
        }
        writer.execute { runCatching { recordExits(context) } }
    }

    // a no-op until the application installs it, which also keeps jvm unit tests
    // away from android.util.Log
    fun info(tag: String, message: String) {
        if (directory == null) return
        Log.i("Murmur/$tag", message)
        write(line('I', tag, message, null))
    }

    fun warn(tag: String, message: String, error: Throwable? = null) {
        if (directory == null) return
        Log.w("Murmur/$tag", message, error)
        write(line('W', tag, message, error))
    }

    fun error(tag: String, message: String, error: Throwable? = null) {
        if (directory == null) return
        Log.e("Murmur/$tag", message, error)
        write(line('E', tag, message, error))
    }

    // header plus the rotated and current log in one file the share sheet can send
    fun export(context: Context): File {
        val out = File(File(context.cacheDir, "diagnostics").apply { mkdirs() }, "murmur-log.txt")
        writer.submit {
            val dir = directory ?: return@submit
            out.bufferedWriter().use { output ->
                output.appendLine("Murmur ${BuildConfig.VERSION_NAME} (${BuildConfig.VERSION_CODE})")
                output.appendLine("Android ${Build.VERSION.RELEASE} (API ${Build.VERSION.SDK_INT}), ${Build.MANUFACTURER} ${Build.MODEL}")
                output.appendLine("ABI ${Build.SUPPORTED_ABIS.firstOrNull()}, ${Runtime.getRuntime().availableProcessors()} cores, ${totalRamMb(context)} MB RAM")
                output.appendLine()
                for (name in listOf("murmur.log.1", "murmur.log")) {
                    val file = File(dir, name)
                    if (file.exists()) file.bufferedReader().use { it.copyTo(output) }
                }
            }
        }.get()
        return out
    }

    fun sizeBytes(): Long = directory?.listFiles()?.sumOf { it.length() } ?: 0L

    fun clear() {
        writer.submit { directory?.listFiles()?.forEach { it.delete() } }.get()
    }

    private fun write(text: String) {
        writer.execute { runCatching { append(text) } }
    }

    @Synchronized private fun append(text: String) {
        val dir = directory ?: return
        val file = File(dir, "murmur.log")
        if (file.length() > MAX_BYTES) file.renameTo(File(dir, "murmur.log.1"))
        file.appendText(text)
    }

    private fun line(level: Char, tag: String, message: String, error: Throwable?): String = buildString {
        synchronized(time) { append(time.format(Date())) }
        append(' ').append(level).append(' ').append(tag).append(": ").append(message)
        if (error != null) append(" | ").append(describe(error))
        append('\n')
        if (error != null && (level == 'E' || error !is java.io.IOException)) {
            var cause: Throwable? = error
            var depth = 0
            while (cause != null && depth < 3) {
                if (depth > 0) append("  caused by ").append(describe(cause)).append('\n')
                cause.stackTrace.take(12).forEach { append("    at ").append(it).append('\n') }
                cause = cause.cause?.takeIf { it !== cause }
                depth++
            }
        }
    }

    // json errors quote the server response, which can hold transcript text
    private fun describe(error: Throwable): String {
        val name = error.javaClass.simpleName
        val message = if (error is JSONException) null else error.message
            ?.replace(urlQuery, "?…")
            ?.let { if (it.length > MAX_MESSAGE) it.take(MAX_MESSAGE) + "…" else it }
        return if (message.isNullOrBlank()) name else "$name: $message"
    }

    // android keeps why the process died, which covers the cases the crash handler
    // cannot see: native crashes in the speech engines, low memory kills and anrs
    private fun recordExits(context: Context) {
        if (Build.VERSION.SDK_INT < Build.VERSION_CODES.R) return
        val prefs = context.getSharedPreferences("murmur_diagnostics", Context.MODE_PRIVATE)
        val seen = prefs.getLong("last_exit", 0L)
        val manager = context.getSystemService(ActivityManager::class.java)
        val exits = manager.getHistoricalProcessExitReasons(null, 0, 10).filter { it.timestamp > seen }
        for (exit in exits.sortedBy { it.timestamp }) {
            val reason = exitReason(exit.reason) ?: continue
            val level = if (exit.reason in setOf(ApplicationExitInfo.REASON_CRASH, ApplicationExitInfo.REASON_CRASH_NATIVE,
                    ApplicationExitInfo.REASON_ANR, ApplicationExitInfo.REASON_LOW_MEMORY)) 'E' else 'W'
            val detail = exit.description?.take(MAX_MESSAGE)?.let { " ($it)" }.orEmpty()
            append(line(level, "exit", "previous run ended at ${synchronized(time) { time.format(Date(exit.timestamp)) }}: " +
                "$reason, importance ${exit.importance}, pss ${exit.pss / 1024} MB$detail", null))
        }
        exits.maxOfOrNull { it.timestamp }?.let { prefs.edit { putLong("last_exit", it) } }
    }

    private fun exitReason(reason: Int): String? = when (reason) {
        ApplicationExitInfo.REASON_CRASH -> "java crash"
        ApplicationExitInfo.REASON_CRASH_NATIVE -> "native crash"
        ApplicationExitInfo.REASON_ANR -> "not responding"
        ApplicationExitInfo.REASON_LOW_MEMORY -> "killed for low memory"
        ApplicationExitInfo.REASON_EXCESSIVE_RESOURCE_USAGE -> "killed for resource use"
        ApplicationExitInfo.REASON_INITIALIZATION_FAILURE -> "failed to start"
        ApplicationExitInfo.REASON_SIGNALED -> "killed by signal"
        ApplicationExitInfo.REASON_OTHER -> "killed by the system"
        ApplicationExitInfo.REASON_FREEZER -> "killed while frozen"
        // user swipes, updates and normal exits are expected
        else -> null
    }

    private fun totalRamMb(context: Context): Long {
        val info = ActivityManager.MemoryInfo()
        context.getSystemService(ActivityManager::class.java).getMemoryInfo(info)
        return info.totalMem / 1024 / 1024
    }
}

// one place that turns a failure into the short text the bubble and lecture screen show
internal fun describeFailure(error: Throwable, fallback: String): String = when (error) {
    is HttpStatusException -> when (error.statusCode) {
        401, 403 -> "Key was rejected (HTTP ${error.statusCode})"
        408, 504 -> "Server timed out (HTTP ${error.statusCode})"
        413 -> "Audio too large for server"
        429 -> "Rate limited, try again soon"
        in 500..599 -> "Server error (HTTP ${error.statusCode})"
        else -> "Server returned HTTP ${error.statusCode}"
    }
    is java.net.SocketTimeoutException -> "Server took too long"
    is java.net.UnknownHostException -> "No connection to server"
    is java.net.ConnectException -> "Could not reach server"
    is javax.net.ssl.SSLException -> "Secure connection failed"
    is JSONException -> "Server sent an unexpected reply"
    else -> error.message?.takeIf { it.isNotBlank() } ?: fallback
}
