package dev.local.murmur

import org.junit.Assert.assertEquals
import org.junit.Assert.assertThrows
import org.junit.Assert.assertTrue
import org.junit.Test
import java.io.IOException
import java.net.ServerSocket
import java.net.URI
import java.nio.charset.StandardCharsets
import java.util.concurrent.atomic.AtomicReference

class CleanupClientTest {
    @Test fun explicitSpellingReplacesTheGuessAndKeepsSurroundingText() {
        val cases = listOf(
            Triple("my name is Simion, spelt S-E-M-Y-O-N.", "my name is Semyon.", listOf("Semyon")),
            Triple("my name is Simion - spelt S-E-M-Y-O-N.", "my name is Semyon.", listOf("Semyon")),
            Triple("John, spelled C-A-T", "Cat", listOf("Cat")),
            Triple("Marion spelled M A R I A N arrives Monday", "Marian arrives Monday", listOf("Marian")),
            Triple("Can Simion spelt S-E-M-Y-O-N, attend!?", "Can Semyon attend!?", listOf("Semyon")),
            Triple("it's simion spelt s-e-m-y-o-n", "it's semyon", listOf("semyon")),
            Triple("SIMION SPELLED S - E - M - Y - O - N", "SEMYON", listOf("SEMYON")),
            Triple("Seán spelt S-E-A-N", "Sean", listOf("Sean")),
            Triple("Simion spelt S-E-M-Y-O-N and Marion spelled M A R I A N", "Semyon and Marian", listOf("Semyon", "Marian")),
        )
        for ((raw, expected, words) in cases) {
            assertEquals(expected to words, prepareExplicitSpellings(raw))
        }
    }

    @Test fun quotedLiteralPartialAndAmbiguousSpellingsAreLeftForTheModel() {
        for (raw in listOf(
            "the literal example is Simion spelt S-E-M-Y-O-N",
            "she said \"Simion spelt S-E-M-Y-O-N\"",
            "she said 'Simion spelt S-E-M-Y-O-N'",
            "she said ‘Simion spelt S-E-M-Y-O-N’",
            "`Simion spelt S-E-M-Y-O-N`",
            "Simion spelt S E M Y O N I think that's right",
            "Simion spelt S E M Y O N a letter is missing",
            "Simion spelt S-E-M-Y-O-N-",
            "Simion spelt S E M y o n",
            "O'Conner spelt C-O-N-N-O-R",
            "example.com spelt C-O-M",
            "Simion spelled S",
            "Simion spelt SÉ-M-Y-O-N",
            "She spelled C-A-T for the class",
            "John spelled C-A-T for the class",
            "The teacher spelled C-A-T slowly",
            "this word is spelled C-A-T",
        )) {
            assertEquals(raw to emptyList<String>(), prepareExplicitSpellings(raw))
        }
    }

    @Test fun cleanupCannotOverwriteOrDropAnExplicitSpelling() {
        val raw = "Contact Simion spelt S-E-M-Y-O-N tomorrow!"
        for (cleaned in listOf("Contact Simeon tomorrow!", "Contact Semyon2 tomorrow!", "Contact Semyon_extra tomorrow!", "Contact tomorrow!")) {
            val response = """{"choices":[{"message":{"content":"$cleaned"},"finish_reason":"stop"}]}"""
            withResponse("/chat/completions", response) { config ->
                assertEquals("Contact Semyon tomorrow!", CleanupClient().clean(config, raw))
            }
        }
        assertTrue(containsExplicitSpellings("Contact SEMYON tomorrow!", "Contact Semyon tomorrow!", listOf("Semyon")))
        assertTrue(containsExplicitSpellings("The original question?", "raw", emptyList()))
        val (prepared, spellings) = prepareExplicitSpellings("Semyon met Simion spelt S-E-M-Y-O-N and Simion spelt S-E-M-Y-O-N")
        assertTrue(!containsExplicitSpellings("Semyon met Semyon", prepared, spellings))
    }

    @Test fun incompleteCleanupIsRejectedForBothResponseFormats() {
        for (reason in listOf("length", "content_filter")) {
            val response = """{"choices":[{"message":{"content":"Keep thi"},"finish_reason":"$reason"}]}"""
            withResponse("/chat/completions", response) { config ->
                assertThrows(IOException::class.java) { CleanupClient().clean(config, "Keep this.") }
            }
        }
        for (reason in listOf("max_tokens", "refusal")) {
            val response = """{"content":[{"type":"text","text":"Keep thi"}],"stop_reason":"$reason"}"""
            withResponse("/messages", response) { config ->
                assertThrows(IOException::class.java) { CleanupClient().clean(config, "Keep this.") }
            }
        }
    }

    @Test fun finalPunctuationSurvivesResponseEndingAtEof() {
        val response = """{"choices":[{"message":{"content":"Ready!?"},"finish_reason":"stop"}]}"""
        withResponse("/chat/completions", response) { config ->
            assertEquals("Ready!?", CleanupClient().clean(config, "ready"))
        }
    }

