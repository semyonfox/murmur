package dev.local.murmur.ui

import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.heightIn
import androidx.compose.foundation.layout.navigationBarsPadding
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.items
import androidx.compose.foundation.text.KeyboardOptions
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.ArrowDropDown
import androidx.compose.material.icons.filled.Search
import androidx.compose.material3.AlertDialog
import androidx.compose.material3.Button
import androidx.compose.material3.DropdownMenu
import androidx.compose.material3.DropdownMenuItem
import androidx.compose.material3.ExperimentalMaterial3Api
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.ListItem
import androidx.compose.material3.ListItemDefaults
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.ModalBottomSheet
import androidx.compose.material3.OutlinedButton
import androidx.compose.material3.OutlinedTextField
import androidx.compose.material3.RadioButton
import androidx.compose.material3.Slider
import androidx.compose.material3.Switch
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.material3.rememberModalBottomSheetState
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableFloatStateOf
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberCoroutineScope
import androidx.compose.runtime.saveable.rememberSaveable
import androidx.compose.runtime.setValue
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.text.input.KeyboardType
import androidx.compose.ui.text.input.PasswordVisualTransformation
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import dev.local.murmur.AppSettings
import dev.local.murmur.CleanupModelCatalog
import dev.local.murmur.CleanupPrompt
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext
import java.util.Locale

private data class CleanupService(val label: String, val url: String)

private val CLEANUP_SERVICES = listOf(
    CleanupService("OpenRouter", AppSettings.DEFAULT_CLEANUP_URL),
    CleanupService("OpenAI", "https://api.openai.com/v1/chat/completions"),
    CleanupService("Z.AI", "https://api.z.ai/api/paas/v4/chat/completions"),
    CleanupService("SiliconFlow", "https://api.siliconflow.com/v1/chat/completions"),
    CleanupService("Anthropic", "https://api.anthropic.com/v1/messages"),
    CleanupService("Groq", "https://api.groq.com/openai/v1/chat/completions"),
    CleanupService("Cerebras", "https://api.cerebras.ai/v1/chat/completions"),
    CleanupService("AWS Bedrock (Mantle)", "https://bedrock-mantle.us-east-1.api.aws/v1/chat/completions"),
    CleanupService("Ollama on this phone", "http://127.0.0.1:11434/v1/chat/completions"),
)

private val FORMALITY = listOf("Very casual", "Casual", "Natural", "Professional", "Formal")
private const val MODEL_PICKER_ROWS = 60
private val SUGGESTED_MODEL_PROVIDERS = listOf("openai", "anthropic", "google", "qwen", "mistralai", "meta-llama", "deepseek", "x-ai")
// snapshots, previews and non-text variants stay reachable through search
private val MODEL_NOISE = listOf(
    ":free", ":batch", "preview", "exp", "vision", "image", "audio", "embed", "guard", "tts", "search", "coder", "math",
)

