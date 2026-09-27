package dev.local.murmur

import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Assert.assertThrows
import org.junit.Test

class UpdateClientTest {
    private val valid = """{
        "versionCode": 5,
        "versionName": "0.1.4",
        "apkUrl": "https://github.com/example/murmur/releases/download/v0.1.4/murmur.apk",
        "sha256": "${"a".repeat(64)}",
        "sizeBytes": 1000000
    }"""

    @Test fun onlyOffersNewerVersions() {
        assertEquals(5L, UpdateClient.parseUpdateManifest(valid, 4)?.versionCode)
        assertNull(UpdateClient.parseUpdateManifest(valid, 5))
    }

    @Test fun rejectsInsecureOrInvalidDownloads() {
        assertThrows(IllegalArgumentException::class.java) {
            UpdateClient.parseUpdateManifest(valid.replace("https://", "http://"), 4)
        }
        assertThrows(IllegalArgumentException::class.java) {
            UpdateClient.parseUpdateManifest(valid.replace("a".repeat(64), "1234"), 4)
        }
        assertThrows(IllegalArgumentException::class.java) {
            UpdateClient.parseUpdateManifest(valid.replace("1000000", "300000000"), 4)
        }
        assertThrows(IllegalArgumentException::class.java) {
            UpdateClient.parseUpdateManifest(valid.replace("\"versionCode\": 5", "\"versionCode\": 5.5"), 4)
        }
    }
}
