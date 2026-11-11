package dev.local.murmur.ui

import android.Manifest
import android.content.Context
import android.content.Intent
import android.os.Build
import android.provider.Settings
import androidx.activity.compose.rememberLauncherForActivityResult
import androidx.activity.result.contract.ActivityResultContracts
import androidx.compose.animation.animateContentSize
import androidx.compose.foundation.background
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.heightIn
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.Check
import androidx.compose.material.icons.automirrored.filled.KeyboardArrowRight
import androidx.compose.material3.AlertDialog
import androidx.compose.material3.Button
import androidx.compose.material3.Card
import androidx.compose.material3.CardDefaults
import androidx.compose.material3.FilledTonalButton
import androidx.compose.material3.Icon
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedButton
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.runtime.Composable
import androidx.compose.runtime.collectAsState
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.saveable.rememberSaveable
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.res.painterResource
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import dev.local.murmur.AnonymousTelemetry
import dev.local.murmur.DictationStats
import dev.local.murmur.LectureService
import dev.local.murmur.LectureState
import dev.local.murmur.MurmurReadyService
import dev.local.murmur.R
import dev.local.murmur.TelemetryError
import dev.local.murmur.TelemetryRoute
import java.text.DateFormat
import java.text.NumberFormat
import java.util.Date

// microphone, notification and accessibility steps for the voice bubble, shared by home and the bubble screen
internal class VoiceControls(
    val requestMicrophone: () -> Unit,
    val enableBubble: () -> Unit,
    val start: () -> Unit,
    val stop: () -> Unit,
)

@Composable
internal fun rememberVoiceControls(model: AppModel): VoiceControls {
    val context = LocalContext.current
    val snackbar = LocalSnackbar.current
    var disclosure by rememberSaveable { mutableStateOf(false) }
    var pendingStart by remember { mutableStateOf(false) }

    fun begin() {
        try {
            MurmurReadyService.start(context)
        } catch (_: Exception) {
            AnonymousTelemetry.error(context, TelemetryError.PERMISSION_FAILED, TelemetryRoute.HOME)
            snackbar.showLater("Voice bubble could not start. Try again.")
        }
        refreshSoon(model)
    }

    val microphone = rememberLauncherForActivityResult(ActivityResultContracts.RequestPermission()) { model.refresh() }
    val notifications = rememberLauncherForActivityResult(ActivityResultContracts.RequestPermission()) {
        if (pendingStart) {
            pendingStart = false
            begin()
        }
    }

    if (disclosure) AlertDialog(
        onDismissRequest = { disclosure = false },
        title = { Text("Enable Murmur voice bubble") },
        text = {
            Text(
                "Murmur uses Android Accessibility to detect when an ordinary text field is focused and insert your dictation at the cursor while your usual keyboard stays active. " +
                    "It checks the field type and cursor context for safe placement and spacing, but does not upload text already in the field. " +
                    "The bubble stays hidden in password and other sensitive fields. " +
                    "Recording starts only when you tap the bubble. Audio follows the recognition choice you make in Settings. " +
                    "If Android blocks the switch, open Settings → Apps → Murmur → ⋮ → Allow restricted settings, then return to Accessibility.",
            )
        },
        confirmButton = {
            TextButton(onClick = {
                disclosure = false
                model.acceptBubbleDisclosure()
                context.startActivity(Intent(Settings.ACTION_ACCESSIBILITY_SETTINGS))
            }) { Text("Agree and open Settings") }
        },
        dismissButton = { TextButton(onClick = { disclosure = false }) { Text("Cancel") } },
    )

    return VoiceControls(
        requestMicrophone = { microphone.launch(Manifest.permission.RECORD_AUDIO) },
        enableBubble = { disclosure = true },
        start = {
            val prefs = context.getSharedPreferences("murmur", Context.MODE_PRIVATE)
            when {
                !model.micAllowed -> microphone.launch(Manifest.permission.RECORD_AUDIO)
                !model.bubbleEnabled -> disclosure = true
                Build.VERSION.SDK_INT >= 33 &&
                    context.checkSelfPermission(Manifest.permission.POST_NOTIFICATIONS) != android.content.pm.PackageManager.PERMISSION_GRANTED &&
                    !prefs.getBoolean("notification_permission_requested", false) -> {
                    pendingStart = true
                    prefs.edit().putBoolean("notification_permission_requested", true).apply()
                    notifications.launch(Manifest.permission.POST_NOTIFICATIONS)
                }
                else -> begin()
            }
        },
        stop = {
            MurmurReadyService.stop(context)
            refreshSoon(model)
        },
    )
}

