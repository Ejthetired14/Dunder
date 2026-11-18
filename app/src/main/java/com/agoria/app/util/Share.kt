package com.agoria.app.util

import android.content.Context
import android.content.Intent
import android.net.Uri
import android.os.Build
import android.os.Environment
import android.provider.MediaStore
import androidx.core.content.FileProvider
import java.io.File
import java.io.FileInputStream

object Share {

    fun shareFile(
        context: Context,
        file: File,
        mime: String,
        title: String = "Share with",
    ) {
        if (!file.exists()) return
        val authority = "${context.packageName}.fileprovider"
        val uri = FileProvider.getUriForFile(context, authority, file)
        val intent = Intent(Intent.ACTION_SEND).apply {
            type = mime
            putExtra(Intent.EXTRA_STREAM, uri)
            addFlags(Intent.FLAG_GRANT_READ_URI_PERMISSION)
            addFlags(Intent.FLAG_ACTIVITY_NEW_TASK)
        }
        context.startActivity(Intent.createChooser(intent, title))
    }

    fun shareZip(context: Context, file: File) =
        shareFile(context, file, "application/zip", "Share build ZIP")

    /**
     * Copies [file] into the public Downloads collection using MediaStore on Android 10+,
     * or directly into Environment.DIRECTORY_DOWNLOADS on older versions. Returns true on success.
     */
    fun saveToDownloads(context: Context, file: File, displayName: String = file.name): Boolean {
        if (!file.exists()) return false
        return try {
            if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.Q) {
                val values = android.content.ContentValues().apply {
                    put(MediaStore.Downloads.DISPLAY_NAME, displayName)
                    put(MediaStore.Downloads.MIME_TYPE, "application/zip")
                    put(MediaStore.Downloads.RELATIVE_PATH, Environment.DIRECTORY_DOWNLOADS)
                    put(MediaStore.Downloads.IS_PENDING, 1)
                }
                val resolver = context.contentResolver
                val uri = resolver.insert(MediaStore.Downloads.EXTERNAL_CONTENT_URI, values) ?: return false
                resolver.openOutputStream(uri)?.use { out ->
                    FileInputStream(file).use { input -> input.copyTo(out) }
                }
                values.clear()
                values.put(MediaStore.Downloads.IS_PENDING, 0)
                resolver.update(uri, values, null, null)
                true
            } else {
                @Suppress("DEPRECATION")
                val downloads = Environment.getExternalStoragePublicDirectory(Environment.DIRECTORY_DOWNLOADS)
                downloads.mkdirs()
                val dest = File(downloads, displayName)
                FileInputStream(file).use { input -> dest.outputStream().use { out -> input.copyTo(out) } }
                true
            }
        } catch (e: Exception) {
            e.printStackTrace()
            false
        }
    }

    fun shareJson(context: Context, file: File) =
        shareFile(context, file, "application/json", "Share Dunder data")

    fun shareText(context: Context, text: String, title: String = "Share with") {
        val intent = Intent(Intent.ACTION_SEND).apply {
            type = "text/plain"
            putExtra(Intent.EXTRA_TEXT, text)
            addFlags(Intent.FLAG_ACTIVITY_NEW_TASK)
        }
        context.startActivity(Intent.createChooser(intent, title))
    }
}