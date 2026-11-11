package dev.local.murmur.ui

import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.navigationBarsPadding
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.foundation.text.selection.SelectionContainer
import androidx.compose.foundation.verticalScroll
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.Delete
import androidx.compose.material3.Card
import androidx.compose.material3.CardDefaults
import androidx.compose.material3.ExperimentalMaterial3Api
import androidx.compose.material3.FilledTonalButton
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.ModalBottomSheet
import androidx.compose.material3.OutlinedButton
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.material3.rememberModalBottomSheetState
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.res.painterResource
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import dev.local.murmur.AnonymousTelemetry
import dev.local.murmur.Diagnostics
import dev.local.murmur.DictationRecord
import dev.local.murmur.R
import dev.local.murmur.TelemetryError
import dev.local.murmur.TelemetryRoute
import java.text.DateFormat
import java.util.Date

@OptIn(ExperimentalMaterial3Api::class)
@Composable
internal fun HistoryScreen(model: AppModel, onOpenStorage: () -> Unit) {
    val context = LocalContext.current
    val snackbar = LocalSnackbar.current
    val revision = model.revision
    val records = remember(revision) {
        runCatching { model.history.recent() }.onFailure {
            Diagnostics.error("history", "could not load dictations", it)
            AnonymousTelemetry.error(context, TelemetryError.STORAGE_FAILED, TelemetryRoute.LIBRARY)
        }
    }
    var open by remember { mutableStateOf<DictationRecord?>(null) }
    var deleting by remember { mutableStateOf<DictationRecord?>(null) }
    val latest = remember(revision) { model.settings.latestTranscript }
    val latestRaw = remember(revision) { model.settings.latestRawTranscript }

    ScreenScaffold(
        title = "History",
        actions = { TextButton(onClick = onOpenStorage) { Text("Storage") } },
    ) {
        if (latest != null) item(key = "latest") {
            PaddedCard {
                Text("Latest result", style = MaterialTheme.typography.labelLarge, color = MaterialTheme.colorScheme.primary)
                SelectionContainer { Text(latest, style = MaterialTheme.typography.bodyLarge) }
                if (latestRaw != null && latestRaw != latest) {
                    Text("Raw transcript", style = MaterialTheme.typography.labelMedium,
                        color = MaterialTheme.colorScheme.onSurfaceVariant, modifier = Modifier.padding(top = 4.dp))
                    SelectionContainer { Text(latestRaw, style = MaterialTheme.typography.bodyMedium) }
                }
                Hint("Kept here in case the text field changed before Murmur could insert it.", Modifier.padding(horizontal = 0.dp))
                Row(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                    FilledTonalButton(onClick = { copyText(context, "Murmur transcript", latest); snackbar.showLater("Copied.") }) {
                        Text("Copy")
                    }
                    OutlinedButton(onClick = { model.settings.latestTranscript = null; model.refresh() }) { Text("Clear") }
                }
            }
        }
        val list = records.getOrNull()
        when {
            list == null -> item(key = "error") {
                PaddedCard {
                    Text("History could not be loaded.")
                    TextButton(onClick = model::refresh) { Text("Try again") }
                }
            }
            list.isEmpty() -> item(key = "empty") { Hint("No past dictations yet. Anything you dictate with the bubble shows up here.") }
            else -> {
                var previousDay: String? = null
                list.forEach { record ->
                    val day = DateFormat.getDateInstance(DateFormat.FULL).format(Date(record.timestamp))
                    if (day != previousDay) {
                        previousDay = day
                        item(key = "day-$day") { SectionLabel(day) }
                    }
                    item(key = record.id) {
                        Card(
                            onClick = { open = record },
                            shape = RoundedCornerShape(20.dp),
                            colors = CardDefaults.cardColors(containerColor = MaterialTheme.colorScheme.surfaceContainerLow),
                            modifier = Modifier.fillMaxWidth(),
                        ) {
                            Column(Modifier.padding(16.dp), verticalArrangement = Arrangement.spacedBy(4.dp)) {
                                Text(
                                    DateFormat.getTimeInstance(DateFormat.SHORT).format(Date(record.timestamp)),
                                    style = MaterialTheme.typography.labelMedium,
                                    color = MaterialTheme.colorScheme.onSurfaceVariant,
                                )
                                Text(record.finalText, style = MaterialTheme.typography.bodyLarge, maxLines = 3, overflow = TextOverflow.Ellipsis)
                            }
                        }
                    }
                }
            }
        }
    }

    open?.let { record ->
        ModalBottomSheet(onDismissRequest = { open = null }, sheetState = rememberModalBottomSheetState(skipPartiallyExpanded = true)) {
            Column(
                Modifier.fillMaxWidth().verticalScroll(rememberScrollState()).padding(horizontal = 24.dp).navigationBarsPadding(),
                verticalArrangement = Arrangement.spacedBy(12.dp),
            ) {
                Text(
                    DateFormat.getDateTimeInstance(DateFormat.LONG, DateFormat.SHORT).format(Date(record.timestamp)),
                    style = MaterialTheme.typography.labelLarge,
                    color = MaterialTheme.colorScheme.onSurfaceVariant,
                )
                SelectionContainer { Text(record.finalText, style = MaterialTheme.typography.bodyLarge) }
                if (record.raw != record.finalText) {
                    Text("Raw transcript", style = MaterialTheme.typography.titleSmall, color = MaterialTheme.colorScheme.primary)
                    SelectionContainer { Text(record.raw, style = MaterialTheme.typography.bodyMedium) }
                }
                Row(horizontalArrangement = Arrangement.spacedBy(8.dp), modifier = Modifier.padding(bottom = 16.dp)) {
                    FilledTonalButton(onClick = { copyText(context, "Murmur transcript", record.finalText); snackbar.showLater("Copied.") }) {
                        Icon(painterResource(R.drawable.ic_copy), null, Modifier.padding(end = 8.dp))
                        Text("Copy")
                    }
                    if (record.raw != record.finalText) OutlinedButton(onClick = {
                        copyText(context, "Murmur raw transcript", record.raw)
                        snackbar.showLater("Raw transcript copied.")
                    }) { Text("Copy raw") }
                    IconButton(onClick = { deleting = record }) { Icon(Icons.Filled.Delete, contentDescription = "Delete dictation") }
                }
            }
        }
    }
    deleting?.let { record ->
        ConfirmDialog(
            title = "Delete dictation?",
            text = "This removes the raw and cleaned text from this phone.",
            confirm = "Delete",
            onConfirm = {
                model.history.delete(record.id)
                if (model.settings.latestHistoryId == record.id) model.settings.latestTranscript = null
                open = null
                model.refresh()
            },
            onDismiss = { deleting = null },
        )
    }
}
