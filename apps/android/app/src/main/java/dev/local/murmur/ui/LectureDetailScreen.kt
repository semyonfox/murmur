package dev.local.murmur.ui

import android.content.Context
import android.content.Intent
import android.media.AudioAttributes
import android.media.MediaPlayer
import androidx.compose.animation.animateColorAsState
import androidx.compose.foundation.background
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.lazy.itemsIndexed
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.foundation.text.selection.SelectionContainer
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.Delete
import androidx.compose.material.icons.filled.Edit
import androidx.compose.material.icons.filled.MoreVert
import androidx.compose.material.icons.filled.Refresh
import androidx.compose.material.icons.filled.Share
import androidx.compose.material3.AlertDialog
import androidx.compose.material3.Button
import androidx.compose.material3.DropdownMenu
import androidx.compose.material3.DropdownMenuItem
import androidx.compose.material3.ExperimentalMaterial3Api
import androidx.compose.material3.FilledIconButton
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.LinearProgressIndicator
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedTextField
import androidx.compose.material3.SegmentedButton
import androidx.compose.material3.SegmentedButtonDefaults
import androidx.compose.material3.SingleChoiceSegmentedButtonRow
import androidx.compose.material3.Slider
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.runtime.Composable
import androidx.compose.runtime.DisposableEffect
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.collectAsState
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableFloatStateOf
import androidx.compose.runtime.mutableIntStateOf
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.saveable.rememberSaveable
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.graphicsLayer
import androidx.compose.ui.hapticfeedback.HapticFeedbackType
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.platform.LocalHapticFeedback
import androidx.compose.ui.res.painterResource
import androidx.compose.ui.unit.dp
import androidx.core.content.FileProvider
import dev.local.murmur.LectureService
import dev.local.murmur.LectureState
import dev.local.murmur.LectureTranscript
import dev.local.murmur.R
import dev.local.murmur.TranscriptSegment
import kotlinx.coroutines.delay
import java.io.File
import java.text.DateFormat
import java.util.Date

