package dev.local.murmur

import com.sun.net.httpserver.HttpServer
import java.io.IOException
import java.net.InetSocketAddress
import java.net.URI
import java.util.concurrent.ExecutionException
import java.util.concurrent.Executors
import java.util.concurrent.TimeUnit
import java.util.concurrent.atomic.AtomicInteger
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Assert.assertThrows
import org.junit.Assert.assertTrue
import org.junit.Rule
import org.junit.Test
import org.junit.rules.TemporaryFolder
import kotlin.math.sin

class AudioChunksTest {
    @get:Rule val temp = TemporaryFolder()

    private val rate = 16_000

    // speech stands in as a tone at the given level, pauses as near silence with a little hiss
    private fun pcm(vararg parts: Pair<Int, Double>): ByteArray {
        val bytes = ArrayList<Byte>()
        var n = 0
        for ((level, seconds) in parts) {
            repeat((seconds * rate).toInt()) {
                val sample = if (level > 0) (level * sin(n * 0.2)).toInt() else (n % 7) - 3
                bytes += (sample and 0xff).toByte()
                bytes += ((sample shr 8) and 0xff).toByte()
                n++
            }
        }
        return bytes.toByteArray()
    }

    private val speech = 8_000
    private val pause = 0

    private fun feed(chunker: PauseChunker, audio: ByteArray): List<AudioPiece> {
        val pieces = mutableListOf<AudioPiece>()
        var offset = 0
        while (offset < audio.size) {
            val count = minOf(3_200, audio.size - offset)
            pieces += chunker.add(audio.copyOfRange(offset, offset + count), count)
            offset += count
        }
        return pieces
    }

    private fun seconds(piece: AudioPiece) = piece.pcm.size / 32_000.0

    @Test
    fun cutsAShortTakeAtItsFirstPause() {
        val audio = pcm(speech to 3.5, pause to 0.5, speech to 2.0)
        val chunker = PauseChunker(minSeconds = 3)
        val pieces = feed(chunker, audio)

        assertEquals(1, pieces.size)
        assertTrue("cut at ${seconds(pieces[0])} s", seconds(pieces[0]) in 3.5..4.0)
        val tail = chunker.finish()!!
        assertEquals(audio.size, pieces[0].pcm.size + tail.pcm.size)
        assertEquals(pieces[0].pcm.size.toLong(), tail.offsetBytes)
    }

    @Test
    fun ignoresPausesBeforeTheMinimum() {
        val audio = pcm(speech to 8.0, pause to 0.5, speech to 6.0, pause to 0.6, speech to 4.0)
        val chunker = PauseChunker(minSeconds = 12)
        val pieces = feed(chunker, audio)

        assertEquals(1, pieces.size)
        // the pause at 8 s is too early, the one from 14.5 s to 15.1 s is taken
        assertTrue("cut at ${seconds(pieces[0])} s", seconds(pieces[0]) in 14.5..15.1)
        val tail = chunker.finish()!!
        assertEquals(audio.size, pieces[0].pcm.size + tail.pcm.size)
    }

    @Test
    fun forcesACutAtTheQuietestPointWhenTheSpeakerNeverPauses() {
        val audio = pcm(speech to 22.0, pause to 0.1, speech to 8.0)
        val pieces = feed(PauseChunker(minSeconds = 12), audio)

        assertEquals(1, pieces.size)
        assertTrue("cut at ${seconds(pieces[0])} s", seconds(pieces[0]) in 22.0..22.1)
    }

    @Test
    fun keepsTheFinalFortyMillisecondsOfSpeech() {
        val chunker = PauseChunker(minSeconds = 3)
        assertEquals(1, feed(chunker, pcm(speech to 3.5, pause to 0.4)).size)
        feed(chunker, pcm(pause to 1.0, speech to 0.04))

        val tail = chunker.finish()!!
        assertTrue(seconds(tail) > 1.0)
        assertEquals(4_940L, (tail.offsetBytes + tail.pcm.size) / 32)
    }

    @Test
    fun retriesTheWholeTakeAfterBlankPartsOrADroppedConnection() {
        for (firstReply in listOf(200 to """{"text":" "}""", 0 to "")) {
            withSpeechReplies(firstReply, 200 to """{"text":"Recovered"}""") { finish, posts ->
                assertEquals("Recovered", finish())
                assertEquals(2, posts.get())
            }
        }
    }

    @Test
    fun doesNotUploadTheWholeTakeAfterAPermanentOrExhaustedFailure() {
        for (reply in listOf(401 to "", 402 to "", 200 to """{"error":"invalid"}""", 500 to "")) {
            withSpeechReplies(reply, reply) { finish, posts ->
                assertThrows(IOException::class.java) { finish() }
                assertEquals(if (reply.first == 500) 2 else 1, posts.get())
            }
        }
        withSpeechReplies(500 to "", 0 to "") { finish, posts ->
            assertThrows(IOException::class.java) { finish() }
            assertEquals(2, posts.get())
        }
        withSpeechReplies(0 to "", 402 to "", partCount = 2) { finish, posts ->
            assertThrows(HttpStatusException::class.java) { finish() }
            assertEquals(2, posts.get())
        }
    }

    @Test
    fun uploadsTheWholeTakeAtMostOnceAfterBlankParts() {
        withSpeechReplies(200 to """{"text":""}""", 500 to "") { finish, posts ->
            assertThrows(HttpStatusException::class.java) { finish() }
            assertEquals(2, posts.get())
        }
    }

