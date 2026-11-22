package dev.local.murmur

internal object TranscriptText {
    private val universal = listOf("uh", "uhm", "umm", "uhh", "uhhh", "ehh", "ehm", "ahm", "hmm", "hm", "mmm", "хм", "ммм")
    private val languageWords = mapOf(
        "en" to listOf("um", "ah", "eh", "ha"),
        "de" to listOf("äh", "ähm"),
        "fr" to listOf("euh"),
    )

    private val quotation = Regex("""(?s)"[^"]*"|“[^”]*”|‘.*?’(?!\p{L})|(?<!\p{L})'.*?'(?!\p{L})|«[^»]*»|「[^」]*」|『[^』]*』""")
    private val repeated = Regex("""(?iu)(?<!\S)(\p{L}+)(?:\s+\1){2,}(?!\S)""")
    private val spacing = Regex("""\s+""")

    fun prepare(raw: String, language: String, removeFillers: Boolean): String {
        val filler = if (removeFillers) {
            val words = universal + languageWords[language.substringBefore('-')].orEmpty()
            Regex("""(?i)\b(?:${words.joinToString("|", transform = Regex::escape)})\b[,.]?""")
        } else null
        fun clean(text: String): String {
            val prepared = if (filler == null) text else text.replace(filler, "").replace(repeated, "$1")
            return prepared.replace(spacing, " ")
        }
        val result = buildString {
            var start = 0
            for (quote in quotation.findAll(raw)) {
                append(clean(raw.substring(start, quote.range.first)))
                append(quote.value)
                start = quote.range.last + 1
            }
            append(clean(raw.substring(start)))
        }.trim()
        return result.ifEmpty { raw.trim() }
    }
}
