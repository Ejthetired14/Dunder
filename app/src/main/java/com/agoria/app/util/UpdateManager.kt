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
        private const val PAGES_URL = "https://ejthetired14.github.io/Dunder/latest-release.json"
        
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
     * Checks GitHub Pages for a newer version.
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
        val url = URL(PAGES_URL)
        val connection = url.openConnection() as HttpURLConnection
        connection.requestMethod = "GET"
        connection.connectTimeout = 15_000
        connection.readTimeout = 15_000
        connection.setRequestProperty("Accept", "application/json")
        connection.setRequestProperty("User-Agent", "Dunder-Android")

        try {
            val responseCode = connection.responseCode
            if (responseCode == 200) {
                val response = connection.inputStream.bufferedReader().readText()
                val json = JSONObject(response)
                return parsePagesJson(json)
            } else {
                Log.w(TAG, "fetchLatestRelease: Pages returned $responseCode")
                return null
            }
        } catch (e: Exception) {
            Log.e(TAG, "fetchLatestRelease error", e)
            return null
        } finally {
            connection.disconnect()
        }
    }

    private fun parsePagesJson(json: JSONObject): ReleaseInfo? {
        val versionName = json.optString("versionName", "")
        val versionCode = json.optInt("versionCode", 1)
        val releaseNotes = json.optString("releaseNotes", "No release notes.")
        val apkUrl = json.optString("apkUrl", "")
        val tagName = json.optString("tagName", "")
        val publishedAt = json.optString("publishedAt", "")

        if (apkUrl.isEmpty()) {
            Log.w(TAG, "parsePagesJson: apkUrl is empty")
            return null
        }

        Log.d(TAG, "parsePagesJson: versionName=$versionName, versionCode=$versionCode, apkUrl=$apkUrl")
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