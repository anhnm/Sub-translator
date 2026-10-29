package com.subtranslator

import android.content.Context
import android.content.Intent
import android.net.Uri
import android.provider.Settings
import androidx.core.content.FileProvider
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext
import org.json.JSONObject
import java.io.File
import java.io.IOException
import java.net.HttpURLConnection
import java.net.URL

data class UpdateInfo(
    val versionCode: Int,
    val versionName: String,
    val notes: String,
    val apkUrl: String,
)

/** Self-update from the latest GitHub release of [BuildConfig.UPDATE_REPO]. */
class Updater(private val context: Context) {

    private val apkFile get() = File(context.cacheDir, "updates/SubDich.apk")

    /** Returns the latest release if it is newer than the installed app, otherwise null. */
    suspend fun check(): UpdateInfo? = withContext(Dispatchers.IO) {
        val conn = open("https://api.github.com/repos/${BuildConfig.UPDATE_REPO}/releases/latest")
        conn.setRequestProperty("Accept", "application/vnd.github+json")
        val json = try {
            if (conn.responseCode == HttpURLConnection.HTTP_NOT_FOUND) return@withContext null
            if (conn.responseCode != HttpURLConnection.HTTP_OK) throw IOException("HTTP ${conn.responseCode}")
            JSONObject(conn.inputStream.bufferedReader().use { it.readText() })
        } finally {
            conn.disconnect()
        }

        // Tags look like "v1.<build number>"; the build number is the versionCode.
        val tag = json.optString("tag_name")
        val code = tag.substringAfterLast('.').toIntOrNull() ?: return@withContext null
        if (code <= BuildConfig.VERSION_CODE) return@withContext null

        val assets = json.optJSONArray("assets") ?: return@withContext null
        val apkUrl = (0 until assets.length()).map { assets.getJSONObject(it) }
            .firstOrNull { it.optString("name").endsWith(".apk") }
            ?.optString("browser_download_url") ?: return@withContext null

        val notes = json.optString("body").lines()
            .filterNot { it.startsWith("Co-Authored-By") || it.startsWith("Claude-Session") }
            .joinToString("\n").trim()
        UpdateInfo(code, tag.removePrefix("v"), notes, apkUrl)
    }

    /** Downloads the APK; [onProgress] gets 0..100. */
    suspend fun download(info: UpdateInfo, onProgress: (Int) -> Unit): File =
        withContext(Dispatchers.IO) {
            val file = apkFile
            file.parentFile?.mkdirs()
            val conn = open(info.apkUrl)
            try {
                if (conn.responseCode != HttpURLConnection.HTTP_OK) throw IOException("HTTP ${conn.responseCode}")
                val total = conn.contentLengthLong
                conn.inputStream.use { input ->
                    file.outputStream().use { out ->
                        val buf = ByteArray(64 * 1024)
                        var done = 0L
                        var last = -1
                        while (true) {
                            val n = input.read(buf)
                            if (n < 0) break
                            out.write(buf, 0, n)
                            done += n
                            val p = if (total > 0) (done * 100 / total).toInt() else 0
                            if (p != last) {
                                last = p
                                onProgress(p)
                            }
                        }
                    }
                }
            } finally {
                conn.disconnect()
            }
            file
        }

    fun canInstall() = context.packageManager.canRequestPackageInstalls()

    /** Opens the system screen where the user allows this app to install updates. */
    fun requestInstallPermission() {
        context.startActivity(
            Intent(Settings.ACTION_MANAGE_UNKNOWN_APP_SOURCES, Uri.parse("package:${context.packageName}"))
                .addFlags(Intent.FLAG_ACTIVITY_NEW_TASK)
        )
    }

    fun install(file: File) {
        val uri = FileProvider.getUriForFile(context, "${context.packageName}.fileprovider", file)
        context.startActivity(
            Intent(Intent.ACTION_VIEW)
                .setDataAndType(uri, "application/vnd.android.package-archive")
                .addFlags(Intent.FLAG_GRANT_READ_URI_PERMISSION or Intent.FLAG_ACTIVITY_NEW_TASK)
        )
    }

    private fun open(url: String) = (URL(url).openConnection() as HttpURLConnection).apply {
        connectTimeout = 15_000
        readTimeout = 30_000
        instanceFollowRedirects = true
    }
}
