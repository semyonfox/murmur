package dev.local.murmur.ui

import android.Manifest
import android.content.Context
import android.net.Uri
import android.provider.OpenableColumns
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
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.lazy.items
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.Refresh
import androidx.compose.material3.AlertDialog
import androidx.compose.material3.Card
import androidx.compose.material3.CardDefaults
import androidx.compose.material3.ExtendedFloatingActionButton
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.LinearProgressIndicator
import androidx.compose.material3.MaterialTheme
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
import androidx.compose.ui.hapticfeedback.HapticFeedbackType
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.platform.LocalHapticFeedback
import androidx.compose.ui.res.painterResource
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import dev.local.murmur.Diagnostics
import dev.local.murmur.LectureRecord
import dev.local.murmur.LectureService
import dev.local.murmur.LectureState
import dev.local.murmur.LectureTranscript
import dev.local.murmur.MurmurReadyService
import dev.local.murmur.R
import java.text.DateFormat
import java.util.Date

// the record and import entry points are shared with home
internal class LectureActions(val record: () -> Unit, val import: () -> Unit)

@Composable
internal fun rememberLectureActions(model: AppModel, onRecording: () -> Unit, onOpenRecognition: () -> Unit): LectureActions {
    val context = LocalContext.current
    val snackbar = LocalSnackbar.current
    var setupIssue by remember { mutableStateOf<String?>(null) }

    fun ready(): Boolean {
        val issue = model.recognitionIssue(forSavedAudio = true)
        setupIssue = issue
        return issue == null
    }

    fun start() {
        MurmurReadyService.stop(context)
        runCatching { LectureService.record(context) }
            .onSuccess { onRecording() }
            .onFailure {
                Diagnostics.error("lecture", "could not start the lecture service", it)
                snackbar.showLater(it.message ?: "Could not start recording.")
            }
    }

    val microphone = rememberLauncherForActivityResult(ActivityResultContracts.RequestPermission()) { granted ->
        model.refresh()
        if (granted) start() else snackbar.showLater("Microphone access is needed to record a lecture.")
    }
    val picker = rememberLauncherForActivityResult(ActivityResultContracts.OpenDocument()) { uri: Uri? ->
        if (uri == null || !ready()) return@rememberLauncherForActivityResult
        runCatching { LectureService.import(context, uri, displayName(context, uri)) }
            .onFailure {
                Diagnostics.error("lecture", "could not start an import", it)
                snackbar.showLater(it.message ?: "Could not open audio file.")
            }
    }

    setupIssue?.let { issue ->
        AlertDialog(
            onDismissRequest = { setupIssue = null },
            title = { Text("Set up lecture transcription") },
            text = { Text("$issue Lectures and imported audio need a downloaded model or a configured endpoint.") },
            confirmButton = { TextButton(onClick = { setupIssue = null; onOpenRecognition() }) { Text("Open Recognition") } },
            dismissButton = { TextButton(onClick = { setupIssue = null }) { Text("Close") } },
        )
    }

    return LectureActions(
        record = {
            when {
                LectureService.state.value.phase == LectureState.Phase.RECORDING -> onRecording()
                LectureService.isActive -> snackbar.showLater("Wait for the current lecture to finish.")
                !ready() -> Unit
                !model.micAllowed -> microphone.launch(Manifest.permission.RECORD_AUDIO)
                else -> start()
            }
        },
        import = {
            when {
                LectureService.isActive -> snackbar.showLater("Wait for the current lecture to finish.")
                ready() -> picker.launch(arrayOf("audio/*"))
            }
        },
    )
}

private fun displayName(context: Context, uri: Uri): String =
    context.contentResolver.query(uri, arrayOf(OpenableColumns.DISPLAY_NAME), null, null, null)
        ?.use { cursor -> if (cursor.moveToFirst()) cursor.getString(0) else null }
        ?.substringBeforeLast('.')?.takeIf(String::isNotBlank) ?: "Imported audio"

