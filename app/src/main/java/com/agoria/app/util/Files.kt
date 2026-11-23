package com.agoria.app.util

import android.content.Context
import java.io.File
import java.io.FileOutputStream
import java.util.zip.ZipEntry
import java.util.zip.ZipOutputStream

object Files {

    fun workspace(context: Context): File {
        val dir = File(context.filesDir, "agent-workspace")
        if (!dir.exists()) dir.mkdirs()
        return dir
    }

    fun agentWorkspace(context: Context, agentId: String): File {
        val dir = File(workspace(context), safeName(agentId))
        if (!dir.exists()) dir.mkdirs()
        return dir
    }

    /** Shared project folder. Every build of the office lands here, not in a per-agent folder. */
    fun projectWorkspace(context: Context, folder: String): File {
        val dir = File(workspace(context), "projects/${safeName(folder)}")
        if (!dir.exists()) dir.mkdirs()
        return dir
    }

    fun projectFiles(root: File): List<File> =
        root.walkTopDown()
            .filter { it.isFile }
            .sortedBy { it.relativeTo(root).path }
            .toList()

    fun relativePath(root: File, file: File): String =
        file.relativeTo(root).path.replace('\\', '/')

    fun exports(context: Context): File {
        val base = context.getExternalFilesDir(null) ?: context.filesDir
        val dir = File(base, "exports")
        if (!dir.exists()) dir.mkdirs()
        return dir
    }

    fun safeName(name: String): String =
        name.map { ch ->
            if (ch.isLetterOrDigit() || ch == '-' || ch == '_' || ch == '.') ch else '-'
        }.joinToString("").ifBlank { "untitled" }.take(80)

    fun writeText(file: File, content: String) {
        file.parentFile?.mkdirs()
        file.writeText(content)
    }

    fun writeBytes(file: File, bytes: ByteArray) {
        file.parentFile?.mkdirs()
        FileOutputStream(file).use { it.write(bytes) }
    }

    fun clear(directory: File) {
        if (!directory.exists()) return
        directory.listFiles()?.forEach { child ->
            if (child.isDirectory) clear(child) else child.delete()
        }
    }

    fun delete(directory: File) {
        if (!directory.exists()) return
        directory.listFiles()?.forEach { child ->
            if (child.isDirectory) delete(child) else child.delete()
        }
        directory.delete()
    }

    fun zipDirectory(source: File, zipFile: File): Int {
        zipFile.parentFile?.mkdirs()
        var count = 0
        ZipOutputStream(FileOutputStream(zipFile)).use { zos ->
            source.walkTopDown().filter { it.isFile }.forEach { file ->
                val relative = source.toPath().relativize(file.toPath()).toString().replace('\\', '/')
                zos.putNextEntry(ZipEntry(relative))
                file.inputStream().use { input -> input.copyTo(zos) }
                zos.closeEntry()
                count++
            }
        }
        return count
    }

    fun readText(file: File): String? =
        try {
            if (file.exists()) file.readText() else null
        } catch (_: Exception) {
            null
        }

    fun listFiles(directory: File): List<File> =
        directory.listFiles()?.sortedBy { it.name } ?: emptyList()

    fun prettySize(bytes: Long): String = when {
        bytes < 1024 -> "$bytes B"
        bytes < 1024 * 1024 -> "%.1f KB".format(bytes / 1024.0)
        else -> "%.1f MB".format(bytes / (1024.0 * 1024.0))
    }
}