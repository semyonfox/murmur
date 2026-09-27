package dev.local.murmur

import android.content.Context
import android.security.keystore.KeyGenParameterSpec
import android.security.keystore.KeyProperties
import android.util.Base64
import org.json.JSONArray
import org.json.JSONObject
import java.net.URI
import java.nio.charset.StandardCharsets
import java.security.KeyStore
import java.security.MessageDigest
import javax.crypto.Cipher
import javax.crypto.KeyGenerator
import javax.crypto.SecretKey
import javax.crypto.spec.GCMParameterSpec

internal class TranscriptionEndpoint(
    val uri: URI, val model: String, val apiKey: String?, val localModel: java.io.File? = null,
    val language: String = "auto", val translate: Boolean = false,
)
internal class CleanupEndpoint(
    val uri: URI, val model: String, val apiKey: String?, val formality: Int,
    val dictionary: List<String>, val customInstructions: String?,
)

internal class AppSettings(context: Context) {
    private val appContext = context.applicationContext
    val cleanupPrompts = CleanupPrompts(context)
    private val preferences = context.getSharedPreferences("murmur", Context.MODE_PRIVATE)

    init {
        val speech = preferences.getString(API_KEY, null)
        val cleanup = preferences.getString(CLEANUP_KEY, null)
        val speechModel = preferences.getString(MODEL, null)
        val textModel = preferences.getString(CLEANUP_MODEL, null)
        if (speech != null || cleanup != null ||
            (speechModel != null && !preferences.contains(keyName(MODEL, endpointUrl))) ||
            (textModel != null && !preferences.contains(keyName(CLEANUP_MODEL, cleanupUrl)))) {
            preferences.edit().apply {
                if (speechModel != null && !preferences.contains(keyName(MODEL, endpointUrl)))
                    putString(keyName(MODEL, endpointUrl), speechModel)
                if (textModel != null && !preferences.contains(keyName(CLEANUP_MODEL, cleanupUrl)))
                    putString(keyName(CLEANUP_MODEL, cleanupUrl), textModel)
                if (speech != null) {
                    val destination = keyName(API_KEY, endpointUrl)
                    if (!preferences.contains(destination)) putString(destination, speech)
                    remove(API_KEY)
                }
                if (cleanup != null) {
                    val destination = keyName(CLEANUP_KEY, cleanupUrl)
                    if (!preferences.contains(destination)) putString(destination, cleanup)
                    remove(CLEANUP_KEY)
                }
            }.commit()
        }
    }

