package dev.local.murmur.ui

import androidx.compose.foundation.horizontalScroll
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.text.KeyboardOptions
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.Check
import androidx.compose.material.icons.filled.Close
import androidx.compose.material.icons.filled.Delete
import androidx.compose.material3.Button
import androidx.compose.material3.CircularProgressIndicator
import androidx.compose.material3.FilterChip
import androidx.compose.material3.FilterChipDefaults
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedTextField
import androidx.compose.material3.RadioButton
import androidx.compose.material3.Switch
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableIntStateOf
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.saveable.rememberSaveable
import androidx.compose.runtime.setValue
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.graphicsLayer
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.res.painterResource
import androidx.compose.ui.text.input.KeyboardType
import androidx.compose.ui.text.input.PasswordVisualTransformation
import androidx.compose.ui.unit.dp
import dev.local.murmur.AppSettings
import dev.local.murmur.DownloadableSpeechModel
import dev.local.murmur.LocalSpeechModels
import dev.local.murmur.R
import kotlinx.coroutines.delay
import java.io.File

private data class SpeechPreset(val label: String, val url: String, val model: String)

private val SPEECH_PRESETS = listOf(
    SpeechPreset("OpenRouter", AppSettings.DEFAULT_ENDPOINT, AppSettings.DEFAULT_MODEL),
    SpeechPreset("OpenAI", "https://api.openai.com/v1/audio/transcriptions", "whisper-1"),
    SpeechPreset("Groq", "https://api.groq.com/openai/v1/audio/transcriptions", "whisper-large-v3-turbo"),
)

@Composable
internal fun RecognitionScreen(model: AppModel, onBack: () -> Unit) {
    val context = LocalContext.current
    val settings = model.settings
    var poll by remember { mutableIntStateOf(0) }
    val revision = model.revision + poll
    val downloadingId = remember(revision) { LocalSpeechModels.downloadingId }
    LaunchedEffect(downloadingId) {
        while (LocalSpeechModels.downloadingId != null) {
            delay(1_000)
            poll++
        }
        poll++
    }
    val online = remember(revision) { !settings.useOnDeviceRecognition && settings.localModelId == null }
    var deleting by remember { mutableStateOf<DownloadableSpeechModel?>(null) }

    ScreenScaffold(title = "Recognition", onBack = onBack) {
        item { Hint("Choose where speech is turned into text. Local options keep audio on this phone, and Murmur never falls back to the cloud on its own.") }
        item { SectionLabel("Source") }
        item {
            GroupCard {
                val phoneAvailable = model.onDeviceAvailable
                GroupItem(
                    "Phone speech service",
                    if (phoneAvailable) "Uses the phone's installed offline recogniser. Dictation only, it cannot transcribe lectures."
                    else "This phone has no on-device speech service.",
                    leading = { RadioButton(selected = settings.useOnDeviceRecognition && phoneAvailable, onClick = null, enabled = phoneAvailable) },
                    onClick = if (phoneAvailable) {
                        {
                            settings.useOnDeviceRecognition = true
                            settings.localModelId = null
                            model.refresh()
                        }
                    } else null,
                )
                LocalSpeechModels.catalog.forEach { speechModel ->
                    GroupDivider()
                    ModelRow(model, speechModel, downloadingId, revision, onDelete = { deleting = speechModel }, onChanged = { poll++ })
                }
                GroupDivider()
                GroupItem(
                    "Online service",
                    if (online) "${model.speechHost} · ${settings.model}" else "OpenRouter, OpenAI, Groq or a compatible endpoint",
                    leading = { RadioButton(selected = online, onClick = null) },
                    onClick = {
                        settings.useOnDeviceRecognition = false
                        settings.localModelId = null
                        model.refresh()
                    },
                )
            }
        }
        LocalSpeechModels.downloadMessage?.takeIf { downloadingId == null }?.let { message ->
            item { Hint(message) }
        }
        item { Hint("Downloading a model uses data and phone storage. Model weights are licensed separately from the app.") }
        if (online) {
            item { SectionLabel("Online service") }
            item { OnlineServiceCard(model) }
        }
        item { SectionLabel("Language") }
        item { LanguageCard(model) }
    }

    deleting?.let { speechModel ->
        ConfirmDialog(
            title = "Delete ${speechModel.label}?",
            text = "The model will need to be downloaded again before it can transcribe audio.",
            confirm = "Delete",
            onConfirm = {
                if (settings.localModelId == speechModel.id) settings.localModelId = null
                LocalSpeechModels.file(context, speechModel).delete()
                model.refresh()
            },
            onDismiss = { deleting = null },
        )
    }
}