    private fun withSpeechReplies(
        vararg replies: Pair<Int, String>,
        partCount: Int = 1,
        check: (() -> String, AtomicInteger) -> Unit,
    ) {
        val server = HttpServer.create(InetSocketAddress("127.0.0.1", 0), 0)
        val posts = AtomicInteger()
        server.createContext("/speech") { request ->
            request.use {
                it.requestBody.readBytes()
                val (status, body) = replies.getOrElse(posts.getAndIncrement()) { 500 to "" }
                if (status != 0) {
                    val bytes = body.toByteArray()
                    it.responseHeaders.set("Retry-After", "0")
                    it.sendResponseHeaders(status, if (bytes.isEmpty()) -1 else bytes.size.toLong())
                    if (bytes.isNotEmpty()) it.responseBody.write(bytes)
                }
            }
        }
        server.start()
        val worker = Executors.newSingleThreadExecutor()
        try {
            val endpoint = TranscriptionEndpoint(URI("http://127.0.0.1:${server.address.port}/speech"), "test", null)
            val chunks = ChunkedTranscription(worker, TranscriptionClient(), endpoint)
            repeat(partCount) { chunks.add(pcmToWav(ByteArray(2), temp.root)) }
            val full = pcmToWav(ByteArray(2 * (partCount + 1)), temp.root)
            check({
                try {
                    worker.submit<String> { chunks.finish(full) }.get(5, TimeUnit.SECONDS)
                } catch (error: ExecutionException) {
                    throw error.cause ?: error
                }
            }, posts)
        } finally {
            worker.shutdownNow()
            server.stop(0)
        }
    }

    @Test
    fun dropsASilentTailSoWhisperDoesNotInventWords() {
        val chunker = PauseChunker(minSeconds = 3)
        feed(chunker, pcm(speech to 5.0, pause to 3.0))
        assertNull(chunker.finish())
    }

    @Test
    fun dropsSilentPiecesInTheMiddleOfATakeAndKeepsLaterOffsets() {
        val chunker = PauseChunker(minSeconds = 3)
        val pieces = feed(chunker, pcm(speech to 4.0, pause to 8.0, speech to 3.0))

        // only the first four seconds went out, the thinking pause was never sent
        assertEquals(1, pieces.size)
        assertTrue(seconds(pieces[0]) in 4.0..4.5)
        val tail = chunker.finish()!!
        val tailStart = tail.offsetBytes / 32_000.0
        assertTrue("tail starts at $tailStart s", tailStart in 8.0..12.0)
        assertEquals(15 * 32_000L, tail.offsetBytes + tail.pcm.size)
    }

    @Test
    fun sendsNothingWhenTheMicrophoneOnlyDeliversZeros() {
        val chunker = PauseChunker(minSeconds = 3)
        assertTrue(feed(chunker, ByteArray(20 * 32_000)).isEmpty())
        assertNull(chunker.finish())
    }

    @Test
    fun keepsQuietLastWordsAfterLoudSpeech() {
        val chunker = PauseChunker(minSeconds = 3)
        val pieces = feed(chunker, pcm(speech to 5.0, pause to 0.6, 800 to 1.5, pause to 0.3))

        assertEquals(1, pieces.size)
        assertTrue(seconds(chunker.finish()!!) in 1.8..2.4)
    }

    @Test
    fun keepsAShortLastWord() {
        val chunker = PauseChunker(minSeconds = 3)
        feed(chunker, pcm(speech to 5.0, pause to 0.5, speech to 0.25))
        assertTrue(seconds(chunker.finish()!!) in 0.4..0.6)
    }

    // a slammed door early in a lecture used to make every later word count as a
    // pause, cutting mid-sentence and then dropping the last part as silence
    @Test
    fun aLoudBangDoesNotCutSpeechOrDropTheEndOfALecture() {
        val chunker = PauseChunker(minSeconds = 60, maxBytes = 120 * 32_000)
        val audio = pcm(30_000 to 0.1, 3_000 to 70.0, pause to 0.6, 3_000 to 50.0, pause to 0.2)
        val pieces = feed(chunker, audio)

        assertEquals(1, pieces.size)
        assertTrue("cut at ${seconds(pieces[0])} s", seconds(pieces[0]) in 70.1..70.7)
        val tail = chunker.finish()!!
        assertEquals(audio.size, pieces[0].pcm.size + tail.pcm.size)
    }

    @Test
    fun splitsLongAudioAtTheQuietestFrameBeforeEachLimit() {
        val samples = FloatArray(50 * rate) { index ->
            val second = index / rate.toDouble()
            if (second in 17.0..17.3 || second in 33.0..33.3) 0f else 0.5f * sin(index * 0.2).toFloat()
        }
        val pieces = splitAtPauses(samples, 20 * rate, 6 * rate)

        assertEquals(3, pieces.size)
        assertEquals(0, pieces.first().first)
        assertEquals(samples.size - 1, pieces.last().last)
        pieces.zipWithNext().forEach { (left, right) -> assertEquals(left.last + 1, right.first) }
        assertTrue(pieces[0].last / rate.toDouble() in 17.0..17.3)
        assertTrue(pieces[1].last / rate.toDouble() in 33.0..33.3)
    }
}
