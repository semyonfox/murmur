package dev.local.murmur

import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test
import java.net.ServerSocket
import java.net.URI
import java.nio.charset.StandardCharsets
import java.util.concurrent.atomic.AtomicReference

class CleanupClientTest {
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
