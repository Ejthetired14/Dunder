package com.agoria.app.util

import android.app.Activity
import android.app.DownloadManager
import android.content.Context
import android.content.Intent
import android.content.pm.PackageManager
import android.net.Uri
import android.os.Build
import android.os.Environment
import androidx.core.content.FileProvider
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext
import org.json.JSONArray
import org.json.JSONObject
import java.io.File
import java.io.FileOutputStream
import java.net.HttpURLConnection
import java.net.URL

/**
 * Manages checking for updates from GitHub Releases and installing APKs.
 */
class UpdateManager(
    private val context: Context,
    private val scope: CoroutineScope,
    private val repoOwner: String = "Ejthetired14",
    private val repoName: String = "Dunder",
) {

    private val currentVersionName: String
        get() = try {
            context.packageManager.getPackageInfo(context.packageName, 0).versionName ?: "1.0.0"
        } catch (e: PackageManager.NameNotFoundException) {
            "1.0.0"
        }

    private val currentVersionCode: Int
        get() = try {
            context.packageManager.getPackageInfo(context.packageName, 0).versionCode
        } catch (e: PackageManager.NameNotFoundException) {
            1
        }

    data class ReleaseInfo(
        val tagName: String,
        val versionName: String,
        val versionCode: Int,
        val releaseNotes: String,
        val apkUrl: String,
        val publishedAt: String,
    ) {
        val isNewer: Boolean
            get() = versionCode > currentVersionCode
    }

    /**
     * Checks GitHub Releases for a newer version.
     * Returns ReleaseInfo if a newer version is available, null otherwise.
     */
    fun checkForUpdates(onResult: (ReleaseInfo?) -> Unit) {
        scope.launch {
            val release = withContext(Dispatchers.IO) { fetchLatestRelease() }
            withContext(Dispatchers.Main) { onResult(release) }
        }
    }

    private suspend fun fetchLatestRelease(): ReleaseInfo? {
        val url = URL("https://api.github.com/repos/$repoOwner/$repoName/releases/latest")
        val connection = url.openConnection() as HttpURLConnection
        connection.requestMethod = "GET"
        connection.connectTimeout = 10_000
        connection.readTimeout = 10_000
        connection.setRequestProperty("Accept", "application/vnd.github.v3+json")
        connection.setRequestProperty("User-Agent", "Dunder-Android")

        try {
            if (connection.responseCode != 200) return null
            val response = connection.inputStream.bufferedReader().readText()
            val json = JSONObject(response)

            val tagName = json.optString("tag_name", "")
            val releaseNotes = json.optString("body", "No release notes.")
            val publishedAt = json.optString("published_at", "")

            // Parse version from tag (e.g., "v1.0.9" -> 1.0.9)
            val versionName = tagName.removePrefix("v")
            val versionCode = parseVersionCode(versionName)

            // Find APK asset
            val assets = json.getJSONArray("assets")
            var apkUrl = ""
            for (i in 0 until assets.length()) {
                val asset = assets.getJSONObject(i)
                val name = asset.optString("name", "")
                if (name.endsWith(".apk")) {
                    apkUrl = asset.optString("browser_download_url", "")
                    break
                }
            }

            if (apkUrl.isEmpty()) return null

            return ReleaseInfo(tagName, versionName, versionCode, releaseNotes, apkUrl, publishedAt)
        } catch (e: Exception) {
            e.printStackTrace()
            return null
        } finally {
            connection.disconnect()
        }
    }

    private fun parseVersionCode(versionName: String): Int {
        // Convert "1.0.9" -> 1009009 or similar
        val parts = versionName.split(".")
        var code = 0
        for ((index, part) in parts.withIndex()) {
            val value = part.toIntOrNull() ?: 0
            code = code * 1000 + value // 1.0.9 -> 1000009
        }
        return code
    }

    /**
     * Downloads and installs the APK.
     * Requires REQUEST_INSTALL_PACKAGES permission.
     */
    fun downloadAndInstall(release: ReleaseInfo, onProgress: (Int) -> Unit, onComplete: (Boolean) -> Unit) {
        scope.launch {
            val success = withContext(Dispatchers.IO) {
                downloadApk(release.apkUrl, release.tagName, onProgress)
            }
            withContext(Dispatchers.Main) { onComplete(success) }
        }
    }

    private fun downloadApk(urlString: String, tagName: String, onProgress: (Int) -> Unit): Boolean {
        val url = URL(urlString)
        val connection = url.openConnection() as HttpURLConnection
        connection.requestMethod = "GET"
        connection.connectTimeout = 30_000
        connection.readTimeout = 30_000
        connection.instanceFollowRedirects = true

        try {
            if (connection.responseCode != 200) return false

            val totalSize = connection.contentLength
            val input = connection.inputStream

            val downloadsDir = if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.Q) {
                context.getExternalFilesDir(Environment.DIRECTORY_DOWNLOADS)
            } else {
                context.getExternalFilesDir(null)
            } ?: return false

            val apkFile = File(downloadsDir, "dunder-$tagName.apk")
            val output = FileOutputStream(apkFile)

            val buffer = ByteArray(8192)
            var downloaded = 0
            var len: Int

            while (input.read(buffer).also { len = it } != -1) {
                output.write(buffer, 0, len)
                downloaded += len
                if (totalSize > 0) {
                    val progress = (downloaded * 100 / totalSize)
                    onProgress(progress)
                }
            }

            output.flush()
            output.close()
            input.close()

            // Install APK
            return installApk(apkFile)

        } catch (e: Exception) {
            e.printStackTrace()
            return false
        } finally {
            connection.disconnect()
        }
    }

    private fun installApk(apkFile: File): Boolean {
        return try {
            val uri = if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.N) {
                FileProvider.getUriForFile(
                    context,
                    "${context.packageName}.fileprovider",
                    apkFile
                )
            } else {
                Uri.fromFile(apkFile)
            }

            val intent = Intent(Intent.ACTION_INSTALL_PACKAGE).apply {
                data = uri
                type = "application/vnd.android.package-archive"
                addFlags(Intent.FLAG_GRANT_READ_URI_PERMISSION)
                putExtra(Intent.EXTRA_INSTALLER_PACKAGE_NAME, context.packageName)
            }

            // Check if we can start the activity
            if (intent.resolveActivity(context.packageManager) != null) {
                if (context is Activity) {
                    (context as Activity).startActivity(intent)
                } else {
                    intent.addFlags(Intent.FLAG_ACTIVITY_NEW_TASK)
                    context.startActivity(intent)
                }
                true
            } else {
                false
            }
        } catch (e: Exception) {
            e.printStackTrace()
            false
        }
    }
}