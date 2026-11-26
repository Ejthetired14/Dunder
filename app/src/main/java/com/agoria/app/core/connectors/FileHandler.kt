package com.agoria.app.core.connectors

import android.content.Context
import com.agoria.app.data.ConnectorEntity
import com.agoria.app.util.Files
import org.json.JSONObject
import java.io.File

/** Files connector — per-agent workspace with path traversal guards and quota. */

class FileHandler(
    private val context: Context,
) : ConnectorHandler {
    override val connectorId = "files"
    override val description = "Per-agent file workspace. Read, write and list files safely."
    override val risk = "low"

    /** Per-agent total workspace byte quota (5 MiB). Prevents one agent filling the disk. */
    private val MAX_AGENT_WORKSPACE_BYTES = 5L * 1024 * 1024

    override fun describeActions(): String =
        "files actions:\n- list: List files in the workspace.\n- read: Read a file. args {\"path\": \"notes.txt\"}\n- write: Write a file. args {\"path\": \"notes.txt\", \"content\": \"...\"}"

    override fun canonicalAction(action: String): String =
        when (action.trim().lowercase().replace('-', '_').replace(' ', '_')) {
            "list_files", "ls", "dir", "glob", "files" -> "list"
            "cat", "open", "read_file", "show", "view" -> "read"
            "save", "create", "write_file", "create_file", "append", "append_file" -> "write"
            "rm", "remove", "unlink", "delete_file" -> "delete"
            else -> action.trim().lowercase()
        }

    override fun missingConfig(config: JSONObject?): String? = null

    override suspend fun execute(
        connector: ConnectorEntity,
        config: JSONObject?,
        action: String,
        args: JSONObject,
        agentId: String?,
    ): HandlerResult {
        // Each agent gets its own subdirectory under the shared workspace so agents cannot
        // read or overwrite each other's files. When no agentId is available (should not happen
        // in normal operation) fall back to the shared root.
        val root = if (agentId != null) {
            Files.agentWorkspace(context, agentId).canonicalFile
        } else {
            Files.workspace(context).canonicalFile
        }
        // Models answer with "/notes.txt" or "./notes.txt" for the same file, so treat both as
        // relative to the workspace instead of rejecting them as an escape attempt.
        val relative = args.optString("path", "")
            .trim()
            .replace('\\', '/')
            .trimStart('/')
            .removePrefix("./")
        val target = if (relative.isBlank()) root else File(root, relative).canonicalFile
        if (target.canonicalPath != root.canonicalPath && !target.canonicalPath.startsWith(root.canonicalPath + File.separator)) {
            val contents = Files.listFiles(root).take(12).joinToString(", ") {
                if (it.isDirectory) it.name + "/" else it.name
            }
            val workspaceContent = contents.ifBlank { "nothing yet" }
            return HandlerResult(
                false,
                "That path points outside the agent workspace. Give a path relative to it, like \"notes.txt\". The workspace currently holds: $workspaceContent.",
            )
        }
        return when (action) {
            "list" -> {
                val dir = if (target.isDirectory) target else target.parentFile ?: root
                val files = Files.listFiles(dir)
                if (files.isEmpty()) return HandlerResult(true, "Workspace is empty.")
                val sb = StringBuilder("Files in ${dir.name}:\n")
                files.take(40).forEach {
                    sb.append("- ").append(it.name)
                        .append(if (it.isDirectory) "/" else " (${Files.prettySize(it.length())})")
                        .append('\n')
                }
                HandlerResult(true, sb.toString().trim())
            }
            "read" -> {
                if (!target.exists() || target.isDirectory) {
                    val name = relative.substringAfterLast('/')
                    val matches = findByName(root, name)
                    val hint = if (matches.isEmpty()) {
                        ""
                    } else {
                        " Did you mean: ${matches.joinToString(", ")}?"
                    }
                    return HandlerResult(false, "File not found: $relative.$hint")
                }
                val content = Files.readText(target)
                    ?: return HandlerResult(false, "Could not read $relative")
                HandlerResult(true, content.take(20_000))
            }
            "write" -> {
                val content = args.optString("content")
                if (content.isBlank()) return HandlerResult(false, "Missing content")
                // Per-agent quota: cap the total workspace size so one agent cannot fill the disk.
                val currentSize = Files.directorySize(root)
                val newBytes = content.take(200_000).toByteArray().size.toLong()
                if (currentSize + newBytes > MAX_AGENT_WORKSPACE_BYTES) {
                    return HandlerResult(false, "Workspace quota exceeded (${Files.prettySize(MAX_AGENT_WORKSPACE_BYTES)}).")
                }
                Files.writeText(target, content.take(200_000))
                HandlerResult(true, "Wrote ${content.length} characters to $relative.")
            }
            "delete" -> {
                if (!target.exists()) return HandlerResult(false, "File not found: $relative")
                if (target.isDirectory) return HandlerResult(false, "Cannot delete a directory.")
                target.delete()
                HandlerResult(true, "Deleted $relative.")
            }
            else -> HandlerResult(false, "Unknown files action '$action'")
        }
    }

    private fun findByName(root: File, name: String): List<String> {
        if (name.isBlank()) return emptyList()
        return root.walkTopDown()
            // Bounded walk so a deep or very large workspace cannot hang the connector.
            .take(500)
            .filter { it.isFile && it.name.equals(name, ignoreCase = true) }
            .take(5)
            .map { root.toPath().relativize(it.toPath()).toString().replace('\\', '/') }
            .toList()
    }

    override fun onSuccess(connector: ConnectorEntity) = ConnectorHandler.defaultOnSuccess(connector)
}
