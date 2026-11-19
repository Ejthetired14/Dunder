package com.agoria.app.util

import android.content.ContentValues
import android.content.Context
import android.content.Intent
import android.net.Uri
import android.os.Build
import android.os.Environment
import android.provider.MediaStore
import androidx.core.content.FileProvider
import java.io.File
import java.io.FileInputStream
import java.io.OutputStream

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

    /**
     * Saves a file to the device's Downloads folder using MediaStore API.
     * Works on Android 10+ (API 29+) without requiring storage permissions.
     * Returns the URI of the saved file on success, null on failure.
     */
    fun saveToDownloads(context: Context, file: File, fileName: String = file.name): Uri? {
        return if (!file.exists()) {
            null
        } else {
            try {
                val resolver = context.contentResolver
                val contentValues = ContentValues().apply {
                    put(MediaStore.MediaColumns.DISPLAY_NAME, fileName)
                    put(MediaStore.MediaColumns.MIME_TYPE, "application/json")
                    if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.Q) {
                        put(MediaStore.MediaColumns.RELATIVE_PATH, Environment.DIRECTORY_DOWNLOADS)
                    }
                }

                val uri = resolver.insert(MediaStore.Downloads.EXTERNAL_CONTENT_URI, contentValues) ?: return null

                resolver.openOutputStream(uri).use { outputStream ->
                    if (outputStream != null) {
                        FileInputStream(file).use { inputStream ->
                            inputStream.copyTo(outputStream)
                        }
                    }
                }
                uri
            } catch (e: Exception) {
                e.printStackTrace()
                null
            }
        }
    }
}