@Composable
internal fun LecturesScreen(
    model: AppModel,
    actions: LectureActions,
    onOpenLecture: (Long) -> Unit,
    onOpenRecorder: () -> Unit,
) {
    val haptics = LocalHapticFeedback.current
    val state by LectureService.state.collectAsState()
    val records = remember(model.revision, state.phase, state.part, state.lectureId, state.message) {
        runCatching { model.history.lectures() }.onFailure { Diagnostics.error("history", "could not load lectures", it) }
    }

    ScreenScaffold(
        title = "Lectures",
        actions = {
            IconButton(onClick = actions.import) {
                Icon(painterResource(R.drawable.ic_upload), contentDescription = "Import audio file")
            }
        },
        floatingActionButton = {
            val recording = state.phase == LectureState.Phase.RECORDING
            ExtendedFloatingActionButton(
                onClick = { haptics.performHapticFeedback(HapticFeedbackType.Confirm); actions.record() },
                icon = { Icon(painterResource(R.drawable.ic_microphone), contentDescription = null) },
                text = { Text(if (recording) "Back to recorder" else "Record") },
                containerColor = if (recording) LocalExtraColors.current.recording else MaterialTheme.colorScheme.primaryContainer,
                contentColor = if (recording) LocalExtraColors.current.onRecording else MaterialTheme.colorScheme.onPrimaryContainer,
            )
        },
    ) {
        if (state.phase == LectureState.Phase.RECORDING) item(key = "live") {
            LiveRecordingCard(state, onOpenRecorder)
        }
        if (state.phase == LectureState.Phase.PREPARING) item(key = "preparing") {
            PaddedCard {
                Text("Preparing audio…", style = MaterialTheme.typography.titleSmall)
                LinearProgressIndicator(Modifier.fillMaxWidth())
                Hint("Decoding the file to 16 kHz mono before transcription.")
            }
        }
        val list = records.getOrNull()
        when {
            list == null -> item { Hint("Saved lectures could not be loaded.") }
            list.isEmpty() && !state.busy -> item(key = "empty") { EmptyLectures() }
            else -> items(list, key = { it.id }) { record ->
                LectureCard(
                    record = record,
                    state = state,
                    onClick = {
                        if (state.phase == LectureState.Phase.RECORDING && state.lectureId == record.id) onOpenRecorder()
                        else onOpenLecture(record.id)
                    },
                )
            }
        }
        item { Hint("Audio and text stay on this phone until their retention period ends or you delete them.") }
    }
}

@Composable
internal fun LiveRecordingCard(state: LectureState, onClick: () -> Unit) {
    val levels by LectureService.levels.collectAsState()
    val red = LocalExtraColors.current.recording
    Card(
        onClick = onClick,
        shape = RoundedCornerShape(24.dp),
        colors = CardDefaults.cardColors(containerColor = MaterialTheme.colorScheme.errorContainer.copy(alpha = 0.45f)),
        modifier = Modifier.fillMaxWidth(),
    ) {
        Column(Modifier.padding(16.dp)) {
            Row(verticalAlignment = Alignment.CenterVertically) {
                Box(Modifier.size(10.dp).background(if (state.paused) MaterialTheme.colorScheme.outline else red, CircleShape))
                Spacer(Modifier.size(8.dp))
                Text(if (state.paused) "Paused" else "Recording", style = MaterialTheme.typography.titleSmall,
                    modifier = Modifier.weight(1f))
                Text(LectureTranscript.clock(state.recordedMs),
                    style = MaterialTheme.typography.titleMedium.copy(fontFeatureSettings = "tnum"))
            }
            Waveform(levels, if (state.paused) MaterialTheme.colorScheme.outline else red,
                MaterialTheme.colorScheme.outlineVariant, Modifier.fillMaxWidth().height(48.dp).padding(top = 8.dp))
        }
    }
}

