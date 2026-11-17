package dev.local.murmur

import java.util.Locale

internal object SpeechLanguages {
    const val AUTO = "auto"

    // languages whisper handles well enough to offer in a list. any other whisper code
    // still works when it is already saved, it just is not offered here
    private val common = listOf(
        "en", "ga", "fr", "de", "es", "it", "pt", "nl", "pl", "uk", "ru", "cs", "ro", "hu", "el",
        "sv", "da", "no", "fi", "tr", "ar", "he", "hi", "ur", "zh", "ja", "ko", "vi", "id", "ms",
    )

    // auto detection guesses again for every piece of audio, and an accent can tip a
    // guess into the wrong language, so a fresh install starts on the phone's language
    fun deviceDefault(): String = Locale.getDefault().language.takeIf { it in common } ?: AUTO

    // the phone's language first, then the rest by name, with the saved value kept even
    // when it is not one of the common ones
    fun options(current: String): List<String> {
        val device = Locale.getDefault().language
        val named = (common + current).filter { it != AUTO && it != device }.distinct().sortedBy { label(it) }
        return listOf(AUTO) + listOfNotNull(device.takeIf { it in common || it == current }) + named
    }

    fun label(code: String): String {
        if (code == AUTO) return "Detect automatically"
        val name = Locale.forLanguageTag(code).getDisplayName(Locale.getDefault())
        return if (name.isBlank() || name == code) code
        else name.replaceFirstChar { it.titlecase(Locale.getDefault()) }
    }
}
