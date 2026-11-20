package com.agoria.app.util

import android.app.Activity
import android.app.DownloadManager
import android.content.BroadcastReceiver
import android.content.Context
import android.content.Intent
import android.content.IntentFilter
import android.content.pm.PackageManager
import android.database.Cursor
import android.net.Uri
import android.os.Build
import android.os.Environment
import android.util.Log
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext
import org.json.JSONObject
import java.io.File
import java.io.FileOutputStream
import java.net.HttpURLConnection
import java.net.URL
import java.util.concurrent.CountDownLatch
import java.util.concurrent.TimeUnit
import java.util.concurrent.atomic.AtomicReference

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
     * Downloads and installs the APK using DownloadManager (system service).
     * This is the most reliable method on all Android versions.
     * Requires REQUEST_INSTALL_PACKAGES permission.
     */
    fun downloadAndInstall(release: ReleaseInfo, onProgress: (Int) -> Unit, onComplete: (Boolean, String?) -> Unit) {
        scope.launch {
            // Check permission before attempting download/install
            if (!hasInstallPermission()) {
                withContext(Dispatchers.Main) {
                    onComplete(false, "Install unknown apps permission not granted")
                }
                return@launch
            }
            
            val result = withContext(Dispatchers.IO) {
                downloadAndInstallViaDownloadManager(release.apkUrl, release.tagName, onProgress)
            }
            withContext(Dispatchers.Main) { onComplete(result.success, result.error) }
        }
    }

    /**
     * Checks if the app has permission to install packages (Android 8+).
     */
    fun hasInstallPermission(): Boolean {
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.O) {
            return context.packageManager.canRequestPackageInstalls()
        }
        return true // Pre-O doesn't have this restriction
    }

    private data class DownloadResult(
        val success: Boolean,
        val error: String? = null
    )

    /**
     * Uses DownloadManager to download APK, then installs via intent.
     * DownloadManager handles all storage/permission complexities.
     */
    private fun downloadAndInstallViaDownloadManager(urlString: String, tagName: String, onProgress: (Int) -> Unit): DownloadResult {
        return try {
            val url = URL(urlString)
            val connection = url.openConnection() as HttpURLConnection
            connection.requestMethod = "GET"
            connection.connectTimeout = 30_000
            connection.readTimeout = 30_000
            connection.instanceFollowRedirects = true

            if (connection.responseCode != 200) return DownloadResult(false, "HTTP ${connection.responseCode}")

            val totalSize = connection.contentLength
            val input = connection.inputStream

            // Download to cache directory (works on all Android versions)
            val cacheDir = context.cacheDir
            val apkDir = File(cacheDir, "apks")
            if (!apkDir.exists()) apkDir.mkdirs()
            val apkFile = File(apkDir, "dunder-$tagName.apk")

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

            if (!apkFile.exists() || apkFile.length() == 0L) {
                return DownloadResult(false, "APK download failed - file empty")
            }

            Log.d(TAG, "APK downloaded to: ${apkFile.absolutePath}, size=${apkFile.length()}")

            // Now install using the most reliable method
            return installApkDirect(apkFile)

        } catch (e: Exception) {
            e.printStackTrace()
            return DownloadResult(false, "Download failed: ${e.message}")
        }
    }

    /**
     * Direct install using multiple fallback strategies.
     */
    private fun installApkDirect(apkFile: File): DownloadResult {
        return try {
            val pm = context.packageManager
            
            // Strategy 1: FileProvider + ACTION_VIEW with package installer
            if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.N) {
                val authority = "${context.packageName}.fileprovider"
                val uri = FileProvider.getUriForFile(context, authority, apkFile)
                Log.d(TAG, "Strategy 1: FileProvider URI = $uri")

                // Grant permission to package installer
                context.grantUriPermission("com.android.packageinstaller", uri, Intent.FLAG_GRANT_READ_URI_PERMISSION)
                
                val intent = Intent(Intent.ACTION_VIEW).apply {
                    data = uri
                    type = "application/vnd.android.package-archive"
                    addFlags(Intent.FLAG_GRANT_READ_URI_PERMISSION)
                    setPackage("com.android.packageinstaller")
                }
                
                if (intent.resolveActivity(pm) != null) {
                    if (context is Activity) {
                        (context as Activity).startActivity(intent)
                    } else {
                        intent.addFlags(Intent.FLAG_ACTIVITY_NEW_TASK)
                        context.startActivity(intent)
                    }
                    return DownloadResult(true)
                }
                
                // Try without setPackage
                val intent2 = Intent(Intent.ACTION_VIEW).apply {
                    data = uri
                    type = "application/vnd.android.package-archive"
                    addFlags(Intent.FLAG_GRANT_READ_URI_PERMISSION)
                }
                if (intent2.resolveActivity(pm) != null) {
                    if (context is Activity) {
                        (context as Activity).startActivity(intent2)
                    } else {
                        intent2.addFlags(Intent.FLAG_ACTIVITY_NEW_TASK)
                        context.startActivity(intent2)
                    }
                    return DownloadResult(true)
                }
            }

            // Strategy 2: FileProvider + ACTION_INSTALL_PACKAGE
            if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.N) {
                val authority = "${context.packageName}.fileprovider"
                val uri = FileProvider.getUriForFile(context, authority, apkFile)
                
                val fallbackIntent = Intent(Intent.ACTION_INSTALL_PACKAGE).apply {
                    data = uri
                    type = "application/vnd.android.package-archive"
                    addFlags(Intent.FLAG_GRANT_READ_URI_PERMISSION)
                    putExtra(Intent.EXTRA_INSTALLER_PACKAGE_NAME, context.packageName)
                }
                if (fallbackIntent.resolveActivity(pm) != null) {
                    if (context is Activity) {
                        (context as Activity).startActivity(fallbackIntent)
                    } else {
                        fallbackIntent.addFlags(Intent.FLAG_ACTIVITY_NEW_TASK)
                        context.startActivity(fallbackIntent)
                    }
                    return DownloadResult(true)
                }
            }

            // Strategy 3: Direct file URI (pre-N)
            if (Build.VERSION.SDK_INT < Build.VERSION_CODES.N) {
                val uri = Uri.fromFile(apkFile)
                val intent = Intent(Intent.ACTION_VIEW).apply {
                    data = uri
                    type = "application/vnd.android.package-archive"
                }
                if (intent.resolveActivity(pm) != null) {
                    if (context is Activity) {
                        (context as Activity).startActivity(intent)
                    } else {
                        intent.addFlags(Intent.FLAG_ACTIVITY_NEW_TASK)
                        context.startActivity(intent)
                    }
                    return DownloadResult(true)
                }
            }

            // Debug: log all available installers
            val installers = pm.queryIntentActivities(
                Intent(Intent.ACTION_VIEW).apply {
                    type = "application/vnd.android.package-archive"
                },
                PackageManager.MATCH_DEFAULT_ONLY
            )
            Log.e(TAG, "No installer found. Available: ${installers.size}")
            for (i in installers) {
                Log.e(TAG, "  Installer: ${i.activityInfo.packageName}")
            }

            DownloadResult(false, "No installer found. Enable 'Install unknown apps' for this app in Settings > Apps > Special access.")
        } catch (e: SecurityException) {
            Log.e(TAG, "SecurityException during install", e)
            DownloadResult(false, "Install unknown apps permission not granted")
        } catch (e: Exception) {
            Log.e(TAG, "Install error", e)
            DownloadResult(false, "Install error: ${e.message}")
        }
    }
}