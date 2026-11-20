package com.agoria.app.util

import android.app.Activity
import android.app.DownloadManager
import android.app.PendingIntent
import android.content.Context
import android.content.Intent
import android.content.pm.PackageInstaller
import android.content.pm.PackageManager
import android.database.Cursor
import android.net.Uri
import android.os.Build
import android.os.ParcelFileDescriptor
import android.util.Log
import androidx.core.content.FileProvider
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext
import org.json.JSONObject
import java.io.File
import java.io.FileInputStream
import java.io.FileOutputStream
import java.io.IOException
import java.io.InputStream
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
     * Uses DownloadManager (system service) to download APK, then copies to cache dir
     * and installs via FileProvider so package installer can read it.
     */
    private fun downloadAndInstallViaDownloadManager(urlString: String, tagName: String, onProgress: (Int) -> Unit): DownloadResult {
        return try {
            val dm = context.getSystemService(Context.DOWNLOAD_SERVICE) as DownloadManager
            
            val request = DownloadManager.Request(Uri.parse(urlString))
                .setTitle("Dunder Update")
                .setDescription("Downloading version $tagName")
                .setNotificationVisibility(DownloadManager.Request.VISIBILITY_VISIBLE_NOTIFY_COMPLETED)
                .setDestinationInExternalFilesDir(context, "apks", "dunder-$tagName.apk")
                .setAllowedOverMetered(true)
                .setAllowedOverRoaming(false)

            val downloadId = dm.enqueue(request)
            Log.d(TAG, "DownloadManager enqueued: id=$downloadId")

            // Wait for download to complete with progress updates
            val query = DownloadManager.Query().setFilterById(downloadId)
            var lastProgress = 0
            var status = DownloadManager.STATUS_RUNNING
            
            while (status == DownloadManager.STATUS_RUNNING || status == DownloadManager.STATUS_PENDING) {
                Thread.sleep(500)
                val cursor = dm.query(query)
                if (cursor.moveToFirst()) {
                    status = cursor.getInt(cursor.getColumnIndexOrThrow(DownloadManager.COLUMN_STATUS))
                    val bytesDownloaded = cursor.getLong(cursor.getColumnIndexOrThrow(DownloadManager.COLUMN_BYTES_DOWNLOADED_SO_FAR))
                    val bytesTotal = cursor.getLong(cursor.getColumnIndexOrThrow(DownloadManager.COLUMN_TOTAL_SIZE_BYTES))
                    if (bytesTotal > 0) {
                        val progress = ((bytesDownloaded * 100) / bytesTotal).toInt()
                        if (progress != lastProgress) {
                            onProgress(progress)
                            lastProgress = progress
                        }
                    }
                }
                cursor.close()
            }

            if (status != DownloadManager.STATUS_SUCCESSFUL) {
                val reason = when (status) {
                    DownloadManager.STATUS_FAILED -> {
                        val errorCursor = dm.query(query)
                        if (errorCursor.moveToFirst()) {
                            val reasonIdx = errorCursor.getColumnIndexOrThrow(DownloadManager.COLUMN_REASON)
                            errorCursor.getInt(reasonIdx).toString()
                        } else {
                            "Unknown failure reason"
                        }
                    }
                    DownloadManager.STATUS_PAUSED -> "Download paused"
                    else -> "Unknown error (status=$status)"
                }
                return DownloadResult(false, "Download failed: $reason")
            }

            // Open the downloaded file and copy to our cache dir for FileProvider access
            val pfd = dm.openDownloadedFile(downloadId)
            val cacheDir = context.cacheDir
            val apkDir = File(cacheDir, "apks")
            if (!apkDir.exists()) apkDir.mkdirs()
            val apkFile = File(apkDir, "dunder-$tagName.apk")
            
            // Copy from ParcelFileDescriptor to our cache file
            val input = ParcelFileDescriptor.AutoCloseInputStream(pfd)
            val output = FileOutputStream(apkFile)
            val buffer = ByteArray(8192)
            var len: Int
            while (input.read(buffer).also { len = it } != -1) {
                output.write(buffer, 0, len)
            }
            output.flush()
            output.close()
            input.close()
            
            Log.d(TAG, "APK copied to cache: ${apkFile.absolutePath}, size=${apkFile.length()}")
            
            if (!apkFile.exists() || apkFile.length() == 0L) {
                return DownloadResult(false, "APK copy failed - file empty")
            }

            // Install via FileProvider URI
            return installApkDirect(apkFile)

        } catch (e: InterruptedException) {
            Thread.currentThread().interrupt()
            return DownloadResult(false, "Download interrupted")
        } catch (e: Exception) {
            e.printStackTrace()
            return DownloadResult(false, "Download failed: ${e.message}")
        }
    }

    /**
     * Direct install using PackageInstaller API (primary) with intent fallbacks.
     * PackageInstaller gives detailed error info and works on API 21+.
     */
    private fun installApkDirect(apkFile: File): DownloadResult {
        return try {
            val pm = context.packageManager
            
            // Get FileProvider URI for our cache file
            val authority = "${context.packageName}.fileprovider"
            val uri = FileProvider.getUriForFile(context, authority, apkFile)
            Log.d(TAG, "installApkDirect: file=${apkFile.absolutePath}, uri=$uri, exists=${apkFile.exists()}, size=${apkFile.length()}")
            
            // Log FileProvider config
            Log.d(TAG, "FileProvider authority: $authority")
            
            // Grant URI permission to ALL potential installers
            val baseIntent = Intent(Intent.ACTION_VIEW).apply {
                type = "application/vnd.android.package-archive"
            }
            val installers = pm.queryIntentActivities(baseIntent, PackageManager.MATCH_DEFAULT_ONLY)
            Log.d(TAG, "Found ${installers.size} installer(s) for ACTION_VIEW")
            for (i in installers) {
                Log.d(TAG, "  Installer: ${i.activityInfo.packageName}")
                try {
                    context.grantUriPermission(i.activityInfo.packageName, uri, Intent.FLAG_GRANT_READ_URI_PERMISSION)
                    Log.d(TAG, "  Granted URI permission to ${i.activityInfo.packageName}")
                } catch (e: Exception) {
                    Log.w(TAG, "  Failed to grant URI permission to ${i.activityInfo.packageName}", e)
                }
            }
            
            // Also grant to package installer as fallback
            try {
                context.grantUriPermission("com.android.packageinstaller", uri, Intent.FLAG_GRANT_READ_URI_PERMISSION)
                Log.d(TAG, "Granted URI permission to com.android.packageinstaller")
            } catch (e: Exception) {
                Log.w(TAG, "Failed to grant URI permission to com.android.packageinstaller", e)
            }
            
            // PRIMARY STRATEGY: PackageInstaller API (API 21+)
            if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.LOLLIPOP) {
                val result = installViaPackageInstaller(apkFile, uri)
                if (result.success) {
                    return result
                }
                Log.w(TAG, "PackageInstaller failed: ${result.error}, falling back to intents")
            }
            
            // FALLBACK 1: Try each installer with ACTION_VIEW
            for (installer in installers) {
                val intent = Intent(Intent.ACTION_VIEW).apply {
                    data = uri
                    type = "application/vnd.android.package-archive"
                    addFlags(Intent.FLAG_GRANT_READ_URI_PERMISSION)
                    addFlags(Intent.FLAG_ACTIVITY_NEW_TASK)
                    setPackage(installer.activityInfo.packageName)
                }
                if (intent.resolveActivity(pm) != null) {
                    Log.d(TAG, "Starting installer (ACTION_VIEW): ${installer.activityInfo.packageName}")
                    context.startActivity(intent)
                    return DownloadResult(true)
                } else {
                    Log.w(TAG, "resolveActivity returned null for ${installer.activityInfo.packageName}")
                }
            }
            
            // FALLBACK 2: ACTION_VIEW without setPackage (system chooser)
            val intent2 = Intent(Intent.ACTION_VIEW).apply {
                data = uri
                type = "application/vnd.android.package-archive"
                addFlags(Intent.FLAG_GRANT_READ_URI_PERMISSION)
                addFlags(Intent.FLAG_ACTIVITY_NEW_TASK)
            }
            if (intent2.resolveActivity(pm) != null) {
                Log.d(TAG, "Starting system chooser for ACTION_VIEW")
                context.startActivity(intent2)
                return DownloadResult(true)
            } else {
                Log.w(TAG, "resolveActivity returned null for system chooser")
            }
            
            // FALLBACK 3: ACTION_INSTALL_PACKAGE
            val fallbackIntent = Intent(Intent.ACTION_INSTALL_PACKAGE).apply {
                data = uri
                type = "application/vnd.android.package-archive"
                addFlags(Intent.FLAG_GRANT_READ_URI_PERMISSION)
                addFlags(Intent.FLAG_ACTIVITY_NEW_TASK)
                putExtra(Intent.EXTRA_INSTALLER_PACKAGE_NAME, context.packageName)
            }
            if (fallbackIntent.resolveActivity(pm) != null) {
                Log.d(TAG, "Starting ACTION_INSTALL_PACKAGE")
                context.startActivity(fallbackIntent)
                return DownloadResult(true)
            } else {
                Log.w(TAG, "resolveActivity returned null for ACTION_INSTALL_PACKAGE")
            }
            
            // If we reach here, no installer could be launched
            Log.e(TAG, "No installer could be launched. Available: ${installers.size}")
            DownloadResult(false, "No installer found. Enable 'Install unknown apps' for this app in Settings > Apps > Special access.")
        } catch (e: SecurityException) {
            Log.e(TAG, "SecurityException during install", e)
            DownloadResult(false, "Install unknown apps permission not granted")
        } catch (e: Exception) {
            Log.e(TAG, "Install error", e)
            DownloadResult(false, "Install error: ${e.message}")
        }
    }

    /**
     * Install using PackageInstaller.Session API (API 21+).
     * This is the most reliable method and provides detailed error info.
     */
    private fun installViaPackageInstaller(apkFile: File, apkUri: Uri): DownloadResult {
        return try {
            val packageInstaller = context.packageManager.packageInstaller
            val sessionParams = PackageInstaller.SessionParams(PackageInstaller.SessionParams.MODE_FULL_INSTALL)
            sessionParams.setAppPackageName(context.packageName)
            
            // Create session
            val sessionId = packageInstaller.createSession(sessionParams)
            val session = packageInstaller.openSession(sessionId)
            Log.d(TAG, "PackageInstaller session created: $sessionId")
            
            try {
                // Open the APK file
                val inputStream: InputStream = if (apkFile.exists()) {
                    FileInputStream(apkFile)
                } else {
                    // Fallback to content resolver for URI
                    context.contentResolver.openInputStream(apkUri) ?: return DownloadResult(false, "Cannot open APK file")
                }
                
                // Write to session
                val outputStream = session.openWrite("apk", 0, -1)
                val buffer = ByteArray(8192)
                var len: Int
                while (inputStream.read(buffer).also { len = it } != -1) {
                    outputStream.write(buffer, 0, len)
                }
                inputStream.close()
                outputStream.close()
                
                // Commit session (install) - use broadcast intent to avoid "Open app" prompt
                val intent = Intent("com.agoria.app.UPDATE_COMPLETE")
                val pendingIntent = PendingIntent.getBroadcast(
                    context, 0, intent,
                    PendingIntent.FLAG_IMMUTABLE or PendingIntent.FLAG_UPDATE_CURRENT
                )
                
                session.commit(pendingIntent.intentSender)
                Log.d(TAG, "PackageInstaller session committed (no restart prompt)")
                
                // Session committed - install will happen asynchronously
                // Return success (the system will handle the install)
                DownloadResult(true)
                
            } finally {
                try {
                    session.close()
                } catch (e: Exception) {
                    Log.w(TAG, "Error closing PackageInstaller session", e)
                }
            }
            
        } catch (e: Exception) {
            Log.e(TAG, "PackageInstaller install failed", e)
            DownloadResult(false, "PackageInstaller failed: ${e.message}")
        }
    }
}