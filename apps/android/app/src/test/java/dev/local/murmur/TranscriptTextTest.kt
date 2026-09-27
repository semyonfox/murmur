package dev.local.murmur

import org.junit.Assert.assertEquals
import org.junit.Test

class TranscriptTextTest {
    @Test fun keepsAmbiguousWordsWithoutLanguageEvidence() {
        assertEquals("um livro", TranscriptText.prepare("um livro", "auto", true))
        assertEquals("ha llegado", TranscriptText.prepare("ha llegado", "es", true))
    }

    @Test fun removesOnlyKnownFillersAndCanBeDisabled() {
        assertEquals("I think so", TranscriptText.prepare("uh, I um think so", "en", true))
        assertEquals("uh, I um think so", TranscriptText.prepare("uh, I um think so", "en", false))
    }

    @Test fun collapsesLongStuttersWithoutDroppingDeliberateEmphasis() {
        assertEquals("I agree", TranscriptText.prepare("I I I I agree", "en", false))
        assertEquals("no no", TranscriptText.prepare("no no", "en", false))
    }
}
