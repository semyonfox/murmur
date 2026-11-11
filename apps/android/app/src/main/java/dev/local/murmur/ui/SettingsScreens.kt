package dev.local.murmur.ui

import android.content.Context
import android.content.Intent
import android.media.audiofx.NoiseSuppressor
import android.net.Uri
import android.provider.Settings
import android.util.JsonWriter
import androidx.activity.compose.rememberLauncherForActivityResult
import androidx.activity.result.contract.ActivityResultContracts
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.text.KeyboardActions
import androidx.compose.foundation.text.KeyboardOptions
import androidx.compose.foundation.verticalScroll
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.Add
import androidx.compose.material.icons.filled.Close
import androidx.compose.material.icons.automirrored.filled.KeyboardArrowRight
import androidx.compose.material.icons.filled.Search
import androidx.compose.material3.AlertDialog
import androidx.compose.material3.Button
import androidx.compose.material3.FilledTonalButton
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.LinearProgressIndicator
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedTextField
import androidx.compose.material3.RadioButton
import androidx.compose.material3.Switch
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableLongStateOf
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberCoroutineScope
import androidx.compose.runtime.saveable.rememberSaveable
import androidx.compose.runtime.setValue
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.res.painterResource
import androidx.compose.ui.text.input.ImeAction
import androidx.compose.ui.unit.dp
import dev.local.murmur.AnonymousTelemetry
import dev.local.murmur.BuildConfig
import dev.local.murmur.Diagnostics
import dev.local.murmur.LectureService
import dev.local.murmur.MurmurReadyService
import dev.local.murmur.R
import java.io.File
import java.io.OutputStreamWriter
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext

@Composable
internal fun SettingsScreen(model: AppModel, navigate: (Route) -> Unit) {
    val context = LocalContext.current
    val revision = model.revision
    val noiseAvailable = remember { NoiseSuppressor.isAvailable() }
    var noise by remember(revision) { mutableStateOf(model.settings.noiseSuppressionEnabled && noiseAvailable) }
    val chevron: @Composable () -> Unit = { Icon(Icons.AutoMirrored.Filled.KeyboardArrowRight, contentDescription = null) }

    ScreenScaffold(title = "Settings") {
        item { SectionLabel("Speech") }
        item {
            GroupCard {
                GroupItem("Recognition", remember(revision) { model.recognitionSummary() },
                    leading = { Icon(painterResource(R.drawable.ic_microphone), null) }, trailing = chevron,
                    onClick = { navigate(Route.Page.RECOGNITION) })
                GroupDivider()
                GroupItem("Transcript cleanup", remember(revision) { model.cleanupSummary() },
                    leading = { Icon(painterResource(R.drawable.ic_document), null) }, trailing = chevron,
                    onClick = { navigate(Route.Page.CLEANUP) })
                GroupDivider()
                val words = remember(revision) { model.settings.dictionary.size }
                GroupItem("Words", if (words == 0) "Preferred spellings for names and terms" else "$words saved",
                    leading = { Icon(painterResource(R.drawable.ic_dictionary), null) }, trailing = chevron,
                    onClick = { navigate(Route.Page.WORDS) })
            }
        }
        item { Hint("Recognition receives audio. Cleanup receives transcript text only when you turn it on.") }
        item { SectionLabel("Input") }
        item {
            GroupCard {
                val bubble = remember(revision) { model.setup() }
                GroupItem("Voice bubble", when {
                    !bubble.bubbleSupported -> "Needs Android 13 or newer"
                    !bubble.bubbleEnabled -> "Not enabled"
                    bubble.voiceReady -> "On"
                    else -> "Enabled, currently off"
                }, leading = { Icon(painterResource(R.drawable.ic_keyboard), null) }, trailing = chevron,
                    onClick = { navigate(Route.Page.BUBBLE) })
                GroupDivider()
                GroupItem(
                    "Reduce background noise",
                    if (noiseAvailable) "Uses the phone's capture effect. It may alter quiet speech." else "Not available on this phone",
                    trailing = {
                        Switch(checked = noise, enabled = noiseAvailable, onCheckedChange = {
                            noise = it
                            model.settings.noiseSuppressionEnabled = it
                        })
                    },
                )
            }
        }
        item { SectionLabel("Data") }
        item {
            GroupCard {
                GroupItem("History and storage", retentionLabel(remember(revision) { model.settings.historyRetentionDays }),
                    trailing = chevron, onClick = { navigate(Route.Page.STORAGE) })
                GroupDivider()
                GroupItem("Stats and provider cost", "Weekly words, pace and OpenRouter usage",
                    trailing = chevron, onClick = { navigate(Route.Page.STATS) })
                GroupDivider()
                val configured = AnonymousTelemetry.configured()
                var telemetry by remember { mutableStateOf(configured && AnonymousTelemetry.enabled(context)) }
                val snackbar = LocalSnackbar.current
                GroupItem(
                    "Share anonymous usage and errors",
                    if (configured) "Screen counts and fixed error categories only. No recordings, transcripts, words or keys."
                    else "Not configured in this build. Nothing is sent.",
                    trailing = {
                        Switch(checked = telemetry, enabled = configured, onCheckedChange = { checked ->
                            if (AnonymousTelemetry.setEnabled(context, checked)) telemetry = checked
                            else {
                                telemetry = false
                                snackbar.showLater("Could not save reporting preference. Reporting stays off.")
                            }
                        })
                    },
                )
            }
        }
        item { SectionLabel("About") }
        item {
            GroupCard {
                GroupItem("Murmur ${BuildConfig.VERSION_NAME}", when (val update = model.update) {
                    is UpdateState.Available -> "Version ${update.update.versionName} is available"
                    is UpdateState.Ready -> "Update downloaded"
                    else -> "Updates and licences"
                }, trailing = chevron, onClick = { navigate(Route.Page.ABOUT) })
            }
        }
    }
}