@Composable
internal fun CleanupScreen(model: AppModel, onBack: () -> Unit) {
    val settings = model.settings
    val scope = rememberCoroutineScope()
    val revision = model.revision
    var fillers by remember { mutableStateOf(settings.removeFillerWords) }
    var url by rememberSaveable { mutableStateOf(settings.cleanupUrl) }
    var modelId by rememberSaveable { mutableStateOf(settings.cleanupModel) }
    var key by rememberSaveable { mutableStateOf("") }
    var formality by remember { mutableFloatStateOf(settings.cleanupFormality.toFloat()) }
    var dirty by remember { mutableStateOf(false) }
    var urlError by remember { mutableStateOf<String?>(null) }
    var modelError by remember { mutableStateOf<String?>(null) }
    var keyError by remember { mutableStateOf<String?>(null) }
    var message by remember { mutableStateOf<Pair<String, Boolean>?>(null) }
    var serviceMenu by remember { mutableStateOf(false) }
    var catalogue by remember { mutableStateOf<List<String>?>(null) }
    var loadingModels by remember { mutableStateOf(false) }
    var choosingPrompt by remember { mutableStateOf(false) }
    var editingPrompt by remember { mutableStateOf<CleanupPrompt?>(null) }
    var creatingPrompt by remember { mutableStateOf(false) }
    var deletingPrompt by remember { mutableStateOf<CleanupPrompt?>(null) }
    val enabled = remember(revision) { settings.cleanupEnabled }
    val hasKey = remember(revision) { settings.hasCleanupKey }
    val selectedPrompt = remember(revision) { settings.cleanupPrompts.selected }

    fun save(enableAfterSave: Boolean): Boolean {
        urlError = null; modelError = null; keyError = null
        try {
            val parsed = try { AppSettings.parseCleanupUrl(url) } catch (error: IllegalArgumentException) {
                urlError = error.message
                throw error
            }
            val trimmedModel = modelId.trim()
            if (trimmedModel.isBlank()) {
                modelError = "Enter a model ID"
                throw IllegalArgumentException("Add a cleanup model ID to turn this on.")
            }
            val endpointChanged = parsed.toASCIIString() != settings.cleanupUrl
            val trimmedKey = key.trim()
            val openRouter = parsed.host.equals("openrouter.ai", ignoreCase = true)
            val keyAvailable = trimmedKey.isNotEmpty() || settings.hasCleanupKeyFor(parsed.toASCIIString())
            if (enableAfterSave && openRouter && !keyAvailable) {
                keyError = "API key required"
                throw IllegalArgumentException("Add a cleanup API key to use OpenRouter.")
            }
            if (endpointChanged) settings.cleanupEnabled = false
            settings.saveCleanupEndpoint(parsed.toASCIIString(), trimmedModel, formality.toInt())
            if (trimmedKey.isNotBlank()) settings.saveCleanupKey(trimmedKey)
            key = ""
            settings.cleanupEnabled = enableAfterSave || (settings.cleanupEnabled && !endpointChanged && (!openRouter || keyAvailable))
            settings.lastCleanupFailure = null
            dirty = false
            message = (if (enableAfterSave) "Saved and turned on." else if (endpointChanged) "Saved. Cleanup is off because the service changed."
                else "Cleanup settings saved.") to false
            model.refresh()
            return true
        } catch (error: IllegalArgumentException) {
            message = (error.message ?: "Check the cleanup settings.") to true
        } catch (_: Exception) {
            message = "Could not save the cleanup key. Enter it again and retry." to true
        }
        return false
    }

    ScreenScaffold(title = "Transcript cleanup", onBack = onBack) {
        item { Hint("An online cleanup service receives transcript text and your words list, never audio. Raw text stays in History for recovery.") }
        item { SectionLabel("On this phone") }
        item {
            GroupCard {
                GroupItem("Remove filler words", "Runs before optional online cleanup. The original stays in History.", trailing = {
                    Switch(checked = fillers, onCheckedChange = { fillers = it; settings.removeFillerWords = it })
                })
            }
        }
        item { SectionLabel("Online cleanup") }
        item {
            PaddedCard {
                Row(verticalAlignment = androidx.compose.ui.Alignment.CenterVertically) {
                    Column(Modifier.weight(1f)) {
                        Text("Use transcript cleanup", style = MaterialTheme.typography.titleMedium)
                        val failure = remember(revision) { settings.lastCleanupFailure }
                        Text(
                            when {
                                dirty -> "Unsaved changes. Turn on to save and enable, or save below."
                                enabled && failure != null -> "On · last cleanup failed: $failure"
                                else -> model.cleanupSummary()
                            },
                            style = MaterialTheme.typography.bodySmall,
                            color = if (enabled && settings.lastCleanupFailure != null) MaterialTheme.colorScheme.error
                                else MaterialTheme.colorScheme.onSurfaceVariant,
                        )
                    }
                    Switch(checked = enabled, onCheckedChange = { on ->
                        if (on) save(enableAfterSave = true)
                        else {
                            settings.cleanupEnabled = false
                            model.refresh()
                        }
                    })
                }
                Box {
                    OutlinedButton(onClick = { serviceMenu = true }) {
                        Text(CLEANUP_SERVICES.firstOrNull { it.url == url.trim() }?.label ?: "Custom service")
                        Icon(Icons.Filled.ArrowDropDown, null)
                    }
                    DropdownMenu(expanded = serviceMenu, onDismissRequest = { serviceMenu = false }) {
                        CLEANUP_SERVICES.forEach { service ->
                            DropdownMenuItem(text = { Text(service.label) }, onClick = {
                                serviceMenu = false
                                url = service.url
                                modelId = settings.cleanupModelFor(service.url).orEmpty()
                                dirty = true
                            })
                        }
                    }
                }
                OutlinedTextField(url, { url = it; dirty = true; urlError = null }, label = { Text("Cleanup API URL") },
                    singleLine = true, isError = urlError != null, supportingText = urlError?.let { { Text(it) } },
                    keyboardOptions = KeyboardOptions(keyboardType = KeyboardType.Uri), modifier = Modifier.fillMaxWidth())
                OutlinedTextField(
                    modelId, { modelId = it; dirty = true; modelError = null }, label = { Text("Model ID") },
                    singleLine = true, isError = modelError != null,
                    supportingText = { Text(modelError ?: "Required. Browse to pick from your provider.") },
                    trailingIcon = {
                        IconButton(enabled = !loadingModels, onClick = {
                            val parsed = runCatching { AppSettings.parseCleanupUrl(url) }.getOrElse {
                                urlError = it.message
                                return@IconButton
                            }
                            loadingModels = true
                            scope.launch {
                                val result = withContext(Dispatchers.IO) {
                                    runCatching { CleanupModelCatalog.fetch(parsed, settings.cleanupKeyFor(parsed.toASCIIString())) }
                                }
                                loadingModels = false
                                result.onSuccess { models ->
                                    if (models.isEmpty()) message = "No models returned. You can enter a model ID manually." to false
                                    else catalogue = models
                                }.onFailure { message = (it.message ?: "Could not load models. Enter an ID manually.") to true }
                            }
                        }) { Icon(Icons.Filled.Search, contentDescription = "Browse provider models") }
                    },
                    modifier = Modifier.fillMaxWidth(),
                )
                OutlinedTextField(
                    key, { key = it; dirty = true; keyError = null }, label = { Text("Cleanup API key") }, singleLine = true,
                    placeholder = { Text(if (hasKey) "Saved. Paste to replace" else "Required for OpenRouter") },
                    isError = keyError != null,
                    supportingText = { Text(keyError ?: if (hasKey) "Saved separately from the speech key" else "No cleanup key saved") },
                    visualTransformation = PasswordVisualTransformation(),
                    keyboardOptions = KeyboardOptions(keyboardType = KeyboardType.Password),
                    modifier = Modifier.fillMaxWidth(),
                )
                Text("Formality · ${FORMALITY[formality.toInt()]}", style = MaterialTheme.typography.bodyLarge)
                Slider(value = formality, onValueChange = { formality = it; dirty = true }, valueRange = 0f..4f, steps = 3)
                Row(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                    Button(onClick = { save(enableAfterSave = false) }) { Text("Save") }
                    if (hasKey) TextButton(onClick = {
                        settings.clearCleanupKey()
                        message = if (settings.cleanupEnabled && runCatching { java.net.URI(settings.cleanupUrl).host }.getOrNull()
                                .equals("openrouter.ai", ignoreCase = true)) {
                            settings.cleanupEnabled = false
                            "Key removed. Cleanup is off until you add another key." to false
                        } else "Cleanup key removed." to false
                        model.refresh()
                    }) { Text("Remove key") }
                }
                message?.let { (text, error) ->
                    Text(text, style = MaterialTheme.typography.bodySmall,
                        color = if (error) MaterialTheme.colorScheme.error else MaterialTheme.colorScheme.primary)
                }
                Hint("An online cleanup service may charge separately. A local server must run on this phone, localhost is not your computer.",
                    Modifier.padding(0.dp))
            }
        }
        item { SectionLabel("Instructions") }
        item {
            GroupCard {
                GroupItem("Cleanup instructions", selectedPrompt?.let { "Using ${it.name}" } ?: "Using default instructions",
                    onClick = { choosingPrompt = true })
                GroupDivider()
                Row(Modifier.padding(horizontal = 8.dp, vertical = 4.dp)) {
                    TextButton(onClick = { creatingPrompt = true }) { Text("Create") }
                    TextButton(onClick = { editingPrompt = selectedPrompt }, enabled = selectedPrompt != null) { Text("Edit") }
                    TextButton(onClick = { deletingPrompt = selectedPrompt }, enabled = selectedPrompt != null) { Text("Delete") }
                }
            }
        }
    }

    catalogue?.let { models ->
        ModelPicker(models, current = modelId.trim(), onDismiss = { catalogue = null }) {
            modelId = it
            dirty = true
            catalogue = null
        }
    }
    if (choosingPrompt) {
        val prompts = remember { settings.cleanupPrompts.all }
        AlertDialog(
            onDismissRequest = { choosingPrompt = false },
            title = { Text("Cleanup instructions") },
            text = {
                Column {
                    (listOf<CleanupPrompt?>(null) + prompts).forEach { prompt ->
                        ListItem(
                            headlineContent = { Text(prompt?.name ?: "Default instructions") },
                            leadingContent = { RadioButton(selected = selectedPrompt?.id == prompt?.id, onClick = null) },
                            colors = ListItemDefaults.colors(containerColor = Color.Transparent),
                            modifier = Modifier.clickableRow {
                                settings.cleanupPrompts.select(prompt?.id)
                                choosingPrompt = false
                                model.refresh()
                            },
                        )
                    }
                }
            },
            confirmButton = { TextButton(onClick = { choosingPrompt = false }) { Text("Close") } },
        )
    }
    if (creatingPrompt || editingPrompt != null) {
        PromptEditor(editingPrompt, onDismiss = { creatingPrompt = false; editingPrompt = null }) { name, instructions ->
            runCatching { settings.cleanupPrompts.save(editingPrompt?.id, name, instructions) }
                .onSuccess { creatingPrompt = false; editingPrompt = null; model.refresh() }
                .onFailure { message = (it.message ?: "Could not save instructions.") to true }
        }
    }
    deletingPrompt?.let { prompt ->
        ConfirmDialog("Delete ${prompt.name}?", "Cleanup goes back to the default instructions.", "Delete",
            onConfirm = { settings.cleanupPrompts.delete(prompt.id); model.refresh() },
            onDismiss = { deletingPrompt = null })
    }
}

