package com.agoria.app.util

import android.app.Activity
import android.app.DownloadManager
import android.content.Context
import android.content.Intent
import android.content.pm.PackageManager
import android.net.Uri
import android.os.Build
import android.os.Environment
import android.util.Log
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
    )

    companion object {
        private const val TAG = "UpdateManager"
        
        fun compareVersions(v1: String, v2: String): Int {
            val parts1 = v1.split(".").map { it.toIntOrNull() ?: 0 }
            val parts2 = v2.split(".").map { it.toIntOrNull() ?: 0 }
            val maxSize = maxOf(parts1.size, parts2.size)
            for (i in 0 until maxSize) {
                val p1 = parts1.getOrElse(i) { 0 }
                val p2 = parts2.getOrElse(i) { 0 }
                if (p1 != p2) return p1.compareTo(p2)
            }
            return 0
        }
    }

    fun isNewerRelease(release: ReleaseInfo): Boolean {
        val result = compareVersions(release.versionName, currentVersionName) > 0
        Log.d(TAG, "isNewerRelease: installed=${currentVersionName} (code=$currentVersionCode), remote=${release.versionName} (code=${release.versionCode}), result=$result")
        return result
    }

    /**
     * Checks GitHub Releases for a newer version.
     * Returns ReleaseInfo if a newer version is available, null otherwise.
     */
    fun checkForUpdates(onResult: (ReleaseInfo?) -> Unit) {
        scope.launch {
            val release = withContext(Dispatchers.IO) { fetchLatestRelease() }
            Log.d(TAG, "checkForUpdates: remoteRelease=${release?.versionName}, installed=$currentVersionName")
            withContext(Dispatchers.Main) { onResult(release) }
        }
    }

    private suspend fun fetchLatestRelease(): ReleaseInfo? {
        // Try "latest" first
        val latestUrl = URL("https://api.github.com/repos/$repoOwner/$repoName/releases/latest")
        var connection = latestUrl.openConnection() as HttpURLConnection
        connection.requestMethod = "GET"
        connection.connectTimeout = 10_000
        connection.readTimeout = 10_000
        connection.setRequestProperty("Accept", "application/vnd.github.v3+json")
        connection.setRequestProperty("User-Agent", "Dunder-Android")

        try {
            if (connection.responseCode == 200) {
                val response = connection.inputStream.bufferedReader().readText()
                val json = JSONObject(response)
                val parsed = parseReleaseJson(json)
                if (parsed != null) return parsed
            }
        } catch (e: Exception) {
            e.printStackTrace()
        } finally {
            connection.disconnect()
        }

        // Fallback: fetch all releases and pick the one with highest version
        return fetchAllReleasesAndPickBest()
    }

    private suspend fun fetchAllReleasesAndPickBest(): ReleaseInfo? {
        val url = URL("https://api.github.com/repos/$repoOwner/$repoName/releases?per_page=20")
        val connection = url.openConnection() as HttpURLConnection
        connection.requestMethod = "GET"
        connection.connectTimeout = 10_000
        connection.readTimeout = 10_000
        connection.setRequestProperty("Accept", "application/vnd.github.v3+json")
        connection.setRequestProperty("User-Agent", "Dunder-Android")

        try {
            if (connection.responseCode != 200) return null
            val response = connection.inputStream.bufferedReader().readText()
            val jsonArray = JSONArray(response)

            var bestRelease: ReleaseInfo? = null

            for (i in 0 until jsonArray.length()) {
                val json = jsonArray.getJSONObject(i)
                val parsed = parseReleaseJson(json)
                if (parsed != null) {
                    if (bestRelease == null || compareVersions(parsed.versionName, bestRelease.versionName) > 0) {
                        bestRelease = parsed
                    }
                }
            }

            return bestRelease
        } catch (e: Exception) {
            e.printStackTrace()
            return null
        } finally {
            connection.disconnect()
        }
    }

    private fun parseReleaseJson(json: JSONObject): ReleaseInfo? {
        val tagName = json.optString("tag_name", "")
        val releaseNotes = json.optString("body", "No release notes.")
        val publishedAt = json.optString("published_at", "")

        val versionName = tagName.removePrefix("v")
        val versionCode = parseVersionCode(versionName)

        val assets = json.optJSONArray("assets")
        var apkUrl = ""
        if (assets != null) {
            for (i in 0 until assets.length()) {
                val asset = assets.getJSONObject(i)
                val name = asset.optString("name", "")
                if (name.endsWith(".apk")) {
                    apkUrl = asset.optString("browser_download_url", "")
                    break
                }
            }
        }

        if (apkUrl.isEmpty()) return null

        Log.d(TAG, "parseReleaseJson: tag=$tagName, versionName=$versionName, versionCode=$versionCode, apkUrl=$apkUrl")
        return ReleaseInfo(tagName, versionName, versionCode, releaseNotes, apkUrl, publishedAt)
    }

    private fun parseVersionCode(versionName: String): Int {
        // Keep for ReleaseInfo.versionCode field, but comparison uses compareVersions
        val parts = versionName.split(".")
        return parts.lastOrNull()?.toIntOrNull() ?: 1
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