private fun retentionLabel(days: Int) = when (days) {
    7, 30, 90 -> "Keep text for $days days"
    else -> "Keep text until you delete it"
}

@Composable
internal fun WordsScreen(model: AppModel, onBack: () -> Unit) {
    var revision by remember { mutableStateOf(0) }
    val terms = remember(revision) { model.settings.dictionary }
    var input by rememberSaveable { mutableStateOf("") }
    var error by remember { mutableStateOf<String?>(null) }
    var query by rememberSaveable { mutableStateOf("") }
    fun add() {
        try {
            model.settings.addDictionaryTerm(input)
            input = ""
            error = null
            revision++
            model.refresh()
        } catch (failure: IllegalArgumentException) {
            error = failure.message
        }
    }
    ScreenScaffold(title = "Words", onBack = onBack) {
        item { Hint("Preferred spelling for names and unusual terms. Cleanup uses them when it is on.") }
        item {
            OutlinedTextField(
                value = input,
                onValueChange = { input = it; error = null },
                label = { Text("Add a name or term") },
                singleLine = true,
                isError = error != null,
                supportingText = error?.let { { Text(it) } },
                keyboardOptions = KeyboardOptions(imeAction = ImeAction.Done),
                keyboardActions = KeyboardActions(onDone = { add() }),
                trailingIcon = { IconButton(onClick = ::add, enabled = input.isNotBlank()) { Icon(Icons.Filled.Add, "Add word") } },
                modifier = Modifier.fillMaxWidth(),
            )
        }
        if (terms.size > 6) item {
            OutlinedTextField(
                value = query, onValueChange = { query = it }, singleLine = true,
                leadingIcon = { Icon(Icons.Filled.Search, null) },
                placeholder = { Text("Search ${terms.size} words") },
                modifier = Modifier.fillMaxWidth(),
            )
        }
        val matches = terms.filter { it.contains(query.trim(), ignoreCase = true) }
        if (terms.isEmpty()) item { Hint("No words saved yet.") }
        else if (matches.isEmpty()) item { Hint("No matching words.") }
        else item {
            GroupCard {
                matches.forEachIndexed { index, term ->
                    if (index > 0) GroupDivider()
                    GroupItem(term, trailing = {
                        IconButton(onClick = {
                            model.settings.removeDictionaryTerm(term)
                            revision++
                            model.refresh()
                        }) { Icon(Icons.Filled.Close, contentDescription = "Remove $term") }
                    })
                }
            }
        }
    }
}