@OptIn(ExperimentalMaterial3Api::class)
@Composable
internal fun LectureDetailScreen(model: AppModel, id: Long, onBack: () -> Unit, onOpenRecognition: () -> Unit) {
    val context = LocalContext.current
    val snackbar = LocalSnackbar.current
    val state by LectureService.state.collectAsState()
    val record = remember(model.revision, id, state.phase, state.part, state.message) {
        runCatching { model.history.lecture(id) }.getOrNull()
    }
    LaunchedEffect(record == null) { if (record == null) onBack() }
    if (record == null) return

    val active = state.busy && state.lectureId == id
    val file = remember(record.fileName) { runCatching { model.history.lectureFile(record.fileName) }.getOrNull() }
    val player = rememberPlayer(file)
    var showRaw by rememberSaveable { mutableStateOf(false) }
    var menu by remember { mutableStateOf(false) }
    var renaming by rememberSaveable { mutableStateOf(false) }
    var confirmRetry by rememberSaveable { mutableStateOf(false) }
    var confirmDelete by rememberSaveable { mutableStateOf(false) }
    val hasCleaned = record.finalText.isNotBlank() && record.finalText != record.raw
    val text = if (showRaw || record.finalText.isBlank()) record.raw else record.finalText
    val segments = remember(text) { LectureTranscript.parse(text) }
    val failed = !active && record.status != "done"

    ScreenScaffold(
        title = record.title,
        onBack = onBack,
        actions = {
            IconButton(onClick = { renaming = true }) { Icon(Icons.Filled.Edit, contentDescription = "Rename") }
            IconButton(onClick = { menu = true }) { Icon(Icons.Filled.MoreVert, contentDescription = "More options") }
            DropdownMenu(expanded = menu, onDismissRequest = { menu = false }) {
                val plain = LectureTranscript.plain(record.finalText.ifBlank { record.raw })
                DropdownMenuItem(
                    text = { Text("Copy text") },
                    leadingIcon = { Icon(painterResource(R.drawable.ic_copy), null) },
                    enabled = plain.isNotBlank(),
                    onClick = { menu = false; copyText(context, "Murmur lecture", plain); snackbar.showLater("Transcript copied.") },
                )
                DropdownMenuItem(
                    text = { Text("Share text") },
                    leadingIcon = { Icon(Icons.Filled.Share, null) },
                    enabled = plain.isNotBlank(),
                    onClick = { menu = false; shareText(context, record.title, plain) },
                )
                DropdownMenuItem(
                    text = { Text("Share audio") },
                    leadingIcon = { Icon(Icons.Filled.Share, null) },
                    enabled = file?.exists() == true,
                    onClick = { menu = false; file?.let { shareAudio(context, it) } },
                )
                DropdownMenuItem(
                    text = { Text("Transcribe again") },
                    leadingIcon = { Icon(Icons.Filled.Refresh, null) },
                    enabled = !state.busy,
                    onClick = { menu = false; confirmRetry = true },
                )
                DropdownMenuItem(
                    text = { Text("Delete") },
                    leadingIcon = { Icon(Icons.Filled.Delete, null) },
                    enabled = !active,
                    onClick = { menu = false; confirmDelete = true },
                )
            }
        },
    ) {
        item(key = "meta") {
            Text(
                listOfNotNull(
                    DateFormat.getDateTimeInstance(DateFormat.LONG, DateFormat.SHORT).format(Date(record.timestamp)),
                    record.durationMs.takeIf { it > 0 }?.let(::durationLabel),
                ).joinToString(" · "),
                style = MaterialTheme.typography.bodyMedium,
                color = MaterialTheme.colorScheme.onSurfaceVariant,
                modifier = Modifier.padding(horizontal = 4.dp),
            )
        }
        if (player != null) item(key = "player") { PlayerCard(player) }
        if (active && state.phase == LectureState.Phase.TRANSCRIBING) item(key = "progress") {
            PaddedCard {
                Text(
                    if (state.parts > 0) "Transcribing part ${minOf(state.part + 1, state.parts)} of ${state.parts}" else "Starting transcription…",
                    style = MaterialTheme.typography.titleSmall,
                )
                if (state.parts > 0) LinearProgressIndicator(progress = { state.part.toFloat() / state.parts }, Modifier.fillMaxWidth())
                else LinearProgressIndicator(Modifier.fillMaxWidth())
                Hint("Text appears below as each part finishes. You can leave this screen.")
            }
        }
        if (failed) item(key = "failed") {
            PaddedCard {
                Text(
                    if (record.status == "failed") "Transcription did not finish" else "This lecture was interrupted",
                    style = MaterialTheme.typography.titleSmall,
                    color = MaterialTheme.colorScheme.error,
                )
                Hint("The audio and any text so far are kept. Transcribing again replaces the text once new parts arrive.")
                Button(onClick = { confirmRetry = true }, enabled = !state.busy) { Text("Transcribe again") }
            }
        }
        if (hasCleaned) item(key = "toggle") {
            SingleChoiceSegmentedButtonRow(Modifier.fillMaxWidth()) {
                SegmentedButton(selected = !showRaw, onClick = { showRaw = false },
                    shape = SegmentedButtonDefaults.itemShape(0, 2)) { Text("Cleaned") }
                SegmentedButton(selected = showRaw, onClick = { showRaw = true },
                    shape = SegmentedButtonDefaults.itemShape(1, 2)) { Text("Raw") }
            }
        }
        if (segments.isEmpty() && !active) item(key = "none") {
            Hint(if (failed) "No text yet." else "No speech was found in this recording.")
        }
        itemsIndexed(segments, key = { index, _ -> "segment-$index" }) { index, segment ->
            val next = segments.getOrNull(index + 1)?.startMs
            val current = player != null && segment.startMs != null && player.position >= segment.startMs &&
                (next == null || player.position < next) && (player.playing || player.position > 0)
            SegmentRow(segment, current, onSeek = player?.let { p -> { ms: Long -> p.seekTo(ms); p.play() } })
        }
    }

    if (renaming) RenameDialog(record.title, onDismiss = { renaming = false }) { title ->
        runCatching { model.history.renameLecture(id, title) }
            .onSuccess { renaming = false; model.refresh() }
            .onFailure { snackbar.showLater(it.message ?: "Could not rename.") }
    }
    if (confirmRetry) ConfirmDialog(
        title = "Transcribe again?",
        text = "Murmur sends this audio to your selected model or endpoint again. An online endpoint may charge for the new requests. The current text stays until new text arrives.",
        confirm = "Transcribe",
        onConfirm = {
            val issue = model.recognitionIssue(forSavedAudio = true)
            if (issue != null) {
                snackbar.showLater(issue)
                onOpenRecognition()
            } else runCatching { LectureService.retry(context, id) }
                .onFailure { snackbar.showLater(it.message ?: "Could not start transcription.") }
        },
        onDismiss = { confirmRetry = false },
    )
    if (confirmDelete) ConfirmDialog(
        title = "Delete lecture?",
        text = "This removes the audio, raw transcript and cleaned transcript from this phone.",
        confirm = "Delete",
        onConfirm = {
            player?.release()
            model.history.deleteLecture(id)
            model.refresh()
            onBack()
        },
        onDismiss = { confirmDelete = false },
    )
}

