package dev.local.murmur

import org.json.JSONObject
import java.io.IOException
import java.net.HttpURLConnection
import java.net.URL

internal data class OpenRouterUsage(
    val keyCount: Int,
    val weekUsd: Double,
    val monthUsd: Double,
    val allTimeUsd: Double,
    val byokMonthUsd: Double,
)

internal object OpenRouterUsageClient {
    private const val ENDPOINT = "https://openrouter.ai/api/v1/key"

    fun fetch(keys: List<String>): OpenRouterUsage {
        var total = OpenRouterUsage(0, 0.0, 0.0, 0.0, 0.0)
        for (key in keys.distinct()) {
            require(key.isNotBlank() && key.none(Char::isISOControl)) { "OpenRouter key is malformed." }
            val connection = (URL(ENDPOINT).openConnection() as HttpURLConnection)
            try {
                connection.instanceFollowRedirects = false
                connection.connectTimeout = 10_000
                connection.readTimeout = 10_000
                connection.setRequestProperty("Authorization", "Bearer $key")
                connection.setRequestProperty("Accept", "application/json")
                if (connection.responseCode !in 200..299) throw IOException("OpenRouter rejected the usage request.")
                val response = connection.inputStream.use { input ->
                    val bytes = ByteArray(65_537)
                    var used = 0
                    while (used < bytes.size) {
                        val count = input.read(bytes, used, bytes.size - used)
                        if (count < 0) break
                        used += count
                    }
                    if (used == bytes.size) throw IOException("OpenRouter usage response was too large.")
                    JSONObject(String(bytes, 0, used, Charsets.UTF_8))
                }
                val usage = parse(response)
                total = OpenRouterUsage(
                    total.keyCount + 1,
                    checkedSum(total.weekUsd, usage.weekUsd),
                    checkedSum(total.monthUsd, usage.monthUsd),
                    checkedSum(total.allTimeUsd, usage.allTimeUsd),
                    checkedSum(total.byokMonthUsd, usage.byokMonthUsd),
                )
            } finally {
                connection.disconnect()
            }
        }
        return total
    }

    internal fun parse(response: JSONObject): OpenRouterUsage {
        val data = response.optJSONObject("data") ?: throw IOException("OpenRouter returned invalid usage data.")
        if (data.opt("is_management_key") != false) {
            throw IOException("OpenRouter management keys cannot be used for usage checks.")
        }
        return OpenRouterUsage(
            0,
            number(data, "usage_weekly"),
            number(data, "usage_monthly"),
            number(data, "usage"),
            number(data, "byok_usage_monthly"),
        )
    }

    private fun number(data: JSONObject, name: String): Double {
        val value = data.opt(name) as? Number ?: throw IOException("OpenRouter returned invalid usage data.")
        return value.toDouble().takeIf { it.isFinite() && it >= 0.0 }
            ?: throw IOException("OpenRouter returned invalid usage data.")
    }

    private fun checkedSum(left: Double, right: Double): Double = (left + right).takeIf { it.isFinite() }
        ?: throw IOException("OpenRouter usage total is invalid.")
}
