package dev.local.murmur

import org.json.JSONObject
import java.io.IOException
import java.io.ByteArrayOutputStream
import java.net.HttpURLConnection
import java.net.URI
import java.nio.charset.StandardCharsets

internal object CleanupModelCatalog {
    fun fetch(configured: URI, key: String?): List<String> {
        val base = configured.toASCIIString().removeSuffix("/chat/completions").removeSuffix("/messages")
        return try { fetchAt(URI("$base/models"), configured, key, "data", "id") }
            catch (error: IOException) {
                if (configured.host !in listOf("localhost", "127.0.0.1") || !configured.port.equals(11434)) throw error
                fetchAt(URI("http://${configured.host}:11434/api/tags"), configured, key, "models", "name")
            }
    }

    private fun fetchAt(url: URI, configured: URI, key: String?, arrayName: String, itemName: String): List<String> {
        val request = url.toURL().openConnection() as HttpURLConnection
        try {
            request.instanceFollowRedirects = false
            request.connectTimeout = 10_000
            request.readTimeout = 15_000
            request.setRequestProperty("Accept", "application/json")
            if (configured.path.endsWith("/messages")) {
                key?.let { request.setRequestProperty("x-api-key", it) }
                request.setRequestProperty("anthropic-version", "2023-06-01")
            } else key?.let { request.setRequestProperty("Authorization", "Bearer $it") }
            if (request.responseCode !in 200..299) throw IOException("Model service returned HTTP ${request.responseCode}.")
            val bytes = request.inputStream.use { input ->
                val output = ByteArrayOutputStream()
                val buffer = ByteArray(8192)
                while (output.size() <= 1024 * 1024) {
                    val count = input.read(buffer)
                    if (count < 0) break
                    output.write(buffer, 0, count)
                }
                output.toByteArray()
            }
            if (bytes.size > 1024 * 1024) throw IOException("Model list was too large.")
            val data = JSONObject(String(bytes, StandardCharsets.UTF_8)).getJSONArray(arrayName)
            return (0 until minOf(data.length(), 500)).mapNotNull { index ->
                data.optJSONObject(index)?.optString(itemName)?.takeIf { it.isNotBlank() && it.length <= 200 }
            }.sorted()
        } finally {
            request.disconnect()
        }
    }
}