// the ready service flips its flag a moment after starting or stopping
private fun refreshSoon(model: AppModel) {
    android.os.Handler(android.os.Looper.getMainLooper()).postDelayed({ model.refresh() }, 400)
}

@Composable
internal fun HomeScreen(
    model: AppModel,
    lectureActions: LectureActions,
    navigate: (Route) -> Unit,
    selectTab: (Route.Tab) -> Unit,
) {
    val voice = rememberVoiceControls(model)
    val lecture by LectureService.state.collectAsState()
    val revision = model.revision
    val setup = remember(revision) { model.setup() }
    val latest = remember(revision) { runCatching { model.history.recent(1).firstOrNull() } }
    val stats = remember(revision) { runCatching { model.history.stats() }.getOrNull() }

    ScreenScaffold(title = "Murmur") {
        item(key = "status") { DictationCard(setup, voice, onRecognition = { navigate(Route.Page.RECOGNITION) }) }

        val update = model.update
        if (update is UpdateState.Available || update is UpdateState.Ready) item(key = "update") {
            Card(
                onClick = { navigate(Route.Page.ABOUT) },
                shape = RoundedCornerShape(24.dp),
                colors = CardDefaults.cardColors(containerColor = MaterialTheme.colorScheme.tertiaryContainer),
                modifier = Modifier.fillMaxWidth(),
            ) {
                Row(Modifier.padding(16.dp), verticalAlignment = Alignment.CenterVertically) {
                    Column(Modifier.weight(1f)) {
                        Text("Update available", style = MaterialTheme.typography.titleSmall)
                        val name = (update as? UpdateState.Available)?.update?.versionName ?: (update as UpdateState.Ready).update.versionName
                        Text("Murmur $name is ready to download.", style = MaterialTheme.typography.bodySmall)
                    }
                    Icon(Icons.AutoMirrored.Filled.KeyboardArrowRight, contentDescription = null)
                }
            }
        }

        item(key = "lecture-label") { SectionLabel("Lectures") }
        item(key = "lecture") {
            if (lecture.phase == LectureState.Phase.RECORDING) LiveRecordingCard(lecture, lectureActions.record)
            else PaddedCard {
                Text(
                    when (lecture.phase) {
                        LectureState.Phase.TRANSCRIBING -> if (lecture.parts > 0) "Transcribing part ${minOf(lecture.part + 1, lecture.parts)} of ${lecture.parts}" else "Transcribing…"
                        LectureState.Phase.PREPARING -> "Preparing audio…"
                        else -> "Record a lecture or transcribe an audio file."
                    },
                    style = MaterialTheme.typography.bodyMedium,
                )
                Row(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                    Button(onClick = lectureActions.record, enabled = !lecture.busy, modifier = Modifier.weight(1f)) {
                        Icon(painterResource(R.drawable.ic_microphone), null, Modifier.size(18.dp))
                        Spacer(Modifier.size(8.dp))
                        Text("Record")
                    }
                    OutlinedButton(onClick = lectureActions.import, enabled = !lecture.busy, modifier = Modifier.weight(1f)) {
                        Icon(painterResource(R.drawable.ic_upload), null, Modifier.size(18.dp))
                        Spacer(Modifier.size(8.dp))
                        Text("Import")
                    }
                }
                TextButton(onClick = { selectTab(Route.Tab.LECTURES) }) { Text("View saved lectures") }
            }
        }

        item(key = "recent-label") { SectionLabel("Latest dictation") }
        item(key = "recent") {
            val record = latest.getOrNull()
            Card(
                onClick = { selectTab(Route.Tab.HISTORY) },
                shape = RoundedCornerShape(24.dp),
                colors = CardDefaults.cardColors(containerColor = MaterialTheme.colorScheme.surfaceContainerLow),
                modifier = Modifier.fillMaxWidth(),
            ) {
                Column(Modifier.padding(16.dp), verticalArrangement = Arrangement.spacedBy(6.dp)) {
                    if (record != null) Text(
                        DateFormat.getDateTimeInstance(DateFormat.MEDIUM, DateFormat.SHORT).format(Date(record.timestamp)),
                        style = MaterialTheme.typography.labelMedium,
                        color = MaterialTheme.colorScheme.onSurfaceVariant,
                    )
                    Text(
                        when {
                            latest.isFailure -> "History could not be loaded."
                            record == null -> "Your first dictation will appear here."
                            else -> record.finalText
                        },
                        style = MaterialTheme.typography.bodyLarge,
                        maxLines = 3,
                        overflow = TextOverflow.Ellipsis,
                        color = if (record == null) MaterialTheme.colorScheme.onSurfaceVariant else MaterialTheme.colorScheme.onSurface,
                    )
                }
            }
        }

        item(key = "activity-label") { SectionLabel("Activity") }
        item(key = "activity") { StatTiles(stats, onClick = { navigate(Route.Page.STATS) }) }
    }
}