@Composable
private fun EmptyLectures() {
    Column(
        Modifier.fillMaxWidth().padding(vertical = 48.dp),
        horizontalAlignment = Alignment.CenterHorizontally,
        verticalArrangement = Arrangement.spacedBy(12.dp),
    ) {
        Box(
            Modifier.size(88.dp).background(MaterialTheme.colorScheme.secondaryContainer, CircleShape),
            contentAlignment = Alignment.Center,
        ) {
            Icon(painterResource(R.drawable.ic_school), contentDescription = null, Modifier.size(40.dp),
                tint = MaterialTheme.colorScheme.onSecondaryContainer)
        }
        Text("No lectures yet", style = MaterialTheme.typography.titleMedium)
        Text(
            "Record a lecture or import an audio file. Murmur transcribes it in two-minute parts with your chosen model.",
            style = MaterialTheme.typography.bodyMedium,
            color = MaterialTheme.colorScheme.onSurfaceVariant,
            textAlign = androidx.compose.ui.text.style.TextAlign.Center,
        )
    }
}

@Composable
private fun LectureCard(record: LectureRecord, state: LectureState, onClick: () -> Unit) {
    val active = state.busy && state.lectureId == record.id
    val interrupted = !active && record.status in listOf("recording", "processing")
    Card(
        onClick = onClick,
        shape = RoundedCornerShape(24.dp),
        colors = CardDefaults.cardColors(containerColor = MaterialTheme.colorScheme.surfaceContainerLow),
        modifier = Modifier.fillMaxWidth().animateContentSize(),
    ) {
        Column(Modifier.padding(16.dp), verticalArrangement = Arrangement.spacedBy(6.dp)) {
            Text(record.title, style = MaterialTheme.typography.titleMedium, maxLines = 2, overflow = TextOverflow.Ellipsis)
            Text(
                listOfNotNull(
                    DateFormat.getDateTimeInstance(DateFormat.MEDIUM, DateFormat.SHORT).format(Date(record.timestamp)),
                    record.durationMs.takeIf { it > 0 }?.let { durationLabel(it) },
                ).joinToString(" · "),
                style = MaterialTheme.typography.bodySmall,
                color = MaterialTheme.colorScheme.onSurfaceVariant,
            )
            when {
                active && state.phase == LectureState.Phase.TRANSCRIBING -> {
                    val progress = if (state.parts > 0) state.part.toFloat() / state.parts else 0f
                    if (state.parts > 0) LinearProgressIndicator(progress = { progress }, Modifier.fillMaxWidth().padding(top = 4.dp))
                    else LinearProgressIndicator(Modifier.fillMaxWidth().padding(top = 4.dp))
                    Text(
                        if (state.parts > 0) "Transcribing part ${minOf(state.part + 1, state.parts)} of ${state.parts}" else "Starting transcription…",
                        style = MaterialTheme.typography.labelMedium,
                        color = MaterialTheme.colorScheme.primary,
                    )
                }
                active && state.phase == LectureState.Phase.RECORDING -> StatusChip("Recording now", error = true)
                interrupted -> StatusChip("Interrupted · open to retry", error = true)
                record.status == "failed" -> StatusChip("Failed · open to retry", error = true)
            }
            val preview = LectureTranscript.plain(record.finalText.ifBlank { record.raw })
            if (preview.isNotBlank()) Text(
                preview,
                style = MaterialTheme.typography.bodyMedium,
                maxLines = 3,
                overflow = TextOverflow.Ellipsis,
            )
        }
    }
}

@Composable
private fun StatusChip(label: String, error: Boolean) {
    val color = if (error) MaterialTheme.colorScheme.error else MaterialTheme.colorScheme.onSurfaceVariant
    Row(verticalAlignment = Alignment.CenterVertically, modifier = Modifier.padding(top = 2.dp)) {
        if (error) Icon(Icons.Filled.Refresh, contentDescription = null, Modifier.size(16.dp), tint = color)
        Spacer(Modifier.size(6.dp))
        Text(label, style = MaterialTheme.typography.labelLarge, color = color)
    }
}

internal fun durationLabel(ms: Long): String {
    val minutes = ms / 60_000
    return when {
        minutes >= 60 -> "${minutes / 60} h ${minutes % 60} min"
        minutes >= 1 -> "$minutes min"
        else -> "${(ms / 1000).coerceAtLeast(1)} s"
    }
}