@Composable
private fun SegmentRow(segment: TranscriptSegment, current: Boolean, onSeek: ((Long) -> Unit)?) {
    val haptics = LocalHapticFeedback.current
    val background by animateColorAsState(
        if (current) MaterialTheme.colorScheme.secondaryContainer else Color.Transparent, label = "segment",
    )
    Column(
        Modifier.fillMaxWidth().clip(RoundedCornerShape(16.dp)).background(background).padding(horizontal = 8.dp, vertical = 4.dp),
    ) {
        segment.startMs?.let { start ->
            TextButton(
                onClick = { haptics.performHapticFeedback(HapticFeedbackType.SegmentTick); onSeek?.invoke(start) },
                enabled = onSeek != null,
                contentPadding = androidx.compose.foundation.layout.PaddingValues(horizontal = 8.dp),
            ) {
                Icon(painterResource(R.drawable.ic_play), contentDescription = null, Modifier.size(16.dp))
                Spacer(Modifier.size(4.dp))
                Text(LectureTranscript.clock(start), style = MaterialTheme.typography.labelLarge.copy(fontFeatureSettings = "tnum"))
            }
        }
        SelectionContainer {
            Text(segment.text, style = MaterialTheme.typography.bodyLarge, modifier = Modifier.padding(horizontal = 8.dp, vertical = 4.dp))
        }
    }
}

@Composable
private fun PlayerCard(player: LecturePlayer) {
    val haptics = LocalHapticFeedback.current
    var dragging by remember { mutableStateOf(false) }
    var dragValue by remember { mutableFloatStateOf(0f) }
    PaddedCard {
        Slider(
            value = if (dragging) dragValue else player.position.toFloat(),
            onValueChange = { dragging = true; dragValue = it },
            onValueChangeFinished = { player.seekTo(dragValue.toLong()); dragging = false },
            valueRange = 0f..player.duration.coerceAtLeast(1).toFloat(),
        )
        Row(Modifier.fillMaxWidth()) {
            val labelStyle = MaterialTheme.typography.labelMedium.copy(fontFeatureSettings = "tnum")
            Text(LectureTranscript.clock(if (dragging) dragValue.toLong() else player.position.toLong()), style = labelStyle,
                color = MaterialTheme.colorScheme.onSurfaceVariant)
            Spacer(Modifier.weight(1f))
            Text(LectureTranscript.clock(player.duration.toLong()), style = labelStyle,
                color = MaterialTheme.colorScheme.onSurfaceVariant)
        }
        Row(Modifier.fillMaxWidth(), horizontalArrangement = Arrangement.Center, verticalAlignment = Alignment.CenterVertically) {
            IconButton(onClick = { player.seekTo(player.position - 10_000L) }) {
                Icon(painterResource(R.drawable.ic_replay), contentDescription = "Back 10 seconds")
            }
            Spacer(Modifier.size(16.dp))
            FilledIconButton(
                onClick = {
                    haptics.performHapticFeedback(HapticFeedbackType.ContextClick)
                    if (player.playing) player.pause() else player.play()
                },
                modifier = Modifier.size(56.dp),
            ) {
                Icon(
                    painterResource(if (player.playing) R.drawable.ic_pause else R.drawable.ic_play),
                    contentDescription = if (player.playing) "Pause" else "Play",
                )
            }
            Spacer(Modifier.size(16.dp))
            IconButton(onClick = { player.seekTo(player.position + 10_000L) }) {
                Icon(painterResource(R.drawable.ic_replay), contentDescription = "Forward 10 seconds",
                    modifier = Modifier.graphicsLayer(scaleX = -1f))
            }
        }
    }
}