@Composable
private fun DictationCard(setup: SetupStatus, voice: VoiceControls, onRecognition: () -> Unit) {
    val title: String
    val action: String?
    val run: () -> Unit
    var primary = true
    when {
        !setup.micAllowed -> { title = "Allow the microphone to start"; action = "Allow microphone"; run = voice.requestMicrophone }
        setup.remoteKeyMissing -> { title = "Add your API key"; action = "Add API key"; run = onRecognition }
        !setup.recognitionReady -> { title = setup.recognitionIssue ?: "Choose speech recognition"; action = "Choose recognition"; run = onRecognition }
        setup.bubbleSupported && !setup.bubbleEnabled -> { title = "Turn on the voice bubble"; action = "Enable voice bubble"; run = voice.enableBubble }
        setup.bubbleSupported && !setup.voiceReady -> { title = "Voice bubble is off"; action = "Turn on voice bubble"; run = voice.start }
        setup.bubbleSupported -> { title = "Ready to dictate"; action = "Turn off"; run = voice.stop; primary = false }
        else -> { title = "The voice bubble needs Android 13 or newer"; action = null; run = {} }
    }
    val ready = setup.allReady
    Card(
        shape = RoundedCornerShape(28.dp),
        colors = CardDefaults.cardColors(
            containerColor = if (ready) MaterialTheme.colorScheme.primaryContainer else MaterialTheme.colorScheme.surfaceContainerHigh,
        ),
        modifier = Modifier.fillMaxWidth().animateContentSize(),
    ) {
        Column(Modifier.padding(20.dp), verticalArrangement = Arrangement.spacedBy(12.dp)) {
            Row(verticalAlignment = Alignment.CenterVertically) {
                Box(
                    Modifier.size(48.dp).background(
                        if (ready) MaterialTheme.colorScheme.primary else MaterialTheme.colorScheme.secondaryContainer, CircleShape,
                    ),
                    contentAlignment = Alignment.Center,
                ) {
                    Icon(
                        painterResource(R.drawable.ic_microphone), contentDescription = null,
                        tint = if (ready) MaterialTheme.colorScheme.onPrimary else MaterialTheme.colorScheme.onSecondaryContainer,
                    )
                }
                Spacer(Modifier.size(16.dp))
                Column(Modifier.weight(1f)) {
                    Text(title, style = MaterialTheme.typography.titleMedium)
                    Text(
                        if (ready) "Tap the bubble in any text field to dictate. ${setup.audioDestination}" else setup.audioDestination,
                        style = MaterialTheme.typography.bodySmall,
                        color = MaterialTheme.colorScheme.onSurfaceVariant,
                    )
                }
            }
            if (!ready) Column(verticalArrangement = Arrangement.spacedBy(4.dp)) {
                ChecklistRow("Microphone", setup.micAllowed, if (setup.micAllowed) "Allowed" else "Permission needed")
                ChecklistRow("Recognition", setup.recognitionReady, if (setup.recognitionReady) "Source selected" else "Choose a working source")
                ChecklistRow(
                    "Voice bubble", setup.bubbleSupported && setup.bubbleEnabled,
                    when {
                        !setup.bubbleSupported -> "Needs Android 13"
                        setup.bubbleEnabled -> "Enabled"
                        else -> "Not enabled"
                    },
                )
            }
            if (action != null) {
                if (primary) Button(onClick = run, modifier = Modifier.fillMaxWidth().heightIn(min = 48.dp)) { Text(action) }
                else FilledTonalButton(onClick = run, modifier = Modifier.fillMaxWidth().heightIn(min = 48.dp)) { Text(action) }
            }
        }
    }
}

