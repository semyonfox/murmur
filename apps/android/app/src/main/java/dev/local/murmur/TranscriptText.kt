package dev.local.murmur

internal object TranscriptText {
    private val universal = listOf("uh", "uhm", "umm", "uhh", "uhhh", "ehh", "ehm", "ahm", "hmm", "hm", "mmm", "хм", "ммм")
    private val languageWords = mapOf(
        "en" to listOf("um", "ah", "eh", "ha"),
        "de" to listOf("äh", "ähm"),
        "fr" to listOf("euh"),
    )

    private val quotation = Regex("""(?s)"[^"]*"|“[^”]*”|‘.*?’(?!\p{L})|(?<!\p{L})'.*?'(?!\p{L})|«[^»]*»|「[^」]*」|『[^』]*』""")
    private val repeated = Regex("""(?i)(?<!\S)(\p{L}+)(?:\s+\1){2,}(?!\S)""")
    private val spacing = Regex("""\s+""")

    fun prepare(raw: String, language: String, removeFillers: Boolean): String {
        if (!removeFillers) return raw.trim()
        val words = universal + languageWords[language.substringBefore('-')].orEmpty()
        val filler = Regex("""(?i)\b(?:${words.joinToString("|", transform = Regex::escape)})\b[,.]?""")
        fun clean(text: String) = text.replace(filler, "").replace(repeated, "$1").replace(spacing, " ")
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