    @Test fun explicitSpellingCanRemoveMostOfRawTranscript() {
        val raw = "Simion, spelt S-E-M-Y-O-N."
        val responses = listOf(
            "/chat/completions" to """{"choices":[{"message":{"content":"Semyon."},"finish_reason":"stop"}]}""",
            "/messages" to """{"content":[{"type":"text","text":"Semyon."}],"stop_reason":"end_turn"}""",
        )
        for ((path, response) in responses) {
            withResponse(path, response) { config ->
                assertEquals("Semyon.", CleanupClient().clean(config, raw))
            }
        }
    }

    @Test fun bothProvidersReceiveThePreparedSpelling() {
        val raw = "Marion spelled M A R I A N arrives Monday"
        val responses = listOf(
            "/chat/completions" to """{"choices":[{"message":{"content":"Marian arrives Monday."},"finish_reason":"stop"}]}""",
            "/messages" to """{"content":[{"type":"text","text":"Marian arrives Monday."}],"stop_reason":"end_turn"}""",
        )
        for ((path, response) in responses) {
            val request = AtomicReference<String>()
            withResponse(path, response, request) { config ->
                assertEquals("Marian arrives Monday.", CleanupClient().clean(config, raw))
            }
            val body = org.json.JSONObject(request.get())
            val messages = body.getJSONArray("messages")
            assertEquals("Marian arrives Monday", messages.getJSONObject(messages.length() - 1).getString("content"))
        }
    }

    @Test fun explicitCorrectionCanRemoveMostOfRawTranscript() {
        val raw = "Tuesday oh no Wednesday no sorry Thursday"
        val response = """{"choices":[{"message":{"content":"Thursday."},"finish_reason":"stop"}]}"""
        withResponse("/chat/completions", response) { config ->
            assertEquals("Thursday.", CleanupClient().clean(config, raw))
        }
    }

    @Test fun blankAndExcessivelyExpandedCleanupAreStillRejected() {
        for (cleaned in listOf("", "Unrelated extra content that was never dictated.")) {
            val response = """{"choices":[{"message":{"content":"$cleaned"},"finish_reason":"stop"}]}"""
            withResponse("/chat/completions", response) { config ->
                assertThrows(IOException::class.java) { CleanupClient().clean(config, "hello") }
            }
        }
    }

    private fun withResponse(path: String, response: String, request: AtomicReference<String>? = null, verify: (CleanupEndpoint) -> Unit) {
        ServerSocket(0).use { server ->
            server.soTimeout = 5_000
            val worker = Thread {
                server.accept().use { socket ->
                    socket.soTimeout = 5_000
                    val input = socket.getInputStream()
                    val headers = StringBuilder()
                    while (!headers.endsWith("\r\n\r\n")) headers.append(input.read().toChar())
                    val length = Regex("(?im)^Content-Length: (\\d+)\r?$").find(headers)?.groupValues?.get(1)?.toInt() ?: 0
                    val body = ByteArray(length)
                    var used = 0
                    while (used < length) {
                        val read = input.read(body, used, length - used)
                        check(read > 0)
                        used += read
                    }
                    request?.set(String(body, StandardCharsets.UTF_8))
                    val bytes = response.toByteArray(StandardCharsets.UTF_8)
                    socket.getOutputStream().write("HTTP/1.1 200 OK\r\nContent-Type: application/json\r\nContent-Length: ${bytes.size}\r\nConnection: close\r\n\r\n".toByteArray() + bytes)
                }
            }.also { it.start() }
            try {
                verify(CleanupEndpoint(URI("http://127.0.0.1:${server.localPort}$path"), "test-model", null, 2, emptyList(), null))
            } finally {
                worker.join(5_000)
                assertTrue(!worker.isAlive)
            }
        }
    }

    @Test fun anthropicMessagesUsesItsOwnRequestAndResponseFormat() {
        val request = AtomicReference<String>()
        ServerSocket(0).use { server ->
            server.soTimeout = 5_000
            val worker = Thread {
                server.accept().use { socket ->
                    socket.soTimeout = 5_000
                    val input = socket.getInputStream()
                    val headers = StringBuilder()
                    while (!headers.endsWith("\r\n\r\n")) headers.append(input.read().toChar())
                    val length = Regex("(?im)^Content-Length: (\\d+)\r?$").find(headers)?.groupValues?.get(1)?.toInt() ?: 0
                    val body = ByteArray(length)
                    var used = 0
                    while (used < length) used += input.read(body, used, length - used)
                    request.set(headers.toString() + String(body, StandardCharsets.UTF_8))
                    val response = """{"content":[{"type":"text","text":"Hello world."}]}""".toByteArray()
                    socket.getOutputStream().write("HTTP/1.1 200 OK\r\nContent-Type: application/json\r\nContent-Length: ${response.size}\r\nConnection: close\r\n\r\n".toByteArray() + response)
                }
            }.also { it.start() }
            val config = CleanupEndpoint(URI("http://127.0.0.1:${server.localPort}/v1/messages"),
                "test-model", "test-key", 2, emptyList(), null)
            assertEquals("Hello world.", CleanupClient().clean(config, "hello world"))
            worker.join(5_000)
            assertTrue(request.get().contains("x-api-key: test-key", ignoreCase = true))
            assertTrue(request.get().contains("\"max_tokens\""))
            assertTrue(request.get().contains("\"system\""))
        }
    }
}