@Composable
private fun ChecklistRow(label: String, done: Boolean, detail: String) {
    Row(verticalAlignment = Alignment.CenterVertically) {
        Box(
            Modifier.size(22.dp).background(
                if (done) MaterialTheme.colorScheme.primary else MaterialTheme.colorScheme.surfaceContainerHighest, CircleShape,
            ),
            contentAlignment = Alignment.Center,
        ) {
            if (done) Icon(Icons.Filled.Check, contentDescription = null, Modifier.size(14.dp), tint = MaterialTheme.colorScheme.onPrimary)
        }
        Spacer(Modifier.size(12.dp))
        Text(label, style = MaterialTheme.typography.bodyMedium, modifier = Modifier.weight(1f))
        Text(detail, style = MaterialTheme.typography.bodySmall, color = MaterialTheme.colorScheme.onSurfaceVariant)
    }
}

@Composable
internal fun StatTiles(stats: DictationStats?, onClick: (() -> Unit)? = null) {
    val numbers = NumberFormat.getIntegerInstance()
    val tiles = listOf(
        Triple("Retained words", stats?.let { numbers.format(it.totalWords) } ?: "—", "In saved dictations"),
        Triple("This week", stats?.let { numbers.format(it.thisWeekWords) } ?: "—", "Since Monday"),
        Triple("Recorded pace", stats?.recordedWpm?.let { "${numbers.format(it)} wpm" } ?: "—", "Including pauses"),
        Triple(
            "Recorded time",
            stats?.takeIf { it.recordingsWithDuration > 0 }?.let {
                "${NumberFormat.getNumberInstance().apply { maximumFractionDigits = 1 }.format(it.recordedMinutes)} min"
            } ?: "—",
            "Dictations with duration",
        ),
    )
    Column(verticalArrangement = Arrangement.spacedBy(8.dp)) {
        tiles.chunked(2).forEach { row ->
            Row(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                row.forEach { (label, value, detail) ->
                    val content: @Composable () -> Unit = {
                        Column(Modifier.padding(16.dp)) {
                            Text(label, style = MaterialTheme.typography.labelLarge, color = MaterialTheme.colorScheme.primary)
                            Text(value, style = MaterialTheme.typography.headlineMedium, modifier = Modifier.padding(top = 8.dp), maxLines = 1)
                            Text(detail, style = MaterialTheme.typography.bodySmall, color = MaterialTheme.colorScheme.onSurfaceVariant)
                        }
                    }
                    val colors = CardDefaults.cardColors(containerColor = MaterialTheme.colorScheme.surfaceContainerLow)
                    if (onClick != null) Card(onClick = onClick, Modifier.weight(1f), shape = RoundedCornerShape(20.dp), colors = colors) { content() }
                    else Card(Modifier.weight(1f), shape = RoundedCornerShape(20.dp), colors = colors) { content() }
                }
            }
        }
    }
}