    val endpointUrl: String get() = preferences.getString(ENDPOINT_URL, DEFAULT_ENDPOINT) ?: DEFAULT_ENDPOINT
    val model: String get() = preferences.getString(MODEL, DEFAULT_MODEL) ?: DEFAULT_MODEL
    fun speechModelFor(url: String): String? = preferences.getString(keyName(MODEL, url), null)
    fun cleanupModelFor(url: String): String? = preferences.getString(keyName(CLEANUP_MODEL, url), null)
    val hasApiKey: Boolean get() = preferences.contains(keyName(API_KEY, endpointUrl)) ||
        (endpointUrl == DEFAULT_ENDPOINT && preferences.contains(API_KEY))
    var noiseSuppressionEnabled: Boolean
        get() = preferences.getBoolean(NOISE_SUPPRESSION, false)
        set(value) { preferences.edit().putBoolean(NOISE_SUPPRESSION, value).apply() }
    var bubbleSizeDp: Int
        get() = preferences.getInt(BUBBLE_SIZE, 44).coerceIn(40, 56)
        set(value) { preferences.edit().putInt(BUBBLE_SIZE, value.coerceIn(40, 56)).apply() }
    var bubbleOpacityPercent: Int
        get() = preferences.getInt(BUBBLE_OPACITY, 90).coerceIn(40, 100)
        set(value) { preferences.edit().putInt(BUBBLE_OPACITY, value.coerceIn(40, 100)).apply() }
    var bubbleXFraction: Float
        get() = preferences.getFloat(BUBBLE_X, 1f).coerceIn(0f, 1f)
        set(value) { preferences.edit().putFloat(BUBBLE_X, value.coerceIn(0f, 1f)).apply() }
    var bubbleYFraction: Float
        get() = preferences.getFloat(BUBBLE_Y, 1f).coerceIn(0f, 1f)
        set(value) { preferences.edit().putFloat(BUBBLE_Y, value.coerceIn(0f, 1f)).apply() }
    var useOnDeviceRecognition: Boolean
        get() = preferences.getBoolean(ON_DEVICE_RECOGNITION, false)
        set(value) { preferences.edit().putBoolean(ON_DEVICE_RECOGNITION, value).apply() }
    var localModelId: String?
        get() = preferences.getString(LOCAL_MODEL_ID, null)
        set(value) {
            require(value == null || LocalSpeechModels.catalog.any { it.id == value })
            preferences.edit().apply {
                if (value == null) remove(LOCAL_MODEL_ID) else putString(LOCAL_MODEL_ID, value)
                if (value != null) putBoolean(ON_DEVICE_RECOGNITION, false)
            }.apply()
        }
    var speechLanguage: String
        get() = preferences.getString(SPEECH_LANGUAGE, "auto") ?: "auto"
        set(value) {
            val normalized = value.trim().lowercase()
            require(normalized == "auto" || normalized.matches(Regex("[a-z]{2,3}(-[a-z]{2,4})?"))) { "Use auto or a language code such as en or fr." }
            preferences.edit().putString(SPEECH_LANGUAGE, normalized).apply()
        }
    var translateToEnglish: Boolean
        get() = preferences.getBoolean(TRANSLATE_TO_ENGLISH, false)
        set(value) { preferences.edit().putBoolean(TRANSLATE_TO_ENGLISH, value).apply() }
    var removeFillerWords: Boolean
        get() = preferences.getBoolean(REMOVE_FILLER_WORDS, true)
        set(value) { preferences.edit().putBoolean(REMOVE_FILLER_WORDS, value).apply() }
    fun prepareTranscript(raw: String): String = TranscriptText.prepare(raw,
        if (translateToEnglish && localModelId != null) "en" else speechLanguage, removeFillerWords)
    var historyRetentionDays: Int
        get() = preferences.getInt(HISTORY_RETENTION_DAYS, 0)
        set(value) {
            require(value == 0 || value == 7 || value == 30 || value == 90)
            preferences.edit().putInt(HISTORY_RETENTION_DAYS, value).apply()
        }
    var cleanupEnabled: Boolean
        get() = preferences.getBoolean(CLEANUP_ENABLED, false)
        set(value) { preferences.edit().putBoolean(CLEANUP_ENABLED, value).apply() }
    var lastCleanupFailure: String?
        get() = preferences.getString(LAST_CLEANUP_FAILURE, null)
        set(value) {
            preferences.edit().apply {
                if (value == null) remove(LAST_CLEANUP_FAILURE)
                else putString(LAST_CLEANUP_FAILURE, value)
            }.apply()
        }
    val cleanupUrl: String get() = preferences.getString(CLEANUP_URL, DEFAULT_CLEANUP_URL) ?: DEFAULT_CLEANUP_URL
    val cleanupModel: String get() = preferences.getString(CLEANUP_MODEL, DEFAULT_CLEANUP_MODEL) ?: DEFAULT_CLEANUP_MODEL
    val cleanupFormality: Int get() = preferences.getInt(CLEANUP_FORMALITY, 2).coerceIn(0, 4)
    val hasCleanupKey: Boolean get() = preferences.contains(keyName(CLEANUP_KEY, cleanupUrl)) ||
        (cleanupUrl == DEFAULT_CLEANUP_URL && preferences.contains(CLEANUP_KEY))
    fun hasCleanupKeyFor(url: String): Boolean = preferences.contains(keyName(CLEANUP_KEY, url)) ||
        (url == DEFAULT_CLEANUP_URL && preferences.contains(CLEANUP_KEY))
    fun cleanupKeyFor(url: String): String? = readKey(keyName(CLEANUP_KEY, url))
        ?: if (url == DEFAULT_CLEANUP_URL) readKey(CLEANUP_KEY) else null
    val dictionary: List<String> get() = runCatching {
        val values = JSONArray(preferences.getString(DICTIONARY, "[]"))
        (0 until values.length()).mapNotNull { values.optString(it).takeIf(String::isNotBlank) }
    }.getOrDefault(emptyList())
    val latestTranscriptTime: Long? get() = preferences.getLong(LATEST_TRANSCRIPT_TIME, 0L).takeIf { it > 0L }
    val latestHistoryId: Long? get() = preferences.getLong(LATEST_HISTORY_ID, 0L).takeIf { it > 0L }
    val latestRawTranscript: String? get() = preferences.getString(LATEST_RAW_TRANSCRIPT, null)
    var latestTranscript: String?
        get() = preferences.getString(LATEST_TRANSCRIPT, null)
        set(value) {
            preferences.edit().apply {
                if (value.isNullOrBlank()) {
                    remove(LATEST_TRANSCRIPT)
                    remove(LATEST_TRANSCRIPT_TIME)
                    remove(LATEST_RAW_TRANSCRIPT)
                    remove(LATEST_HISTORY_ID)
                } else {
                    putString(LATEST_TRANSCRIPT, value)
                    putLong(LATEST_TRANSCRIPT_TIME, System.currentTimeMillis())
                }
            }.apply()
        }