@Composable
internal fun BubbleScreen(model: AppModel, onBack: () -> Unit) {
    val voice = rememberVoiceControls(model)
    val setup = remember(model.revision) { model.setup() }
    var restrictedHelp by rememberSaveable { mutableStateOf(false) }
    ScreenScaffold(title = "Voice bubble", onBack = onBack) {
        item {
            Hint("Keep your usual keyboard. The bubble appears beside ordinary text fields on Android 13 and newer. Drag it to move it and tap to dictate.")
        }
        item {
            GroupCard {
                GroupItem(
                    "Accessibility service",
                    when {
                        !setup.bubbleSupported -> "Available on Android 13 and newer"
                        setup.bubbleEnabled -> "Enabled"
                        else -> "Open Accessibility settings to enable"
                    },
                    trailing = if (setup.bubbleSupported && !setup.bubbleEnabled) {
                        { FilledTonalButton(onClick = voice.enableBubble) { Text("Enable") } }
                    } else null,
                )
                GroupDivider()
                GroupItem(
                    "Show the bubble",
                    if (setup.voiceReady) "On. A notification keeps the microphone service available." else "Off",
                    trailing = {
                        Switch(
                            checked = setup.voiceReady,
                            enabled = setup.bubbleSupported && setup.bubbleEnabled && !LectureService.isActive,
                            onCheckedChange = { if (it) voice.start() else voice.stop() },
                        )
                    },
                )
            }
        }
        item { Hint("The bubble stays above the keyboard and remembers where you leave it. It hides itself in password, number and phone fields.") }
        item { TextButton(onClick = { restrictedHelp = true }) { Text("Android says “Restricted setting”?") } }
    }
    if (restrictedHelp) AlertDialog(
        onDismissRequest = { restrictedHelp = false },
        title = { Text("Allow restricted settings") },
        text = { Text("Open Settings → Apps → Murmur → ⋮ → Allow restricted settings. Then return to Accessibility and enable Murmur voice bubble. Android applies this restriction to some sideloaded apps.") },
        confirmButton = { TextButton(onClick = { restrictedHelp = false }) { Text("Close") } },
    )
}

