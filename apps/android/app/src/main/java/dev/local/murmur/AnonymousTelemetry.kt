package dev.local.murmur

import android.content.Context
import java.net.HttpURLConnection
import java.net.URI

internal enum class TelemetryRoute(val wire: String) { APP("app"), HOME("home"), SETTINGS("settings"), LIBRARY("library") }
internal enum class TelemetryCount(val wire: String) { APP_OPEN("app_open"), SCREEN_VIEW("screen_view") }
internal enum class TelemetryError(val wire: String) { STORAGE_FAILED("storage_failed"), PERMISSION_FAILED("permission_failed") }

internal class AnonymousTelemetryClient(
    private val configured: Boolean,
    private val endpoint: String,
    private val allowed: () -> Boolean,
    private val transport: (String, String) -> Unit,
    private val clock: () -> Long = System::currentTimeMillis,
    private val dispatch: (() -> Unit) -> Unit = { task -> Thread(task, "anonymous-reporting").start() },
) {
    private var inFlight = false
    private var lifetime = 0
    private var minuteStart = 0L
    private var minuteCount = 0
    private val lastErrors = mutableMapOf<Pair<TelemetryError, TelemetryRoute>, Long>()

    fun count(name: TelemetryCount, route: TelemetryRoute) = send("count", name.wire, route, null)
    fun error(name: TelemetryError, route: TelemetryRoute) = send("error", name.wire, route, name)

    private fun send(kind: String, name: String, route: TelemetryRoute, error: TelemetryError?) {
        if (!configured || !validEndpoint(endpoint)) return
        var ownsRequest = false
        try {
            synchronized(this) {
                if (inFlight || !allowed()) return
                val now = clock()
                if (now < minuteStart || lifetime >= 200) return
                if (now - minuteStart >= 60_000) { minuteStart = now; minuteCount = 0 }
                if (minuteCount >= 20) return
                if (error != null && lastErrors[error to route]?.let { now - it < 60_000 } == true) return
                inFlight = true
                ownsRequest = true
                lifetime += 1
                minuteCount += 1
                if (error != null) lastErrors[error to route] = now
            }
            val payload = """{"version":1,"app":"murmur","kind":"$kind","name":"$name","surface":"android","route":"${route.wire}"}"""
            dispatch {
                try { if (allowed()) transport(endpoint, payload) } catch (_: Exception) {
                    // optional reporting does not change the user action
                } finally { synchronized(this) { inFlight = false } }
            }
        } catch (_: Exception) { if (ownsRequest) synchronized(this) { inFlight = false } }
    }

    companion object {
        fun validEndpoint(value: String): Boolean = try {
            val uri = URI(value)
            uri.scheme == "https" && !uri.host.isNullOrBlank() && uri.path == "/v1/events" &&
                uri.userInfo == null && uri.query == null && uri.fragment == null
        } catch (_: Exception) { false }
    }
}

internal object AnonymousTelemetry {
    private var client: AnonymousTelemetryClient? = null
    private var opened = false
    @Volatile private var preferenceBlocked = false
    fun configured() = BuildConfig.TELEMETRY_ENABLED && AnonymousTelemetryClient.validEndpoint(BuildConfig.TELEMETRY_ENDPOINT)
    fun enabled(context: Context): Boolean = try {
        !preferenceBlocked && context.getSharedPreferences("murmur_privacy", Context.MODE_PRIVATE).getBoolean("anonymous_reporting", false)
    } catch (_: Exception) { false }

    fun setEnabled(context: Context, enabled: Boolean): Boolean = try {
        val saved = context.getSharedPreferences("murmur_privacy", Context.MODE_PRIVATE).edit().putBoolean("anonymous_reporting", enabled).commit()
        preferenceBlocked = !saved
        saved
    } catch (_: Exception) { preferenceBlocked = true; false }

    @Synchronized private fun client(context: Context): AnonymousTelemetryClient {
        val app = context.applicationContext
        return client ?: AnonymousTelemetryClient(configured(), BuildConfig.TELEMETRY_ENDPOINT, { enabled(app) }, { url, payload ->
            val connection = URI(url).toURL().openConnection() as HttpURLConnection
            val deadline = java.util.Timer(true)
            try {
                deadline.schedule(object : java.util.TimerTask() { override fun run() { try { connection.disconnect() } catch (_: Exception) { } } }, 2000)
                connection.requestMethod = "POST"
                connection.instanceFollowRedirects = false
                connection.connectTimeout = 2000
                connection.readTimeout = 2000
                connection.useCaches = false
                connection.doOutput = true
                connection.setRequestProperty("Content-Type", "application/json")
                connection.setRequestProperty("User-Agent", "")
                connection.setRequestProperty("Cookie", "")
                connection.setFixedLengthStreamingMode(payload.toByteArray(Charsets.UTF_8).size)
                connection.outputStream.use { it.write(payload.toByteArray(Charsets.UTF_8)) }
                connection.responseCode
            } finally { deadline.cancel(); connection.disconnect() }
        }).also { client = it }
    }

    @Synchronized fun open(context: Context) {
        if (!opened) { opened = true; client(context).count(TelemetryCount.APP_OPEN, TelemetryRoute.APP) }
    }
    fun screen(context: Context, route: TelemetryRoute) = client(context).count(TelemetryCount.SCREEN_VIEW, route)
    fun error(context: Context, name: TelemetryError, route: TelemetryRoute) = client(context).error(name, route)
}
