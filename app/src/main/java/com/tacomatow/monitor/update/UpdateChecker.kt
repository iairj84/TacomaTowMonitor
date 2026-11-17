package com.tacomatow.monitor.update

import android.content.Context
import android.content.Intent
import androidx.core.content.FileProvider
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext
import org.json.JSONObject
import java.io.File
import java.net.HttpURLConnection
import java.net.URL
import java.security.MessageDigest

data class AppRelease(
    val tag: String,
    val version: String,
    val notes: String,
    val apkUrl: String,
    val sizeBytes: Long,
)

sealed class CheckResult {
    data object UpToDate : CheckResult()
    data class Available(val release: AppRelease) : CheckResult()
    data class Failed(val reason: String) : CheckResult()
}

/**
 * Reads this project's GitHub releases and offers the APK in the latest one.
 *
 * Kept dependency-free (HttpURLConnection + org.json) because this is a
 * side-loading convenience, not a Play pipeline: if the check fails the app
 * must still work exactly as before.
 */
object UpdateChecker {

    private const val REPO = "iairj84/TacomaTowMonitor"
    private const val API = "https://api.github.com/repos/$REPO/releases/latest"

    suspend fun check(currentVersion: String): CheckResult = withContext(Dispatchers.IO) {
        var conn: HttpURLConnection? = null
        try {
            conn = (URL(API).openConnection() as HttpURLConnection).apply {
                connectTimeout = 8_000
                readTimeout = 8_000
                setRequestProperty("Accept", "application/vnd.github+json")
                setRequestProperty("User-Agent", "TacomaTowMonitor")
            }
            val code = conn.responseCode
            if (code == 404) {
                return@withContext CheckResult.Failed(
                    "GitHub says the repository is private, so updates can't be checked " +
                        "from the app. Make it public, or install releases by hand.",
                )
            }
            if (code != 200) {
                return@withContext CheckResult.Failed("Update check failed (HTTP $code).")
            }

            val body = conn.inputStream.bufferedReader().use { it.readText() }
            val json = JSONObject(body)
            val tag = json.optString("tag_name").orEmpty()
            val version = tag.trimStart('v', 'V')
            val notes = json.optString("body").orEmpty()

            val assets = json.optJSONArray("assets")
            var apkUrl: String? = null
            var size = 0L
            if (assets != null) {
                for (i in 0 until assets.length()) {
                    val a = assets.getJSONObject(i)
                    val name = a.optString("name").orEmpty()
                    if (name.endsWith(".apk", ignoreCase = true)) {
                        apkUrl = a.optString("browser_download_url")
                        size = a.optLong("size")
                        break
                    }
                }
            }

            when {
                version.isBlank() -> CheckResult.Failed("The release has no version tag.")
                !isNewer(version, currentVersion) -> CheckResult.UpToDate
                apkUrl.isNullOrBlank() -> CheckResult.Failed(
                    "v$version is out, but that release has no APK attached.",
                )
                else -> CheckResult.Available(
                    AppRelease(tag = tag, version = version, notes = notes, apkUrl = apkUrl, sizeBytes = size),
                )
            }
        } catch (e: Exception) {
            CheckResult.Failed(e.message ?: "Update check failed.")
        } finally {
            conn?.disconnect()
        }
    }

    /** SHA-256 published in the release notes, or null when the notes carry none. */
    fun parseSha256(notes: String): String? =
        Regex("\\b[0-9a-fA-F]{64}\\b").find(notes)?.value?.lowercase()

    /** Hex SHA-256 of bytes, dependency-free via MessageDigest. */
    fun sha256Hex(bytes: ByteArray): String {
        val digest = MessageDigest.getInstance("SHA-256").digest(bytes)
        return digest.joinToString("") { "%02x".format(it) }
    }

    /**
     * Downloads the APK into cache so the system installer can read it.
     *
     * Fail-closed: the release notes must publish the APK's SHA-256 (the
     * release checklist writes it there) and the bytes must match it.
     * A missing or mismatched digest deletes the file and throws — the
     * caller surfaces the message instead of offering Install.
     */
    suspend fun download(context: Context, release: AppRelease): File = withContext(Dispatchers.IO) {
        val dir = File(context.cacheDir, "updates").apply { mkdirs() }
        // Clear older builds so a stale APK can never be installed.
        dir.listFiles()?.forEach { it.delete() }
        val out = File(dir, "TacomaTowMonitor-${release.version}.apk")

        val conn = (URL(release.apkUrl).openConnection() as HttpURLConnection).apply {
            connectTimeout = 15_000
            readTimeout = 60_000
            setRequestProperty("User-Agent", "TacomaTowMonitor")
            instanceFollowRedirects = true
        }
        val digest = MessageDigest.getInstance("SHA-256")
        try {
            if (conn.responseCode != 200) {
                throw IllegalStateException("Download failed (HTTP ${conn.responseCode}).")
            }
            conn.inputStream.use { input ->
                out.outputStream().use { output ->
                    val buf = ByteArray(64 * 1024)
                    while (true) {
                        val n = input.read(buf)
                        if (n < 0) break
                        digest.update(buf, 0, n)
                        output.write(buf, 0, n)
                    }
                }
            }
        } finally {
            conn.disconnect()
        }
        if (out.length() < 1024) {
            out.delete()
            throw IllegalStateException("Downloaded file looks empty.")
        }
        val expected = parseSha256(release.notes)
        if (expected == null) {
            out.delete()
            throw IllegalStateException(
                "Release v${release.version} has no SHA-256 in its notes — " +
                    "refusing to install an unverified APK.",
            )
        }
        val actual = digest.digest().joinToString("") { "%02x".format(it) }
        if (!actual.equals(expected, ignoreCase = true)) {
            out.delete()
            throw IllegalStateException(
                "APK SHA-256 mismatch — refusing to install. " +
                    "Expected $expected, got $actual.",
            )
        }
        out
    }

    /** Hands the APK to the system installer. */
    fun install(context: Context, apk: File) {
        val uri = FileProvider.getUriForFile(context, "${context.packageName}.updates", apk)
        val intent = Intent(Intent.ACTION_VIEW).apply {
            setDataAndType(uri, "application/vnd.android.package-archive")
            addFlags(Intent.FLAG_GRANT_READ_URI_PERMISSION)
            addFlags(Intent.FLAG_ACTIVITY_NEW_TASK)
        }
        context.startActivity(intent)
    }

    /** True when [candidate] is a higher dotted version than [current]. */
    fun isNewer(candidate: String, current: String): Boolean {
        val a = numbers(candidate)
        val b = numbers(current)
        for (i in 0 until maxOf(a.size, b.size)) {
            val x = a.getOrElse(i) { 0 }
            val y = b.getOrElse(i) { 0 }
            if (x != y) return x > y
        }
        return false
    }

    private fun numbers(v: String): List<Int> =
        v.trimStart('v', 'V')
            .substringBefore('-')
            .substringBefore('+')
            .split('.')
            .map { it.trim().toIntOrNull() ?: 0 }
}
