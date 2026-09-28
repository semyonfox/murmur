package dev.local.murmur

import org.json.JSONObject
import org.junit.Assert.assertEquals
import org.junit.Assert.assertThrows
import org.junit.Test
import java.io.IOException

class OpenRouterUsageTest {
    @Test fun parsesCurrentKeyUsage() {
        val usage = OpenRouterUsageClient.parse(JSONObject("""
            {"data":{"is_management_key":false,"usage_weekly":0.014,"usage_monthly":0.05,
            "usage":1.25,"byok_usage_monthly":0.2}}
        """))
        assertEquals(0.014, usage.weekUsd, 0.0)
        assertEquals(0.05, usage.monthUsd, 0.0)
        assertEquals(1.25, usage.allTimeUsd, 0.0)
        assertEquals(0.2, usage.byokMonthUsd, 0.0)
    }

    @Test fun rejectsManagementKeyAndMalformedAmounts() {
        val management = JSONObject("""{"data":{"is_management_key":true}}""")
        assertThrows(IOException::class.java) { OpenRouterUsageClient.parse(management) }
        val stringAmount = JSONObject("""{"data":{"is_management_key":false,"usage_weekly":"1"}}""")
        assertThrows(IOException::class.java) { OpenRouterUsageClient.parse(stringAmount) }
        val negativeAmount = JSONObject("""
            {"data":{"is_management_key":false,"usage_weekly":-1,"usage_monthly":0,
            "usage":0,"byok_usage_monthly":0}}
        """)
        assertThrows(IOException::class.java) { OpenRouterUsageClient.parse(negativeAmount) }
    }
}
