package dev.local.murmur

internal object TranscriptText {
    private val universal = listOf("uh", "uhm", "umm", "uhh", "uhhh", "ehh", "ehm", "ahm", "hmm", "hm", "mmm", "хм", "ммм")
    private val languageWords = mapOf(
        "en" to listOf("um", "ah", "eh", "ha"),
        "de" to listOf("äh", "ähm"),
        "fr" to listOf("euh"),
    )

    fun prepare(raw: String, language: String, removeFillers: Boolean): String {
        var text = raw
        if (removeFillers) {
            val words = universal + languageWords[language.substringBefore('-')].orEmpty()
            words.forEach { word ->
                text = text.replace(Regex("(?i)\\b${Regex.escape(word)}\\b[,.]?"), "")
            }
        }
        val words = text.split(Regex("\\s+")).filter(String::isNotEmpty)
        val collapsed = buildList {
            var index = 0
            while (index < words.size) {
                val word = words[index]
                var count = 1
                if (word.all(Char::isLetter)) {
                    while (index + count < words.size && words[index + count].equals(word, ignoreCase = true)) count++
                }
                add(word)
                index += if (count >= 3) count else 1
            }
        }.joinToString(" ").trim()
        return collapsed.ifEmpty { raw.trim() }
    }
}