@Composable
internal fun StorageScreen(model: AppModel, onBack: () -> Unit) {
    val context = LocalContext.current
    val snackbar = LocalSnackbar.current
    var retention by remember { mutableStateOf(model.settings.historyRetentionDays) }
    var pending by remember { mutableStateOf<Int?>(null) }
    val exporter = rememberLauncherForActivityResult(ActivityResultContracts.CreateDocument("application/json")) { uri: Uri? ->
        if (uri == null) return@rememberLauncherForActivityResult
        snackbar.showLater("Exporting history…")
        Thread {
            val result = runCatching {
                context.contentResolver.openOutputStream(uri)?.use { output ->
                    JsonWriter(OutputStreamWriter(output, Charsets.UTF_8)).use { writer -> model.history.writeExport(writer) }
                } ?: error("Could not open the selected file.")
            }
            snackbar.showLater(if (result.isSuccess) "Text history exported." else "Could not export history. Choose another location.")
        }.start()
    }
    ScreenScaffold(title = "History and storage", onBack = onBack) {
        item { SectionLabel("Keep text and lecture audio") }
        item {
            GroupCard {
                listOf(0 to "Until I delete it", 7 to "7 days", 30 to "30 days", 90 to "90 days").forEach { (days, label) ->
                    GroupItem(label, leading = { RadioButton(selected = retention == days, onClick = null) }, onClick = {
                        if (days == retention) return@GroupItem
                        if (days == 0) {
                            model.settings.historyRetentionDays = 0
                            retention = 0
                            model.refresh()
                        } else pending = days
                    })
                }
            }
        }
        item { Hint("Shorter periods delete older dictations and lectures now and after future recordings.") }
        item { SectionLabel("Export") }
        item {
            PaddedCard {
                Text("Export text history", style = MaterialTheme.typography.titleMedium)
                Hint("Saves raw and final text for dictations and lectures as an unencrypted JSON file. Audio is not included.", Modifier.padding(0.dp))
                FilledTonalButton(onClick = { exporter.launch("murmur-history.json") }) { Text("Export") }
            }
        }
    }
    pending?.let { days ->
        ConfirmDialog(
            title = "Keep text for $days days?",
            text = "Dictations and lectures older than $days days will be deleted from this phone now and after future recordings.",
            confirm = "Delete older entries",
            onConfirm = {
                model.settings.historyRetentionDays = days
                model.history.pruneOlderThan(days)
                if (model.settings.latestHistoryId?.let { !model.history.contains(it) } == true) model.settings.latestTranscript = null
                retention = days
                model.refresh()
            },
            onDismiss = { pending = null },
        )
    }
}

