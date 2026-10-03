package dev.local.murmur

import org.json.JSONObject
import org.junit.Assert.*
import org.junit.Test

class AnonymousTelemetryTest {
    private val endpoint = "https://stats.example.test/v1/events"

    @Test fun defaultOffAndOptOutDispatchNothing() {
        for (configured in listOf(false, true)) {
            var sent = 0
            val client = AnonymousTelemetryClient(configured, endpoint, { if (!configured) error("must not read") else false }, { _, _ -> sent++ }, dispatch = { it() })
            client.count(TelemetryCount.APP_OPEN, TelemetryRoute.APP)
            client.error(TelemetryError.STORAGE_FAILED, TelemetryRoute.LIBRARY)
            assertEquals(0, sent)
        }
    }

    @Test fun endpointRejectsSensitiveAndInsecureUrls() {
        assertTrue(AnonymousTelemetryClient.validEndpoint(endpoint))
        for (value in listOf("", "http://stats.example/v1/events", "https://key@stats.example/v1/events", "$endpoint?private=1", "$endpoint#private", "https://stats.example/other")) assertFalse(AnonymousTelemetryClient.validEndpoint(value))
    }

    @Test fun payloadHasOnlySixAllowlistedFields() {
        var payload = ""
        val client = AnonymousTelemetryClient(true, endpoint, { true }, { _, body -> payload = body }, dispatch = { it() })
        client.error(TelemetryError.STORAGE_FAILED, TelemetryRoute.LIBRARY)
        val data = JSONObject(payload)
        assertEquals(setOf("version", "app", "kind", "name", "surface", "route"), data.keys().asSequence().toSet())
        assertEquals("murmur", data.getString("app")); assertEquals("android", data.getString("surface"))
        assertEquals("storage_failed", data.getString("name")); assertEquals("library", data.getString("route"))
        assertFalse(payload.contains("transcript")); assertFalse(payload.contains("https"))
    }

    @Test fun privacyAndTransportFailuresStayHarmless() {
        var failPrivacy = true; var sent = 0
        val client = AnonymousTelemetryClient(true, endpoint, { if (failPrivacy) error("private exception") else true }, { _, _ -> sent++; error("private exception") }, dispatch = { it() })
        client.count(TelemetryCount.APP_OPEN, TelemetryRoute.APP)
        assertEquals(0, sent)
        failPrivacy = false
        client.count(TelemetryCount.SCREEN_VIEW, TelemetryRoute.SETTINGS)
        client.count(TelemetryCount.SCREEN_VIEW, TelemetryRoute.SETTINGS)
        assertEquals(2, sent)
    }

    @Test fun pendingRequestExcludesOverlapAndRechecksOptOutBeforeTransport() {
        val work = mutableListOf<() -> Unit>(); var allowed = true; var sent = 0
        val client = AnonymousTelemetryClient(true, endpoint, { allowed }, { _, _ -> sent++ }, dispatch = { work.add(it) })
        client.count(TelemetryCount.APP_OPEN, TelemetryRoute.APP)
        client.count(TelemetryCount.SCREEN_VIEW, TelemetryRoute.SETTINGS)
        assertEquals(1, work.size)
        allowed = false; work.removeAt(0)(); assertEquals(0, sent)
        allowed = true; client.count(TelemetryCount.SCREEN_VIEW, TelemetryRoute.SETTINGS); work.removeAt(0)(); assertEquals(1, sent)
    }

    @Test fun dispatchFailureDoesNotStrandRequest() {
        var fail = true; var sent = 0
        val client = AnonymousTelemetryClient(true, endpoint, { true }, { _, _ -> sent++ }, dispatch = { if (fail) error("scheduler") else it() })
        client.count(TelemetryCount.APP_OPEN, TelemetryRoute.APP)
        fail = false; client.count(TelemetryCount.SCREEN_VIEW, TelemetryRoute.SETTINGS); assertEquals(1, sent)
    }

    @Test fun minuteLifetimeAndErrorBudgets() {
        var now = 60_000L; var sent = 0
        val client = AnonymousTelemetryClient(true, endpoint, { true }, { _, _ -> sent++ }, clock = { now }, dispatch = { it() })
        repeat(12) { repeat(25) { client.count(TelemetryCount.SCREEN_VIEW, TelemetryRoute.SETTINGS) }; now += 60_000 }
        assertEquals(200, sent)
        var errors = 0
        val errorClient = AnonymousTelemetryClient(true, endpoint, { true }, { _, _ -> errors++ }, clock = { now }, dispatch = { it() })
        repeat(3) { errorClient.error(TelemetryError.STORAGE_FAILED, TelemetryRoute.LIBRARY) }; assertEquals(1, errors)
        now += 60_000; errorClient.error(TelemetryError.STORAGE_FAILED, TelemetryRoute.LIBRARY); assertEquals(2, errors)
    }
}
