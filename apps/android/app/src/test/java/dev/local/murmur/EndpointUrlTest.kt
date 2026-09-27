package dev.local.murmur

import org.junit.Assert.assertEquals
import org.junit.Assert.assertThrows
import org.junit.Test

class EndpointUrlTest {
    @Test
    fun acceptsHttpsAndPhoneLoopback() {
        assertEquals(
            "https://openrouter.ai/api/v1/audio/transcriptions",
            AppSettings.parseEndpointUrl(AppSettings.DEFAULT_ENDPOINT).toString(),
        )
        assertEquals(
            "http://127.0.0.1:8000/v1/audio/transcriptions",
            AppSettings.parseEndpointUrl("http://127.0.0.1:8000/v1/audio/transcriptions").toString(),
        )
    }

    @Test
    fun rejectsInsecureRemoteUrlAndEmbeddedCredentials() {
        listOf(
            "http://192.168.1.20:8000/v1/audio/transcriptions",
            "http://example.com/v1/audio/transcriptions",
            "https://user:secret@example.com/v1/audio/transcriptions",
            "https://example.com/v1/audio/transcriptions?key=secret",
            "https://example.com/v1/chat/completions",
        ).forEach { url ->
            assertThrows(IllegalArgumentException::class.java) {
                AppSettings.parseEndpointUrl(url)
            }
        }
    }

    @Test
    fun cleanupRequiresChatCompletionsAndSafeTransport() {
        assertEquals(
            "https://openrouter.ai/api/v1/chat/completions",
            AppSettings.parseCleanupUrl(AppSettings.DEFAULT_CLEANUP_URL).toString(),
        )
        assertEquals(
            "http://127.0.0.1:11434/v1/chat/completions",
            AppSettings.parseCleanupUrl("http://127.0.0.1:11434/v1/chat/completions").toString(),
        )
        listOf(
            "http://192.168.1.20:11434/v1/chat/completions",
            "https://user:secret@example.com/v1/chat/completions",
            "https://example.com/v1/chat/completions?key=secret",
            "https://example.com/v1/audio/transcriptions",
        ).forEach { url ->
            assertThrows(IllegalArgumentException::class.java) {
                AppSettings.parseCleanupUrl(url)
            }
        }
    }
}