@Composable
private fun PromptEditor(existing: CleanupPrompt?, onDismiss: () -> Unit, onSave: (String, String) -> Unit) {
    var name by rememberSaveable { mutableStateOf(existing?.name.orEmpty()) }
    var instructions by rememberSaveable { mutableStateOf(existing?.instructions.orEmpty()) }
    AlertDialog(
        onDismissRequest = onDismiss,
        title = { Text(if (existing == null) "Create instructions" else "Edit instructions") },
        text = {
            Column(verticalArrangement = Arrangement.spacedBy(8.dp)) {
                OutlinedTextField(name, { name = it }, label = { Text("Name") }, singleLine = true, modifier = Modifier.fillMaxWidth())
                OutlinedTextField(instructions, { instructions = it }, label = { Text("How should Murmur clean text?") },
                    minLines = 5, modifier = Modifier.fillMaxWidth())
            }
        },
        confirmButton = { TextButton(onClick = { onSave(name, instructions) }) { Text("Save") } },
        dismissButton = { TextButton(onClick = onDismiss) { Text("Cancel") } },
    )
}

// big catalogues like OpenRouter list hundreds of niche models, so until the
// user searches only the current choice and mainstream providers are shown
@OptIn(ExperimentalMaterial3Api::class)
@Composable
private fun ModelPicker(models: List<String>, current: String, onDismiss: () -> Unit, onPick: (String) -> Unit) {
    var query by rememberSaveable { mutableStateOf("") }
    val suggested = remember(models, current) {
        models.filter { model ->
            model == current || (SUGGESTED_MODEL_PROVIDERS.any { model.startsWith("$it/") } &&
                MODEL_NOISE.none { model.contains(it, ignoreCase = true) } && !Regex("""\d{4}""").containsMatchIn(model))
        }.sortedBy { if (it == current) 0 else 1 }.ifEmpty { models.take(MODEL_PICKER_ROWS) }
    }
    val terms = query.trim().lowercase(Locale.ROOT).split(' ').filter { it.isNotEmpty() }
    val matches = if (terms.isEmpty()) suggested else models.filter { model -> terms.all { model.lowercase(Locale.ROOT).contains(it) } }
    ModalBottomSheet(onDismissRequest = onDismiss, sheetState = rememberModalBottomSheetState(skipPartiallyExpanded = true)) {
        Column(Modifier.padding(horizontal = 16.dp).navigationBarsPadding()) {
            Text("Choose a cleanup model", style = MaterialTheme.typography.titleLarge, modifier = Modifier.padding(bottom = 12.dp))
            OutlinedTextField(query, { query = it }, singleLine = true, leadingIcon = { Icon(Icons.Filled.Search, null) },
                placeholder = { Text("Search ${models.size} models") }, modifier = Modifier.fillMaxWidth())
            Hint(
                when {
                    terms.isEmpty() -> "Popular providers. Search to see all ${models.size}."
                    matches.isEmpty() -> "No matches. You can still type a model ID."
                    matches.size > MODEL_PICKER_ROWS -> "Showing $MODEL_PICKER_ROWS of ${matches.size}. Keep typing to narrow it down."
                    else -> "${matches.size} ${if (matches.size == 1) "match" else "matches"}"
                },
                Modifier.padding(vertical = 8.dp),
            )
            LazyColumn(Modifier.heightIn(max = 480.dp)) {
                items(matches.take(MODEL_PICKER_ROWS), key = { it }) { option ->
                    val provider = option.substringBefore('/', "")
                    ListItem(
                        headlineContent = { Text(option.substringAfter('/'), maxLines = 1, overflow = TextOverflow.Ellipsis) },
                        supportingContent = provider.takeIf { it.isNotEmpty() }?.let { { Text(it) } },
                        leadingContent = { RadioButton(selected = option == current, onClick = null) },
                        colors = ListItemDefaults.colors(containerColor = Color.Transparent),
                        modifier = Modifier.clickableRow { onPick(option) },
                    )
                }
            }
        }
    }
}