    fun saveTranscript(raw: String, cleaned: String?, historyId: Long?) {
        preferences.edit()
            .putString(LATEST_RAW_TRANSCRIPT, raw)
            .putString(LATEST_TRANSCRIPT, cleaned ?: raw)
            .putLong(LATEST_TRANSCRIPT_TIME, System.currentTimeMillis())
            .apply { if (historyId != null) putLong(LATEST_HISTORY_ID, historyId) else remove(LATEST_HISTORY_ID) }
            .apply()
    }

    fun saveEndpoint(url: String, modelId: String) {
        val uri = parseEndpointUrl(url)
        val trimmedModel = modelId.trim()
        require(trimmedModel.isNotEmpty() && trimmedModel.length <= 200 && trimmedModel.none { Character.isISOControl(it) }) {
            "Enter a valid model ID."
        }
        val normalizedUrl = uri.toASCIIString()
        preferences.edit()
            .putString(ENDPOINT_URL, normalizedUrl)
            .putString(MODEL, trimmedModel)
            .putString(keyName(MODEL, normalizedUrl), trimmedModel)
            .apply()
    }

    fun endpointOrNull(): TranscriptionEndpoint? {
        if (endpointUrl.isBlank() || model.isBlank()) return null
        return TranscriptionEndpoint(parseEndpointUrl(endpointUrl), model, readApiKey(), language = speechLanguage)
    }

    fun activeEndpointOrNull(): TranscriptionEndpoint? {
        val id = localModelId ?: return endpointOrNull()
        val selected = LocalSpeechModels.catalog.firstOrNull { it.id == id } ?: return null
        require(LocalSpeechModels.canRun(appContext, selected)) { "This phone does not have enough memory for the selected model." }
        require(LocalSpeechModels.isInstalled(appContext, selected)) { "Download the selected speech model first." }
        return TranscriptionEndpoint(URI("murmur-local://speech/audio/transcriptions"), id, null,
            LocalSpeechModels.file(appContext, selected), speechLanguage, translateToEnglish)
    }

    fun cleanupEndpointOrNull(): CleanupEndpoint? {
        if (!cleanupEnabled) return null
        require(cleanupModel.isNotBlank()) { "Choose a cleanup model in Murmur." }
        return CleanupEndpoint(parseCleanupUrl(cleanupUrl), cleanupModel, readCleanupKey(), cleanupFormality,
            dictionary, cleanupPrompts.selected?.instructions)
    }

