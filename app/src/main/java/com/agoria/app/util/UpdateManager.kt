package com.agoria.app.util

import android.content.ActivityNotFoundException
import android.content.ClipData
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
import java.io.IOException
import java.net.HttpURLConnection
import java.net.URL

/**
 * Manages checking for updates from GitHub Pages / Releases and installing APKs.
 *
 * SEC-10: the APK URL must be a GitHub release asset of this repo (see [UpdatePolicy]), every
 * redirect hop is re-checked, the download goes to a .part file, its SHA-256 must match the one
 * published in latest-release.json, and partial/mismatching files are deleted.
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
        val sha256: String? = null,
    )

    companion object {
        private const val TAG = "UpdateManager"
        private const val PAGES_URL = "https://ejthetired14.github.io/Dunder/latest-release.json"

        fun compareVersions(v1: String, v2: String): Int = UpdatePolicy.compareVersions(v1, v2)
    }

    fun isNewerRelease(release: ReleaseInfo): Boolean {
        val result = UpdatePolicy.isNewer(
            release.versionName, release.versionCode, currentVersionName, currentVersionCode,
        )
        Log.d(TAG, "isNewerRelease: installed=$currentVersionName (code=$currentVersionCode), remote=${release.versionName} (code=${release.versionCode}), result=$result")
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
                val response = connection.inputStream.bufferedReader().use { it.readText() }
                return parsePagesJson(JSONObject(response))
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
        val sha256 = UpdatePolicy.normalizeSha256(json.optString("sha256", ""))

        if (apkUrl.isEmpty()) {
            Log.w(TAG, "parsePagesJson: apkUrl is empty")
            return null
        }
        if (!UpdatePolicy.isTrustedReleaseUrl(apkUrl, repoOwner, repoName)) {
            Log.w(TAG, "parsePagesJson: refusing untrusted apkUrl")
            return null
        }
        if (sha256 == null && UpdatePolicy.REQUIRE_SHA256) {
            Log.w(TAG, "parsePagesJson: refusing release without a valid sha256")
            return null
        }

        Log.d(TAG, "parsePagesJson: versionName=$versionName, versionCode=$versionCode")
        return ReleaseInfo(tagName, versionName, versionCode, releaseNotes, apkUrl, publishedAt, sha256)
    }

    /**
     * Downloads, verifies and installs the APK.
     * Requires REQUEST_INSTALL_PACKAGES permission.
     */
    fun downloadAndInstall(release: ReleaseInfo, onProgress: (Int) -> Unit, onComplete: (Boolean) -> Unit) {
        scope.launch {
            val success = withContext(Dispatchers.IO) { downloadApk(release, onProgress) }
            withContext(Dispatchers.Main) { onComplete(success) }
        }
    }

    /** Follows redirects by hand so every hop is checked. Returns a 200 connection or null. */
    private fun openFollowingRedirects(startUrl: String): HttpURLConnection? {
        var current = URL(startUrl)
        repeat(UpdatePolicy.MAX_REDIRECTS + 1) {
            if (!UpdatePolicy.isAllowedDownloadUrl(current.toString())) {
                Log.w(TAG, "openFollowingRedirects: host not allowed: ${current.host}")
                return null
            }
            val c = current.openConnection() as HttpURLConnection
            c.requestMethod = "GET"
            c.connectTimeout = 30_000
            c.readTimeout = 30_000
            c.instanceFollowRedirects = false
            c.setRequestProperty("User-Agent", "Dunder-Android")
            val code = c.responseCode
            when (code) {
                200 -> return c
                301, 302, 303, 307, 308 -> {
                    val location = c.getHeaderField("Location")
                    c.disconnect()
                    if (location.isNullOrBlank()) return null
                    current = try {
                        URL(current, location)
                    } catch (e: Exception) {
                        return null
                    }
                }
                else -> {
                    Log.w(TAG, "openFollowingRedirects: HTTP $code")
                    c.disconnect()
                    return null
                }
            }
        }
        return null
    }

    private fun downloadApk(release: ReleaseInfo, onProgress: (Int) -> Unit): Boolean {
        if (!UpdatePolicy.isTrustedReleaseUrl(release.apkUrl, repoOwner, repoName)) {
            Log.w(TAG, "downloadApk: refusing untrusted apkUrl")
            return false
        }
        val expectedSha = release.sha256
        if (expectedSha == null && UpdatePolicy.REQUIRE_SHA256) {
            Log.w(TAG, "downloadApk: no sha256 for this release")
            return false
        }

        val downloadsDir = if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.Q) {
            context.getExternalFilesDir(Environment.DIRECTORY_DOWNLOADS)
        } else {
            context.getExternalFilesDir(null)
        } ?: return false

        // Remove leftovers from earlier attempts or older versions.
        downloadsDir.listFiles { f -> f.name.startsWith("dunder-") && (f.name.endsWith(".apk") || f.name.endsWith(".part")) }
            ?.forEach { it.delete() }

        val safeTag = UpdatePolicy.safeFileComponent(release.tagName)
        val partFile = File(downloadsDir, "dunder-$safeTag.apk.part")
        val apkFile = File(downloadsDir, "dunder-$safeTag.apk")
        var connection: HttpURLConnection? = null

        try {
            connection = openFollowingRedirects(release.apkUrl) ?: return false
            val totalSize = connection.contentLengthLong
            if (totalSize > UpdatePolicy.MAX_APK_BYTES) {
                Log.w(TAG, "downloadApk: APK larger than allowed")
                return false
            }

            connection.inputStream.use { input ->
                FileOutputStream(partFile).use { output ->
                    val buffer = ByteArray(8192)
                    var downloaded = 0L
                    var lastProgress = -1
                    while (true) {
                        val len = input.read(buffer)
                        if (len == -1) break
                        downloaded += len
                        if (downloaded > UpdatePolicy.MAX_APK_BYTES) throw IOException("APK larger than allowed size")
                        output.write(buffer, 0, len)
                        if (totalSize > 0) {
                            val progress = (downloaded * 100 / totalSize).toInt()
                            if (progress != lastProgress) {
                                lastProgress = progress
                                onProgress(progress)
                            }
                        }
                    }
                    output.flush()
                }
            }

            if (totalSize > 0 && partFile.length() != totalSize) {
                Log.w(TAG, "downloadApk: truncated download")
                return false
            }
            if (expectedSha != null && !UpdatePolicy.fileMatchesSha256(partFile, expectedSha)) {
                Log.w(TAG, "downloadApk: SHA-256 mismatch, refusing to install")
                return false
            }
            if (apkFile.exists()) apkFile.delete()
            if (!partFile.renameTo(apkFile)) {
                Log.w(TAG, "downloadApk: could not finalize file")
                return false
            }

            return installApk(apkFile)
        } catch (e: Exception) {
            Log.e(TAG, "downloadApk error", e)
            return false
        } finally {
            connection?.disconnect()
            partFile.delete() // no-op after a successful rename; removes partial/mismatching data otherwise
        }
    }

    private fun installApk(apkFile: File): Boolean {
        return try {
            Log.d(TAG, "installApk: file=${apkFile.absolutePath}, exists=${apkFile.exists()}, length=${apkFile.length()}")
            if (!apkFile.exists() || apkFile.length() == 0L) {
                Log.e(TAG, "installApk: APK file missing or empty")
                return false
            }

            if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.O &&
                !context.packageManager.canRequestPackageInstalls()
            ) {
                // Nothing installs until the user turns on "Allow from this source", and that
                // screen is easy to miss, so open it instead of finishing with no reaction.
                Log.w(TAG, "installApk: install-packages permission is not granted")
                Notifier.notifyLocal(
                    context,
                    "Allow Dunder to install updates",
                    "Turn on \"Allow from this source\" in the screen that just opened, then tap Download & Install again.",
                )
                openInstallPermissionSettings()
                return false
            }

            val uri = FileProvider.getUriForFile(
                context,
                "${context.packageName}.fileprovider",
                apkFile,
            )
            Log.d(TAG, "installApk: uri=$uri")

            // ACTION_VIEW with the APK mime type is what every supported Android version hands to
            // the package installer. This deliberately does not check resolveActivity(): package
            // visibility on Android 11+ hides the installer from us, so that check returned null
            // and the install silently never started after the download finished.
            val viewIntent = Intent(Intent.ACTION_VIEW).apply {
                setDataAndType(uri, "application/vnd.android.package-archive")
                clipData = ClipData.newRawUri("apk", uri)
                addFlags(Intent.FLAG_GRANT_READ_URI_PERMISSION)
                addFlags(Intent.FLAG_ACTIVITY_NEW_TASK)
            }
            try {
                context.startActivity(viewIntent)
                return true
            } catch (e: ActivityNotFoundException) {
                Log.e(TAG, "installApk: no activity handled ACTION_VIEW for the APK", e)
            } catch (e: Exception) {
                Log.e(TAG, "installApk: ACTION_VIEW failed", e)
            }

            // Last resort for devices whose installer only answers the legacy action.
            val legacyIntent = Intent(Intent.ACTION_INSTALL_PACKAGE).apply {
                data = uri
                type = "application/vnd.android.package-archive"
                clipData = ClipData.newRawUri("apk", uri)
                addFlags(Intent.FLAG_GRANT_READ_URI_PERMISSION)
                addFlags(Intent.FLAG_ACTIVITY_NEW_TASK)
            }
            return try {
                context.startActivity(legacyIntent)
                true
            } catch (e: Exception) {
                Log.e(TAG, "installApk: ACTION_INSTALL_PACKAGE failed", e)
                false
            }
        } catch (e: Exception) {
            Log.e(TAG, "installApk error", e)
            false
        }
    }

    private fun openInstallPermissionSettings() {
        try {
            if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.O) {
                context.startActivity(
                    Intent(android.provider.Settings.ACTION_MANAGE_UNKNOWN_APP_SOURCES)
                        .setData(Uri.parse("package:${context.packageName}"))
                        .addFlags(Intent.FLAG_ACTIVITY_NEW_TASK),
                )
            }
        } catch (e: Exception) {
            Log.e(TAG, "openInstallPermissionSettings failed", e)
        }
    }
}