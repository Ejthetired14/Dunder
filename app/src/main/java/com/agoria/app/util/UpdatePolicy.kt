package com.agoria.app.util

import java.io.File
import java.io.FileInputStream
import java.net.URI
import java.security.MessageDigest
import java.nio.charset.Charsets

/**
 * SEC-10: pure (no Android classes) rules for the self-updater, so they are unit-testable.
 *
 * - The APK URL from the Pages JSON must be https://github.com/<owner>/<repo>/releases/download/<tag>/<file>.apk
 * - Redirect hops may only go to GitHub's release-asset hosts over https.
 * - The APK must match the SHA-256 published in latest-release.json.
 */
object UpdatePolicy {
    const val MAX_APK_BYTES: Long = 250L * 1024L * 1024L
    const val MAX_REDIRECTS: Int = 5

    /** Updates without a published sha256 are refused. Flip only deliberately. */
    const val REQUIRE_SHA256: Boolean = true

    private val DOWNLOAD_HOSTS = setOf(
        "github.com",
        "objects.githubusercontent.com",
        "github-releases.githubusercontent.com",
        "release-assets.githubusercontent.com",
    )
    private val SHA256_REGEX = Regex("^[0-9a-fA-F]{64}$")
    private val SAFE_SEGMENT = Regex("^[A-Za-z0-9._-]+$")
    private val UNSAFE_FILE_CHARS = Regex("[^A-Za-z0-9._-]")

    private fun parse(url: String): URI? = try {
        URI(url)
    } catch (e: Exception) {
        null
    }

    private fun isPlainHttps(uri: URI): Boolean =
        "https".equals(uri.scheme, ignoreCase = true) &&
            uri.userInfo == null &&
            (uri.port == -1 || uri.port == 443) &&
            !uri.host.isNullOrEmpty()

    private fun isSafeSegment(s: String): Boolean =
        s.isNotEmpty() && s != "." && s != ".." && SAFE_SEGMENT.matches(s)

    /** Exactly https://github.com/<owner>/<repo>/releases/download/<tag>/<file>.apk, nothing else. */
    fun isTrustedReleaseUrl(url: String, owner: String, repo: String): Boolean {
        val uri = parse(url) ?: return false
        if (!isPlainHttps(uri)) return false
        if (!"github.com".equals(uri.host, ignoreCase = true)) return false
        if (uri.rawQuery != null || uri.rawFragment != null) return false
        val path = uri.rawPath ?: return false
        if (path.contains('%') || path.contains('\\')) return false
        val parts = path.split('/')
        if (parts.size != 7 || parts[0].isNotEmpty()) return false
        if (!parts[1].equals(owner, ignoreCase = true) || !parts[2].equals(repo, ignoreCase = true)) return false
        if (parts[3] != "releases" || parts[4] != "download") return false
        return isSafeSegment(parts[5]) && isSafeSegment(parts[6]) &&
            parts[6].endsWith(".apk", ignoreCase = true)
    }

    /** Allowed for every download hop (first request and each redirect). */
    fun isAllowedDownloadUrl(url: String): Boolean {
        val uri = parse(url) ?: return false
        if (!isPlainHttps(uri)) return false
        return uri.host.lowercase() in DOWNLOAD_HOSTS
    }

    /** Returns the lowercase 64-hex digest, or null when [raw] is missing or malformed. */
    fun normalizeSha256(raw: String?): String? {
        val t = raw?.trim() ?: return null
        return if (SHA256_REGEX.matches(t)) t.lowercase() else null
    }

    fun sha256Hex(file: File): String {
        val md = MessageDigest.getInstance("SHA-256")
        FileInputStream(file).use { input ->
            val buf = ByteArray(8192)
            while (true) {
                val n = input.read(buf)
                if (n < 0) break
                md.update(buf, 0, n)
            }
        }
        return md.digest().joinToString("") { "%02x".format(it) }
    }

    fun fileMatchesSha256(file: File, expected: String): Boolean {
        val want = normalizeSha256(expected) ?: return false
        val got = sha256Hex(file)
        return MessageDigest.isEqual(
            want.toByteArray(Charsets.US_ASCII),
            got.toByteArray(Charsets.US_ASCII),
        )
    }

    /** Tag name -> safe file name part (no separators, no leading dots). */
    fun safeFileComponent(raw: String): String {
        val cleaned = raw.replace(UNSAFE_FILE_CHARS, "_").trim('.', '_').take(64)
        return if (cleaned.isEmpty()) "release" else cleaned
    }

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

    /** Newer version name wins; with an equal name a higher versionCode counts as newer. */
    fun isNewer(remoteName: String, remoteCode: Int, installedName: String, installedCode: Int): Boolean {
        val c = compareVersions(remoteName, installedName)
        return if (c != 0) c > 0 else remoteCode > installedCode
    }
}