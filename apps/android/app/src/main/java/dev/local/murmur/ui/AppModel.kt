package dev.local.murmur.ui

import android.Manifest
import android.content.ComponentName
import android.content.Context
import android.content.pm.PackageManager
import android.os.Build
import android.provider.Settings
import android.speech.SpeechRecognizer
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableIntStateOf
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.setValue
import android.os.Handler
import android.os.Looper
import dev.local.murmur.AppSettings
import dev.local.murmur.BuildConfig
import dev.local.murmur.Diagnostics
import dev.local.murmur.UpdateClient
import dev.local.murmur.AvailableUpdate
import dev.local.murmur.HistoryStore
import dev.local.murmur.LocalSpeechModels
import dev.local.murmur.MurmurAccessibilityService
import dev.local.murmur.MurmurReadyService
import java.io.File
import java.net.URI

internal sealed interface UpdateState {
    data object Idle : UpdateState
    data object Checking : UpdateState
    data object UpToDate : UpdateState
    data class Available(val update: AvailableUpdate) : UpdateState
    data class Downloading(val update: AvailableUpdate, val percent: Int) : UpdateState
    data class Ready(val update: AvailableUpdate, val apk: File) : UpdateState
    data class Failed(val message: String, val update: AvailableUpdate?) : UpdateState
}

internal data class SetupStatus(
    val micAllowed: Boolean,
    val recognitionIssue: String?,
    val remoteKeyMissing: Boolean,
    val bubbleSupported: Boolean,
    val bubbleEnabled: Boolean,
    val voiceReady: Boolean,
    val audioDestination: String,
) {
    val recognitionReady: Boolean get() = recognitionIssue == null
    val allReady: Boolean get() = micAllowed && recognitionReady && bubbleSupported && bubbleEnabled && voiceReady
}

// plain state holder, settings stay the source of truth and bumping revision makes screens re-read them
internal class AppModel(val context: Context) {
    val settings = AppSettings(context)
    val history = HistoryStore(context)
    var revision by mutableIntStateOf(0)
        private set
    var update by mutableStateOf<UpdateState>(UpdateState.Idle)

    fun refresh() {
        revision++
    }

    val micAllowed: Boolean
        get() = context.checkSelfPermission(Manifest.permission.RECORD_AUDIO) == PackageManager.PERMISSION_GRANTED

    val onDeviceAvailable: Boolean
        get() = Build.VERSION.SDK_INT >= 31 && SpeechRecognizer.isOnDeviceRecognitionAvailable(context)

    fun selectedLocalModel() = settings.localModelId
        ?.let { id -> LocalSpeechModels.catalog.firstOrNull { it.id == id } }
        ?.takeIf { LocalSpeechModels.isInstalled(context, it) && LocalSpeechModels.canRun(context, it) }

    fun recognitionIssue(forSavedAudio: Boolean): String? {
        if (settings.useOnDeviceRecognition) {
            if (forSavedAudio) return "The phone speech service cannot process saved audio."
            return if (onDeviceAvailable) null else "The phone speech service is unavailable. Choose another source."
        }
        if (settings.localModelId != null) {
            return if (selectedLocalModel() != null) null else "Download or select a model that can run on this phone."
        }
        val endpoint = runCatching { settings.endpointOrNull() }.getOrNull()
            ?: return "Enter a valid transcription URL and model."
        val hostedProvider = endpoint.uri.host?.lowercase() in HOSTED_STT
        return if (hostedProvider && !settings.hasApiKey) "Add an API key for ${endpoint.uri.host}." else null
    }

    val bubbleEnabled: Boolean
        get() {
            if (Build.VERSION.SDK_INT < 33 ||
                !context.getSharedPreferences("murmur", Context.MODE_PRIVATE).getBoolean("bubble_disclosure_accepted", false)
            ) return false
            val component = ComponentName(context, MurmurAccessibilityService::class.java)
            return Settings.Secure.getString(context.contentResolver, Settings.Secure.ENABLED_ACCESSIBILITY_SERVICES)
                .orEmpty().split(':').any { ComponentName.unflattenFromString(it) == component }
        }