    fun saveCleanupEndpoint(url: String, modelId: String, formality: Int) {
        val uri = parseCleanupUrl(url)
        val model = modelId.trim()
        require(model.isNotEmpty() && model.length <= 200 && model.none(Character::isISOControl)) { "Enter a valid cleanup model ID." }
        require(formality in 0..4) { "Choose a formality level." }
        val normalizedUrl = uri.toASCIIString()
        preferences.edit()
            .putString(CLEANUP_URL, normalizedUrl)
            .putString(CLEANUP_MODEL, model)
            .putString(keyName(CLEANUP_MODEL, normalizedUrl), model)
            .putInt(CLEANUP_FORMALITY, formality)
            .apply()
    }

    fun saveCleanupKey(key: String) = saveKey(keyName(CLEANUP_KEY, cleanupUrl), key)
    fun clearCleanupKey() {
        preferences.edit().remove(keyName(CLEANUP_KEY, cleanupUrl))
            .apply { if (cleanupUrl == DEFAULT_CLEANUP_URL) remove(CLEANUP_KEY) }.apply()
    }

    fun addDictionaryTerm(value: String) {
        val term = value.trim()
        require(term.length in 1..100 && term.none(Character::isISOControl)) { "Enter a term under 100 characters." }
        val terms = dictionary
        require(terms.size < 500 || terms.any { it.equals(term, ignoreCase = true) }) { "Dictionary is full." }
        if (terms.any { it.equals(term, ignoreCase = true) }) return
        require(terms.sumOf(String::length) + term.length <= 4_000) { "Dictionary is full." }
        preferences.edit().putString(DICTIONARY, JSONArray(terms + term).toString()).apply()
    }

    fun removeDictionaryTerm(value: String) {
        preferences.edit().putString(DICTIONARY, JSONArray(dictionary.filterNot { it == value }).toString()).apply()
    }

    fun saveApiKey(key: String) {
        require(preferences.contains(ENDPOINT_URL)) { "Save the endpoint before adding its key." }
        saveKey(keyName(API_KEY, endpointUrl), key)
    }

    private fun saveKey(name: String, key: String) {
        require(key.isNotBlank() && key.length <= 8192 && key.none { Character.isISOControl(it) }) {
            "Enter a valid API key."
        }
        val cipher = Cipher.getInstance("AES/GCM/NoPadding")
        cipher.init(Cipher.ENCRYPT_MODE, getOrCreateKey())
        val encrypted = cipher.doFinal(key.toByteArray(StandardCharsets.UTF_8))
        val payload = cipher.iv + encrypted
        preferences.edit().putString(name, Base64.encodeToString(payload, Base64.NO_WRAP)).apply()
    }

    fun clearApiKey() {
        preferences.edit().remove(keyName(API_KEY, endpointUrl))
            .apply { if (endpointUrl == DEFAULT_ENDPOINT) remove(API_KEY) }.apply()
    }

    fun migrationSnapshot(): JSONObject = JSONObject().apply {
        val speechKey = runCatching { readApiKey() }
        val textKey = runCatching { readCleanupKey() }
        put("endpoint_url", endpointUrl)
        put("model", model)
        put("api_key", speechKey.getOrNull())
        put("speech_key_unreadable", speechKey.isFailure)
        put("noise_suppression", noiseSuppressionEnabled)
        put("bubble_size_dp", bubbleSizeDp)
        put("bubble_opacity_percent", bubbleOpacityPercent)
        put("bubble_x_fraction", bubbleXFraction.toDouble())
        put("bubble_y_fraction", bubbleYFraction.toDouble())
        put("on_device_recognition", useOnDeviceRecognition)
        put("local_model_id", localModelId)
        put("speech_language", speechLanguage)
        put("translate_to_english", translateToEnglish)
        put("remove_filler_words", removeFillerWords)
        put("history_retention_days", historyRetentionDays)
        put("cleanup_enabled", cleanupEnabled)
        put("cleanup_url", cleanupUrl)
        put("cleanup_model", cleanupModel)
        put("cleanup_formality", cleanupFormality)
        put("cleanup_prompts", JSONArray(cleanupPrompts.all.map { prompt -> JSONObject()
            .put("id", prompt.id).put("name", prompt.name).put("instructions", prompt.instructions) }))
        put("selected_cleanup_prompt", cleanupPrompts.selectedId)
        put("cleanup_key", textKey.getOrNull())
        put("cleanup_key_unreadable", textKey.isFailure)
        put("last_cleanup_failure", lastCleanupFailure)
        put("dictionary", JSONArray(dictionary))
        put("latest_transcript", latestTranscript)
        put("latest_raw_transcript", latestRawTranscript)
        put("latest_transcript_time", latestTranscriptTime)
        put("latest_history_id", latestHistoryId)
    }

