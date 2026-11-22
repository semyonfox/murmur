package dev.local.murmur

import org.junit.Assert.assertEquals
import org.junit.Assert.assertThrows
import org.junit.Assert.assertSame
import java.io.IOException
import java.net.UnknownHostException
import java.util.concurrent.CountDownLatch
import java.util.concurrent.ExecutionException
import java.util.concurrent.Executors
import java.util.concurrent.TimeUnit
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

    @Test
    fun providerReplyRequiresAStringButCanContainSilence() {
        for (reply in listOf("""{"error":"bad request"}""", """{"text":42}""", """{"text":null}""", "bad json")) {
            assertThrows(IOException::class.java) { parseSpeechResponse(reply) }
        }
        assertEquals("", parseSpeechResponse("""{"text":"   "}"""))
        assertEquals("speech", parseSpeechResponse("""{"text":" speech "}"""))
    }

    @Test
    fun lecturePartReturnsSpeechAfterATemporaryDnsFailure() {
        val client = TranscriptionClient()
        var attempts = 0

        val text = client.retryLecturePart {
            if (attempts++ == 0) throw UnknownHostException()
            "lecture speech"
        }

        assertEquals("lecture speech", text)
        assertEquals(2, attempts)
    }

    @Test
    fun cancellingLectureDuringRetryWaitPreservesTheOriginalFailure() {
        val client = TranscriptionClient()
        val failure = SpeechConnectionDropped(IOException("connection reset"))
        val firstAttempt = CountDownLatch(1)
        val worker = Executors.newSingleThreadExecutor()
        try {
            val result = worker.submit<String> {
                client.retryLecturePart {
                    if (firstAttempt.count == 0L) error("sent a second request")
                    firstAttempt.countDown()
                    throw failure
                }
            }
            check(firstAttempt.await(2, TimeUnit.SECONDS))
            client.cancel()
            val error = assertThrows(ExecutionException::class.java) { result.get(2, TimeUnit.SECONDS) }
            assertSame(failure, error.cause)
        } finally {
            worker.shutdownNow()
        }
    }
}
