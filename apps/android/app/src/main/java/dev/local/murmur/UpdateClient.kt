package dev.local.murmur

import android.content.Context
import android.content.pm.PackageInfo
import android.content.pm.PackageManager
import android.os.Build
import org.json.JSONObject
import java.io.File
import java.net.HttpURLConnection
import java.net.URL
import java.security.MessageDigest

internal data class AvailableUpdate(
    val versionCode: Long,
    val versionName: String,
    val apkUrl: URL,
    val sha256: String,
    val sizeBytes: Long,
)

internal class UpdateClient(private val context: Context) {
    fun check(): AvailableUpdate? {
        val manifestUrl = BuildConfig.UPDATE_MANIFEST_URL
        require(manifestUrl.isNotBlank()) { "Updates are not configured in this build." }
        val connection = openHttps(URL(manifestUrl))
        val bytes = try {
            require(connection.contentLengthLong <= MAX_MANIFEST_BYTES) { "Update information is too large." }
            connection.inputStream.use { input ->
                val output = java.io.ByteArrayOutputStream()
                val buffer = ByteArray(4096)
                while (true) {
                    val count = input.read(buffer)
                    if (count < 0) break
                    require(output.size() + count <= MAX_MANIFEST_BYTES) { "Update information is too large." }
                    output.write(buffer, 0, count)
                }
                output.toByteArray()
            }
        } finally {
            connection.disconnect()
        }
        require(bytes.size <= MAX_MANIFEST_BYTES) { "Update information is too large." }
        return parseUpdateManifest(String(bytes, Charsets.UTF_8), BuildConfig.VERSION_CODE.toLong())
    }

    fun download(update: AvailableUpdate, onProgress: (Int) -> Unit): File {
        val directory = File(context.cacheDir, "updates").apply { mkdirs() }
        val partial = File(directory, "murmur-update.part")
        val apk = File(directory, "murmur-update.apk")
        partial.delete()
        apk.delete()
        try {
            val digest = MessageDigest.getInstance("SHA-256")
            var received = 0L
            val connection = openHttps(update.apkUrl)
            try {
                require(connection.contentLengthLong <= update.sizeBytes) { "Update download is larger than expected." }
                connection.inputStream.use { input ->
                    partial.outputStream().buffered().use { output ->
                        val buffer = ByteArray(64 * 1024)
                        while (true) {
                            val count = input.read(buffer)
                            if (count < 0) break
                            received += count
                            require(received <= update.sizeBytes) { "Update download is larger than expected." }
                            digest.update(buffer, 0, count)
                            output.write(buffer, 0, count)
                            onProgress((received * 100 / update.sizeBytes).toInt())
                        }
                    }
                }
            } finally {
                connection.disconnect()
            }
            require(received == update.sizeBytes) { "Update download is incomplete." }
            val actualHash = digest.digest().joinToString("") { "%02x".format(it) }
            require(actualHash == update.sha256) { "Update checksum did not match." }
            verifyPackage(partial, update.versionCode)
            require(partial.renameTo(apk)) { "Could not prepare the update for installation." }
            return apk
        } catch (error: Exception) {
            partial.delete()
            throw error
        }
    }

    @Suppress("DEPRECATION")
    private fun verifyPackage(apk: File, versionCode: Long) {
        val flags = if (Build.VERSION.SDK_INT >= 28) PackageManager.GET_SIGNING_CERTIFICATES else PackageManager.GET_SIGNATURES
        val manager = context.packageManager
        val candidate = manager.getPackageArchiveInfo(apk.absolutePath, flags)
            ?: error("Downloaded file is not an Android app.")
        val installed = manager.getPackageInfo(context.packageName, flags)
        require(candidate.packageName == context.packageName) { "Update is for a different app." }
        val candidateVersion = if (Build.VERSION.SDK_INT >= 28) candidate.longVersionCode else candidate.versionCode.toLong()
        require(candidateVersion == versionCode) { "Update version did not match." }
        fun signers(info: PackageInfo): Set<String> = if (Build.VERSION.SDK_INT >= 28) {
            info.signingInfo?.apkContentsSigners?.map { it.toCharsString() }?.toSet().orEmpty()
        } else {
            info.signatures?.map { it.toCharsString() }?.toSet().orEmpty()
        }
        val currentSigners = signers(installed)
        require(currentSigners.isNotEmpty() && currentSigners == signers(candidate)) {
            "Update was signed with a different key."
        }
    }

    private fun openHttps(initial: URL): HttpURLConnection {
        var url = initial
        repeat(5) {
            require(url.protocol == "https" && url.userInfo == null) { "Update URL must use HTTPS." }
            val connection = (url.openConnection() as HttpURLConnection).apply {
                instanceFollowRedirects = false
                connectTimeout = 10_000
                readTimeout = 30_000
                setRequestProperty("Accept", "application/json, application/vnd.android.package-archive")
            }
            when (connection.responseCode) {
                HttpURLConnection.HTTP_OK -> return connection
                HttpURLConnection.HTTP_MOVED_PERM, HttpURLConnection.HTTP_MOVED_TEMP, 307, 308 -> {
                    val location = connection.getHeaderField("Location") ?: error("Update redirect is missing its destination.")
                    url = URL(url, location)
                    connection.disconnect()
                }
                else -> {
                    val code = connection.responseCode
                    connection.disconnect()
                    error("Update server returned HTTP $code.")
                }
            }
        }
        error("Update server redirected too many times.")
    }

    companion object {
        private const val MAX_MANIFEST_BYTES = 64 * 1024
        private const val MAX_APK_BYTES = 250_000_000L

        internal fun parseUpdateManifest(body: String, currentVersion: Long): AvailableUpdate? {
            val json = JSONObject(body)
            fun positiveLong(name: String): Long {
                val value = json.get(name).toString()
                require(value.matches(Regex("[0-9]{1,18}"))) { "Invalid $name in update information." }
                return value.toLong()
            }
            val versionCode = positiveLong("versionCode")
            val versionName = json.getString("versionName")
            val apkUrl = URL(json.getString("apkUrl"))
            val sha256 = json.getString("sha256").lowercase()
            val sizeBytes = positiveLong("sizeBytes")
            require(versionCode > 0 && versionName.length in 1..50 && versionName.none(Char::isISOControl)) {
                "Invalid update version."
            }
            require(apkUrl.protocol == "https" && apkUrl.userInfo == null) { "Update download must use HTTPS." }
            require(sha256.matches(Regex("[0-9a-f]{64}"))) { "Invalid update checksum." }
            require(sizeBytes in 1..MAX_APK_BYTES) { "Invalid update size." }
            if (versionCode <= currentVersion) return null
            return AvailableUpdate(versionCode, versionName, apkUrl, sha256, sizeBytes)
        }
    }
}