    fun restoreMigration(snapshot: JSONObject, mappedHistoryId: Long?) {
        val endpoint = parseEndpointUrl(snapshot.getString("endpoint_url")).toASCIIString()
        val modelId = snapshot.getString("model").trim()
        require(modelId.isNotEmpty() && modelId.length <= 200 && modelId.none(Character::isISOControl))
        val cleanupEndpoint = parseCleanupUrl(snapshot.getString("cleanup_url")).toASCIIString()
        val cleanupModelId = snapshot.getString("cleanup_model").trim()
        require(cleanupModelId.length <= 200 && cleanupModelId.none(Character::isISOControl))
        val formality = snapshot.getInt("cleanup_formality")
        require(formality in 0..4)
        val retention = snapshot.getInt("history_retention_days")
        require(retention == 0 || retention == 7 || retention == 30 || retention == 90)
        val terms = snapshot.getJSONArray("dictionary").let { array ->
            (0 until array.length()).map { array.getString(it) }
        }
        require(terms.size <= 500 && terms.sumOf(String::length) <= 4_000)
        val speechKey = snapshot.optString("api_key").takeIf { !snapshot.isNull("api_key") && it.isNotBlank() }
        val textKey = snapshot.optString("cleanup_key").takeIf { !snapshot.isNull("cleanup_key") && it.isNotBlank() }
        val finalText = snapshot.optString("latest_transcript").takeIf { !snapshot.isNull("latest_transcript") }
        val rawText = snapshot.optString("latest_raw_transcript").takeIf { !snapshot.isNull("latest_raw_transcript") }
        val cleanupFailure = snapshot.optString("last_cleanup_failure").takeIf { !snapshot.isNull("last_cleanup_failure") }
        val transcriptTime = snapshot.optLong("latest_transcript_time", 0L).coerceAtLeast(0L)
        val prompts = snapshot.optJSONArray("cleanup_prompts") ?: JSONArray()
        require(prompts.length() <= 20)
        for (index in 0 until prompts.length()) {
            val prompt = prompts.getJSONObject(index)
            require(prompt.getString("name").length in 1..80 && prompt.getString("instructions").length in 1..4000)
        }

        saveEndpoint(endpoint, modelId)
        clearApiKey()
        if (speechKey != null) saveApiKey(speechKey)
        if (cleanupModelId.isNotEmpty()) saveCleanupEndpoint(cleanupEndpoint, cleanupModelId, formality)
        else preferences.edit().putString(CLEANUP_URL, cleanupEndpoint).putString(CLEANUP_MODEL, "")
            .putInt(CLEANUP_FORMALITY, formality).apply()
        clearCleanupKey()
        if (textKey != null) saveCleanupKey(textKey)
        preferences.edit()
            .putBoolean(NOISE_SUPPRESSION, snapshot.getBoolean("noise_suppression"))
            .putInt(BUBBLE_SIZE, snapshot.getInt("bubble_size_dp").coerceIn(40, 56))
            .putInt(BUBBLE_OPACITY, snapshot.getInt("bubble_opacity_percent").coerceIn(40, 100))
            .putFloat(BUBBLE_X, snapshot.getDouble("bubble_x_fraction").toFloat().coerceIn(0f, 1f))
            .putFloat(BUBBLE_Y, snapshot.getDouble("bubble_y_fraction").toFloat().coerceIn(0f, 1f))
            .putBoolean(ON_DEVICE_RECOGNITION, snapshot.getBoolean("on_device_recognition"))
            .apply { snapshot.optString("local_model_id").takeIf { id -> LocalSpeechModels.catalog.any { it.id == id } }?.let { putString(LOCAL_MODEL_ID, it) } }
            .putString(SPEECH_LANGUAGE, snapshot.optString("speech_language", "auto"))
            .putBoolean(TRANSLATE_TO_ENGLISH, snapshot.optBoolean("translate_to_english"))
            .putBoolean(REMOVE_FILLER_WORDS, snapshot.optBoolean("remove_filler_words", true))
            .putInt(HISTORY_RETENTION_DAYS, retention)
            .putBoolean(CLEANUP_ENABLED, snapshot.getBoolean("cleanup_enabled") && !snapshot.optBoolean("cleanup_key_unreadable"))
            .putString("cleanup_prompts", prompts.toString())
            .apply { snapshot.optString("selected_cleanup_prompt").takeIf(String::isNotBlank)?.let { putString("selected_cleanup_prompt", it) } }
            .putString(DICTIONARY, JSONArray(terms).toString())
            .apply { if (cleanupFailure == null) remove(LAST_CLEANUP_FAILURE) else putString(LAST_CLEANUP_FAILURE, cleanupFailure) }
            .apply { if (finalText == null) remove(LATEST_TRANSCRIPT) else putString(LATEST_TRANSCRIPT, finalText) }
            .apply { if (rawText == null) remove(LATEST_RAW_TRANSCRIPT) else putString(LATEST_RAW_TRANSCRIPT, rawText) }
            .apply { if (transcriptTime == 0L) remove(LATEST_TRANSCRIPT_TIME) else putLong(LATEST_TRANSCRIPT_TIME, transcriptTime) }
            .apply { if (mappedHistoryId == null) remove(LATEST_HISTORY_ID) else putLong(LATEST_HISTORY_ID, mappedHistoryId) }
            .putBoolean(MIGRATION_COMPLETE, true)
            .commit()
            .also { require(it) { "Could not save migrated settings." } }
    }