@Composable
private fun ModelRow(
    model: AppModel,
    speechModel: DownloadableSpeechModel,
    downloadingId: String?,
    revision: Int,
    onDelete: () -> Unit,
    onChanged: () -> Unit,
) {
    val context = LocalContext.current
    val installed = remember(revision) { LocalSpeechModels.isInstalled(context, speechModel) }
    val supported = remember(revision) { LocalSpeechModels.canRun(context, speechModel) }
    val resumable = remember(revision) { File(LocalSpeechModels.file(context, speechModel).absolutePath + ".part").length() > 0 }
    val selected = model.settings.localModelId == speechModel.id
    val downloading = downloadingId == speechModel.id
    val size = "${speechModel.bytes / 1_000_000} MB"
    GroupItem(
        speechModel.label,
        when {
            downloading -> "Downloading · ${LocalSpeechModels.downloadPercent}%"
            !supported -> "Needs ${speechModel.minimumRamGb} GB RAM"
            installed -> "On this phone · $size"
            resumable -> "Partly downloaded · $size"
            else -> "Download · $size"
        },
        leading = { RadioButton(selected = selected, onClick = null, enabled = installed && supported) },
        trailing = {
            when {
                downloading -> Row {
                    CircularProgressIndicator(progress = { LocalSpeechModels.downloadPercent / 100f }, Modifier.size(24.dp).padding(2.dp))
                    IconButton(onClick = { LocalSpeechModels.cancelDownload(); onChanged() }) {
                        Icon(Icons.Filled.Close, contentDescription = "Cancel download of ${speechModel.label}")
                    }
                }
                installed -> IconButton(onClick = onDelete) { Icon(Icons.Filled.Delete, contentDescription = "Delete ${speechModel.label}") }
                supported -> IconButton(onClick = {
                    LocalSpeechModels.startDownload(context, speechModel)
                    onChanged()
                }, enabled = downloadingId == null) {
                    // the upload arrow flipped vertically reads as a download arrow
                    Icon(painterResource(R.drawable.ic_upload), contentDescription = "Download ${speechModel.label}",
                        modifier = Modifier.graphicsLayer(scaleY = -1f))
                }
            }
        },
        onClick = if (installed && supported) {
            {
                model.settings.localModelId = speechModel.id
                model.settings.useOnDeviceRecognition = false
                model.refresh()
            }
        } else null,
    )
}