    fun acceptBubbleDisclosure() {
        context.getSharedPreferences("murmur", Context.MODE_PRIVATE).edit()
            .putBoolean("bubble_disclosure_accepted", true).apply()
    }

    val speechHost: String get() = runCatching { URI(settings.endpointUrl).host }.getOrNull() ?: settings.endpointUrl
    val cleanupHost: String get() = runCatching { URI(settings.cleanupUrl).host }.getOrNull() ?: "your service"

    fun setup(): SetupStatus {
        val host = speechHost
        val audioStaysLocal = settings.useOnDeviceRecognition || settings.localModelId != null ||
            host == "localhost" || host == "127.0.0.1"
        return SetupStatus(
            micAllowed = micAllowed,
            recognitionIssue = recognitionIssue(forSavedAudio = false),
            remoteKeyMissing = !settings.useOnDeviceRecognition && settings.localModelId == null &&
                runCatching { settings.endpointOrNull()?.uri?.host?.lowercase() }.getOrNull() in HOSTED_STT && !settings.hasApiKey,
            bubbleSupported = Build.VERSION.SDK_INT >= 33,
            bubbleEnabled = bubbleEnabled,
            voiceReady = MurmurReadyService.isActive,
            audioDestination = if (audioStaysLocal) "Audio stays on this phone."
                else "Audio goes to $host for transcription.",
        )
    }

    fun recognitionSummary(): String {
        val issue = recognitionIssue(forSavedAudio = false)
        return when {
            settings.useOnDeviceRecognition && onDeviceAvailable -> "Phone speech service"
            settings.localModelId != null -> selectedLocalModel()?.let { "On this phone · ${it.label}" } ?: "Downloaded model unavailable"
            issue != null -> issue
            else -> "$speechHost · ${settings.model}"
        }
    }

    fun cleanupSummary(): String = when {
        settings.cleanupEnabled && settings.lastCleanupFailure != null -> "On · last cleanup failed, local text used"
        settings.cleanupEnabled -> "On · $cleanupHost · ${settings.cleanupModel}"
        settings.cleanupModel.isBlank() -> "Off · add a model to turn on"
        cleanupHost.equals("openrouter.ai", ignoreCase = true) && !settings.hasCleanupKey -> "Off · add a cleanup key"
        else -> "Off · ready to turn on"
    }

    private val main = Handler(Looper.getMainLooper())

    fun checkForUpdates(automatic: Boolean) {
        if (BuildConfig.UPDATE_MANIFEST_URL.isBlank()) return
        if (update is UpdateState.Checking || update is UpdateState.Downloading) return
        if (!automatic) update = UpdateState.Checking
        Thread {
            val result = runCatching { UpdateClient(context).check() }
                .onFailure { Diagnostics.warn("update", "update check failed", it) }
            main.post {
                update = result.fold(
                    onSuccess = { found -> if (found == null) (if (automatic) UpdateState.Idle else UpdateState.UpToDate) else UpdateState.Available(found) },
                    onFailure = { error -> if (automatic) UpdateState.Idle else UpdateState.Failed(error.message ?: "Could not check for updates.", null) },
                )
            }
        }.start()
    }

    fun downloadUpdate(found: AvailableUpdate, onReady: (File) -> Unit) {
        update = UpdateState.Downloading(found, 0)
        Thread {
            val result = runCatching {
                UpdateClient(context).download(found) { percent -> main.post { update = UpdateState.Downloading(found, percent) } }
            }.onFailure { Diagnostics.warn("update", "update download failed", it) }
            main.post {
                result.onSuccess { apk ->
                    update = UpdateState.Ready(found, apk)
                    onReady(apk)
                }.onFailure { error -> update = UpdateState.Failed(error.message ?: "Could not download the update.", found) }
            }
        }.start()
    }

    companion object {
        val HOSTED_STT = setOf("openrouter.ai", "api.openai.com", "api.groq.com")
    }
}