    val migrationComplete: Boolean get() = preferences.getBoolean(MIGRATION_COMPLETE, false)

    private fun readApiKey(): String? = readKey(keyName(API_KEY, endpointUrl))
        ?: if (endpointUrl == DEFAULT_ENDPOINT) readKey(API_KEY) else null

    private fun readCleanupKey(): String? = readKey(keyName(CLEANUP_KEY, cleanupUrl))
        ?: if (cleanupUrl == DEFAULT_CLEANUP_URL) readKey(CLEANUP_KEY) else null

    private fun keyName(prefix: String, url: String): String = prefix + "_" +
        MessageDigest.getInstance("SHA-256").digest(url.toByteArray(StandardCharsets.UTF_8))
            .take(12).joinToString("") { "%02x".format(it) }

    private fun readKey(name: String): String? {
        val stored = preferences.getString(name, null) ?: return null
        val payload = Base64.decode(stored, Base64.NO_WRAP)
        require(payload.size > GCM_IV_BYTES) { "Stored API key is unavailable. Re-enter it in Murmur." }
        val cipher = Cipher.getInstance("AES/GCM/NoPadding")
        cipher.init(
            Cipher.DECRYPT_MODE,
            getOrCreateKey(),
            GCMParameterSpec(128, payload.copyOfRange(0, GCM_IV_BYTES)),
        )
        return String(cipher.doFinal(payload, GCM_IV_BYTES, payload.size - GCM_IV_BYTES), StandardCharsets.UTF_8)
    }

    private fun getOrCreateKey(): SecretKey {
        val keyStore = KeyStore.getInstance("AndroidKeyStore").apply { load(null) }
        (keyStore.getKey(KEY_ALIAS, null) as? SecretKey)?.let { return it }

        val generator = KeyGenerator.getInstance(KeyProperties.KEY_ALGORITHM_AES, "AndroidKeyStore")
        val specification = KeyGenParameterSpec.Builder(
            KEY_ALIAS,
            KeyProperties.PURPOSE_ENCRYPT or KeyProperties.PURPOSE_DECRYPT,
        )
            .setBlockModes(KeyProperties.BLOCK_MODE_GCM)
            .setEncryptionPaddings(KeyProperties.ENCRYPTION_PADDING_NONE)
            .build()
        generator.init(specification)
        return generator.generateKey()
    }