internal class LecturePlayer(private val media: MediaPlayer) {
    var playing by mutableStateOf(false)
        private set
    var position by mutableIntStateOf(0)
        private set
    val duration: Int = media.duration.coerceAtLeast(0)
    private var released = false

    fun play() {
        if (released) return
        if (position >= duration) media.seekTo(0)
        media.start()
        playing = true
    }

    fun pause() {
        if (released || !media.isPlaying) return
        media.pause()
        playing = false
    }

    fun seekTo(ms: Long) {
        if (released) return
        val target = ms.coerceIn(0, duration.toLong()).toInt()
        media.seekTo(target.toLong(), MediaPlayer.SEEK_CLOSEST)
        position = target
    }

    fun tick() {
        if (released) return
        position = media.currentPosition
        if (playing && !media.isPlaying) playing = false
    }

    fun release() {
        if (released) return
        released = true
        playing = false
        media.release()
    }
}

@Composable
private fun rememberPlayer(file: File?): LecturePlayer? {
    val player = remember(file) {
        if (file == null || !file.exists() || file.length() <= 44) null else runCatching {
            val media = MediaPlayer().apply {
                setAudioAttributes(AudioAttributes.Builder()
                    .setUsage(AudioAttributes.USAGE_MEDIA)
                    .setContentType(AudioAttributes.CONTENT_TYPE_SPEECH).build())
                setDataSource(file.absolutePath)
                prepare()
            }
            LecturePlayer(media).also { player -> media.setOnCompletionListener { player.tick() } }
        }.getOrNull()
    }
    DisposableEffect(player) { onDispose { player?.release() } }
    LaunchedEffect(player, player?.playing) {
        while (player != null && player.playing) {
            player.tick()
            delay(200)
        }
        player?.tick()
    }
    return player
}

@Composable
private fun RenameDialog(current: String, onDismiss: () -> Unit, onSave: (String) -> Unit) {
    var value by rememberSaveable { mutableStateOf(current) }
    AlertDialog(
        onDismissRequest = onDismiss,
        title = { Text("Rename lecture") },
        text = { OutlinedTextField(value = value, onValueChange = { value = it }, singleLine = true, label = { Text("Title") }) },
        confirmButton = { TextButton(onClick = { onSave(value) }, enabled = value.isNotBlank()) { Text("Save") } },
        dismissButton = { TextButton(onClick = onDismiss) { Text("Cancel") } },
    )
}

private fun shareText(context: Context, title: String, text: String) {
    context.startActivity(Intent.createChooser(Intent(Intent.ACTION_SEND).apply {
        type = "text/plain"
        putExtra(Intent.EXTRA_SUBJECT, title)
        putExtra(Intent.EXTRA_TEXT, text)
    }, "Share transcript"))
}

private fun shareAudio(context: Context, file: File) {
    val uri = FileProvider.getUriForFile(context, "${context.packageName}.updates", file)
    context.startActivity(Intent.createChooser(Intent(Intent.ACTION_SEND).apply {
        type = "audio/wav"
        putExtra(Intent.EXTRA_STREAM, uri)
        addFlags(Intent.FLAG_GRANT_READ_URI_PERMISSION)
    }, "Share lecture audio"))
}