@Composable
private fun OnlineServiceCard(model: AppModel) {
    val settings = model.settings
    var url by rememberSaveable { mutableStateOf(settings.endpointUrl) }
    var modelId by rememberSaveable { mutableStateOf(settings.model) }
    var key by rememberSaveable { mutableStateOf("") }
    var feedback by remember { mutableStateOf<String?>(null) }
    val hasKey = remember(model.revision) { settings.hasApiKey }
    PaddedCard {
        Row(Modifier.horizontalScroll(rememberScrollState()), horizontalArrangement = Arrangement.spacedBy(8.dp)) {
            SPEECH_PRESETS.forEach { preset ->
                val selected = url.trim() == preset.url
                FilterChip(
                    selected = selected,
                    onClick = {
                        url = preset.url
                        modelId = settings.speechModelFor(preset.url) ?: preset.model
                    },
                    label = { Text(preset.label) },
                    leadingIcon = if (selected) {
                        { Icon(Icons.Filled.Check, null, Modifier.size(FilterChipDefaults.IconSize)) }
                    } else null,
                )
            }
        }
        OutlinedTextField(url, { url = it }, label = { Text("Transcription URL") }, singleLine = true,
            keyboardOptions = KeyboardOptions(keyboardType = KeyboardType.Uri), modifier = Modifier.fillMaxWidth())
        OutlinedTextField(modelId, { modelId = it }, label = { Text("Model ID") }, singleLine = true, modifier = Modifier.fillMaxWidth())
        OutlinedTextField(
            key, { key = it }, label = { Text("API key") }, singleLine = true,
            placeholder = { Text(if (hasKey) "Saved. Paste to replace" else "Paste your key") },
            visualTransformation = PasswordVisualTransformation(),
            keyboardOptions = KeyboardOptions(keyboardType = KeyboardType.Password),
            supportingText = { Text(if (hasKey) "A key is saved for ${model.speechHost}" else "No key saved") },
            modifier = Modifier.fillMaxWidth(),
        )
        Row(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
            Button(onClick = {
                feedback = try {
                    val previousUrl = settings.endpointUrl
                    settings.saveEndpoint(url, modelId)
                    settings.localModelId = null
                    settings.useOnDeviceRecognition = false
                    if (key.isNotBlank()) settings.saveApiKey(key.trim())
                    val newKey = key.isNotBlank()
                    key = ""
                    url = settings.endpointUrl
                    if (settings.endpointUrl != previousUrl && !newKey && !settings.hasApiKey) "Saved. Add a key for this service if it needs one." else "Saved."
                } catch (error: IllegalArgumentException) {
                    error.message ?: "Check the URL and model."
                } catch (_: Exception) {
                    "Could not save the key. Enter it again and retry."
                }
                model.refresh()
            }) { Text("Save") }
            if (hasKey) TextButton(onClick = {
                settings.clearApiKey()
                feedback = "API key removed."
                model.refresh()
            }) { Text("Remove key") }
        }
        feedback?.let { Text(it, style = MaterialTheme.typography.bodySmall, color = MaterialTheme.colorScheme.primary) }
        Hint("HTTPS is required, except for a server on this phone. The key is encrypted with Android Keystore.", Modifier.padding(0.dp))
    }
}

@Composable
private fun LanguageCard(model: AppModel) {
    val settings = model.settings
    var language by rememberSaveable { mutableStateOf(settings.speechLanguage) }
    var error by remember { mutableStateOf<String?>(null) }
    var saved by remember { mutableStateOf(false) }
    var translate by remember { mutableStateOf(settings.translateToEnglish) }
    PaddedCard {
        OutlinedTextField(
            language, { language = it; saved = false; error = null },
            label = { Text("Recognition language") },
            placeholder = { Text("auto, en, fr…") },
            singleLine = true,
            isError = error != null,
            supportingText = { Text(error ?: if (saved) "Saved." else "Use auto to detect the language.") },
            trailingIcon = {
                TextButton(onClick = {
                    try {
                        settings.speechLanguage = language
                        language = settings.speechLanguage
                        saved = true
                    } catch (failure: IllegalArgumentException) {
                        error = failure.message
                    }
                }) { Text("Save") }
            },
            modifier = Modifier.fillMaxWidth(),
        )
        Row(verticalAlignment = androidx.compose.ui.Alignment.CenterVertically) {
            androidx.compose.foundation.layout.Column(Modifier.weight(1f)) {
                Text("Translate speech to English", style = MaterialTheme.typography.bodyLarge)
                Hint("Downloaded Whisper models only.", Modifier.padding(0.dp))
            }
            Switch(checked = translate, onCheckedChange = { translate = it; settings.translateToEnglish = it })
        }
    }
}