@Composable
internal fun AboutScreen(model: AppModel, onBack: () -> Unit) {
    val context = LocalContext.current
    var licence by rememberSaveable { mutableStateOf(false) }
    var askInstallPermission by remember { mutableStateOf(false) }
    var stopVoice by remember { mutableStateOf(false) }
    val snackbar = LocalSnackbar.current
    val scope = rememberCoroutineScope()
    var logSize by remember { mutableLongStateOf(Diagnostics.sizeBytes()) }

    fun install(apk: File) {
        when {
            MurmurReadyService.isActive || LectureService.isActive ->
                snackbar.showLater("Finish the voice bubble or lecture before installing.")
            !context.packageManager.canRequestPackageInstalls() -> askInstallPermission = true
            else -> {
                val uri = androidx.core.content.FileProvider.getUriForFile(context, "${context.packageName}.updates", apk)
                runCatching {
                    // still the documented way to hand a sideloaded apk to the system installer
                    @Suppress("DEPRECATION")
                    context.startActivity(Intent(Intent.ACTION_INSTALL_PACKAGE).apply {
                        data = uri
                        addFlags(Intent.FLAG_GRANT_READ_URI_PERMISSION)
                    })
                }.onFailure { snackbar.showLater("Android could not open the installer.") }
            }
        }
    }

    ScreenScaffold(title = "About", onBack = onBack) {
        item {
            PaddedCard {
                Text("Murmur ${BuildConfig.VERSION_NAME}", style = MaterialTheme.typography.titleLarge)
                val update = model.update
                Text(
                    when {
                        BuildConfig.UPDATE_MANIFEST_URL.isBlank() -> "Updates are not configured in this build."
                        update is UpdateState.Checking -> "Checking for updates…"
                        update is UpdateState.UpToDate -> "Murmur is up to date."
                        update is UpdateState.Available -> "Version ${update.update.versionName} is available (${update.update.sizeBytes / 1_000_000} MB)."
                        update is UpdateState.Downloading -> "Downloading update… ${update.percent}%"
                        update is UpdateState.Ready -> "Download verified. Android will ask you to confirm the update."
                        update is UpdateState.Failed -> update.message
                        else -> "Check for a newer signed version."
                    },
                    style = MaterialTheme.typography.bodyMedium,
                    color = MaterialTheme.colorScheme.onSurfaceVariant,
                )
                if (update is UpdateState.Downloading) LinearProgressIndicator(progress = { update.percent / 100f }, Modifier.fillMaxWidth())
                Row(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                    when (update) {
                        is UpdateState.Available -> Button(onClick = {
                            if (MurmurReadyService.isActive) stopVoice = true else model.downloadUpdate(update.update, ::install)
                        }) { Text("Download update") }
                        is UpdateState.Ready -> Button(onClick = { install(update.apk) }) { Text("Install update") }
                        is UpdateState.Failed -> Button(onClick = {
                            update.update?.let { model.downloadUpdate(it, ::install) } ?: model.checkForUpdates(automatic = false)
                        }) { Text(if (update.update != null) "Retry download" else "Try again") }
                        else -> FilledTonalButton(
                            onClick = { model.checkForUpdates(automatic = false) },
                            enabled = BuildConfig.UPDATE_MANIFEST_URL.isNotBlank() &&
                                update !is UpdateState.Checking && update !is UpdateState.Downloading,
                        ) { Text("Check for updates") }
                    }
                }
            }
        }
        item { SectionLabel("Diagnostics") }
        item {
            GroupCard {
                GroupItem(
                    "Share diagnostics log",
                    "Errors, crashes and timings from this phone, ${logSize / 1024} KB. No transcripts, audio or keys.",
                    onClick = {
                        scope.launch {
                            val file = withContext(Dispatchers.IO) { runCatching { Diagnostics.export(context) } }
                            file.onSuccess { shareLog(context, it) }
                                .onFailure { snackbar.showLater("Could not prepare the log.") }
                        }
                    },
                )
                GroupDivider()
                GroupItem("Clear diagnostics log", onClick = {
                    scope.launch {
                        withContext(Dispatchers.IO) { Diagnostics.clear() }
                        logSize = 0L
                        snackbar.showLater("Diagnostics log cleared.")
                    }
                })
            }
        }
        item { Hint("The log stays on this phone unless you share it.") }
        item {
            GroupCard {
                GroupItem("Open-source licences", "whisper.cpp", onClick = { licence = true })
            }
        }
    }
    if (licence) {
        val notice = remember { context.assets.open("licenses/whisper.cpp.txt").bufferedReader().use { it.readText() } }
        AlertDialog(
            onDismissRequest = { licence = false },
            title = { Text("whisper.cpp licence") },
            text = { Text(notice, Modifier.verticalScroll(rememberScrollState())) },
            confirmButton = { TextButton(onClick = { licence = false }) { Text("Close") } },
        )
    }
    if (askInstallPermission) ConfirmDialog(
        title = "Allow Murmur to install updates",
        text = "Android needs your permission to install a downloaded Murmur APK. Return here and tap Install update afterwards.",
        confirm = "Open settings",
        onConfirm = {
            context.startActivity(Intent(Settings.ACTION_MANAGE_UNKNOWN_APP_SOURCES, Uri.parse("package:${context.packageName}")))
        },
        onDismiss = { askInstallPermission = false },
    )
    if (stopVoice) AlertDialog(
        onDismissRequest = { stopVoice = false },
        title = { Text("Turn off voice capture first") },
        text = { Text("Stop the voice bubble before installing an update. Then return here to download it.") },
        confirmButton = { TextButton(onClick = { stopVoice = false }) { Text("OK") } },
    )
}

private fun shareLog(context: Context, file: File) {
    val uri = androidx.core.content.FileProvider.getUriForFile(context, "${context.packageName}.updates", file)
    val send = Intent(Intent.ACTION_SEND).apply {
        type = "text/plain"
        putExtra(Intent.EXTRA_STREAM, uri)
        putExtra(Intent.EXTRA_SUBJECT, "Murmur diagnostics")
        addFlags(Intent.FLAG_GRANT_READ_URI_PERMISSION)
    }
    context.startActivity(Intent.createChooser(send, "Share diagnostics log"))
}
