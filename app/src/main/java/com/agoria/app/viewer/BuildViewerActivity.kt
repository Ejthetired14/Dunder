package com.agoria.app.viewer

import android.content.Context
import android.content.Intent
import android.graphics.BitmapFactory
import android.graphics.Color
import android.graphics.Typeface
import android.os.Bundle
import android.view.Gravity
import android.view.View
import android.widget.ImageView
import android.widget.LinearLayout
import android.widget.ScrollView
import android.widget.TextView
import androidx.activity.ComponentActivity
import java.io.File
import java.util.zip.ZipFile

/**
 * Reads a finished build without leaving the app.
 *
 * Builds are ZIPs on this device. Sharing them out just to look at a file was the only way to see
 * what an agent built, so this screen lists the archive and shows the selected entry inline.
 */
class BuildViewerActivity : ComponentActivity() {

    private lateinit var archive: File
    private lateinit var list: LinearLayout
    private lateinit var detail: LinearLayout
    private var entries: List<String> = emptyList()

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        archive = File(intent.getStringExtra(EXTRA_PATH).orEmpty())
        val name = intent.getStringExtra(EXTRA_NAME).orEmpty()

        if (!archive.isFile) {
            finish()
            return
        }

        val root = LinearLayout(this).apply {
            orientation = LinearLayout.VERTICAL
            setBackgroundColor(Color.parseColor("#11151C"))
            setPadding(dp(16), dp(16), dp(16), dp(16))
        }

        root.addView(
            TextView(this).apply {
                text = name.ifBlank { archive.name }
                setTextColor(Color.WHITE)
                textSize = 20f
                setTypeface(typeface, Typeface.BOLD)
            },
        )

        entries = try {
            ZipFile(archive).use { zip ->
                zip.entries().toList()
                    .filter { !it.isDirectory }
                    .map { it.name }
                    .sorted()
            }
        } catch (_: Exception) {
            emptyList()
        }

        root.addView(
            TextView(this).apply {
                text = entries.size.toString() + " files"
                setTextColor(Color.parseColor("#8A93A3"))
                textSize = 13f
            },
        )

        list = LinearLayout(this).apply { orientation = LinearLayout.VERTICAL }
        val listScroll = ScrollView(this).apply {
            addView(list)
            layoutParams = LinearLayout.LayoutParams(
                LinearLayout.LayoutParams.MATCH_PARENT,
                0,
                1f,
            )
        }
        root.addView(listScroll)

        detail = LinearLayout(this).apply {
            orientation = LinearLayout.VERTICAL
            setPadding(0, dp(12), 0, 0)
        }
        val detailScroll = ScrollView(this).apply {
            addView(detail)
            layoutParams = LinearLayout.LayoutParams(
                LinearLayout.LayoutParams.MATCH_PARENT,
                0,
                1.4f,
            )
        }
        root.addView(detailScroll)

        setContentView(root)
        renderList()
        entries.firstOrNull { it.endsWith(".md") }?.let { showEntry(it) } ?: entries.firstOrNull()?.let { showEntry(it) }
    }

    private fun renderList() {
        list.removeAllViews()
        if (entries.isEmpty()) {
            list.addView(label("This build has no readable files.", "#8A93A3", 14f))
            return
        }
        entries.forEach { entry ->
            list.addView(
                TextView(this).apply {
                    text = entry
                    setTextColor(Color.parseColor("#D6DCEB"))
                    textSize = 14f
                    setPadding(0, dp(7), 0, dp(7))
                    setOnClickListener { showEntry(entry) }
                },
            )
        }
    }

    private fun showEntry(entry: String) {
        detail.removeAllViews()
        detail.addView(
            TextView(this).apply {
                text = entry
                setTextColor(Color.parseColor("#7FB3FF"))
                textSize = 13f
                setTypeface(typeface, Typeface.BOLD)
                setPadding(0, 0, 0, dp(6))
            },
        )
        val bytes = try {
            ZipFile(archive).use { zip -> zip.getInputStream(zip.getEntry(entry)).use { it.readBytes() } }
        } catch (_: Exception) {
            null
        }
        if (bytes == null) {
            detail.addView(label("Could not read this file.", "#FF8A80", 14f))
            return
        }
        val lower = entry.lowercase()
        when {
            lower.endsWith(".png") || lower.endsWith(".jpg") || lower.endsWith(".jpeg") || lower.endsWith(".webp") -> {
                val bitmap = BitmapFactory.decodeByteArray(bytes, 0, bytes.size)
                if (bitmap == null) {
                    detail.addView(label("This image could not be decoded.", "#FF8A80", 14f))
                } else {
                    detail.addView(
                        ImageView(this).apply {
                            setImageBitmap(bitmap)
                            adjustViewBounds = true
                            layoutParams = LinearLayout.LayoutParams(
                                LinearLayout.LayoutParams.MATCH_PARENT,
                                LinearLayout.LayoutParams.WRAP_CONTENT,
                            )
                        },
                    )
                }
            }
            isText(lower) -> {
                val text = String(bytes, Charsets.UTF_8).take(120_000)
                detail.addView(
                    TextView(this).apply {
                        this.text = text
                        setTextColor(Color.parseColor("#C9D1E1"))
                        textSize = 12f
                        typeface = Typeface.MONOSPACE
                        setTextIsSelectable(true)
                        gravity = Gravity.START
                    },
                )
            }
            else -> {
                detail.addView(
                    label(
                        "Binary file, ${bytes.size} bytes. Save the build if you need it outside the app.",
                        "#8A93A3",
                        14f,
                    ),
                )
            }
        }
    }

    private fun isText(extension: String): Boolean {
        val textExtensions = listOf(
            ".txt", ".md", ".json", ".js", ".jsx", ".ts", ".tsx", ".kt", ".java", ".html", ".htm",
            ".css", ".svg", ".yml", ".yaml", ".toml", ".xml", ".gradle", ".properties", ".sh", ".py",
        )
        return textExtensions.any { extension.endsWith(it) } || !extension.substringAfterLast('/').contains('.')
    }

    private fun label(value: String, color: String, size: Float) = TextView(this).apply {
        text = value
        setTextColor(Color.parseColor(color))
        textSize = size
    }

    private fun dp(value: Int): Int = (value * resources.displayMetrics.density).toInt()

    companion object {
        private const val EXTRA_PATH = "path"
        private const val EXTRA_NAME = "name"

        fun intent(context: Context, zipPath: String, name: String): Intent =
            Intent(context, BuildViewerActivity::class.java)
                .putExtra(EXTRA_PATH, zipPath)
                .putExtra(EXTRA_NAME, name)
    }
}