    companion object {
        const val DEFAULT_ENDPOINT = "https://openrouter.ai/api/v1/audio/transcriptions"
        const val DEFAULT_MODEL = "openai/whisper-large-v3-turbo"
        const val DEFAULT_CLEANUP_URL = "https://openrouter.ai/api/v1/chat/completions"
        const val DEFAULT_CLEANUP_MODEL = ""
        private const val ENDPOINT_URL = "endpoint_url"
        private const val MODEL = "model"
        private const val API_KEY = "api_key_encrypted"
        private const val NOISE_SUPPRESSION = "noise_suppression"
        private const val BUBBLE_SIZE = "bubble_size_dp"
        private const val BUBBLE_OPACITY = "bubble_opacity_percent"
        private const val BUBBLE_X = "bubble_x_fraction"
        private const val BUBBLE_Y = "bubble_y_fraction"
        private const val ON_DEVICE_RECOGNITION = "on_device_recognition"
        private const val LOCAL_MODEL_ID = "local_model_id"
        private const val SPEECH_LANGUAGE = "speech_language"
        private const val TRANSLATE_TO_ENGLISH = "translate_to_english"
        private const val REMOVE_FILLER_WORDS = "remove_filler_words"
        private const val HISTORY_RETENTION_DAYS = "history_retention_days"
        private const val CLEANUP_ENABLED = "cleanup_enabled"
        private const val LAST_CLEANUP_FAILURE = "last_cleanup_failure"
        private const val CLEANUP_URL = "cleanup_url"
        private const val CLEANUP_MODEL = "cleanup_model"
        private const val CLEANUP_FORMALITY = "cleanup_formality"
        private const val CLEANUP_KEY = "cleanup_key_encrypted"
        private const val DICTIONARY = "dictionary"
        private const val LATEST_TRANSCRIPT = "latest_transcript"
        private const val LATEST_RAW_TRANSCRIPT = "latest_raw_transcript"
        private const val LATEST_HISTORY_ID = "latest_history_id"
        private const val LATEST_TRANSCRIPT_TIME = "latest_transcript_time"
        private const val KEY_ALIAS = "murmur_transcription_key_v1"
        private const val GCM_IV_BYTES = 12
        private const val MIGRATION_COMPLETE = "legacy_migration_complete"

        fun parseEndpointUrl(value: String): URI {
            val uri = try {
                URI(value.trim())
            } catch (_: Exception) {
                throw IllegalArgumentException("Enter a valid transcription URL.")
            }
            val host = uri.host?.lowercase()
            val loopback = host == "localhost" || host == "127.0.0.1"
            require(
                host != null &&
                    (uri.scheme?.lowercase() == "https" || (uri.scheme?.lowercase() == "http" && loopback)) &&
                    uri.userInfo == null &&
                    uri.query == null &&
                    uri.fragment == null &&
                    uri.path.endsWith("/audio/transcriptions"),
            ) {
                "Use an HTTPS /audio/transcriptions URL. HTTP is allowed only for this phone's localhost."
            }
            return uri
        }

        fun parseCleanupUrl(value: String): URI {
            val uri = try { URI(value.trim()) } catch (_: Exception) {
                throw IllegalArgumentException("Enter a valid cleanup URL.")
            }
            val host = uri.host?.lowercase()
            val loopback = host == "localhost" || host == "127.0.0.1"
            require(host != null &&
                (uri.scheme?.lowercase() == "https" || (uri.scheme?.lowercase() == "http" && loopback)) &&
                uri.userInfo == null && uri.query == null && uri.fragment == null &&
                (uri.path.endsWith("/chat/completions") || uri.path.endsWith("/messages"))) {
                "Use an HTTPS /chat/completions or /messages URL. HTTP is allowed only for this phone's localhost."
            }
            return uri
        }
    }
}
