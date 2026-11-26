package com.agoria.app.core

import com.agoria.app.core.llm.LlmClient
import com.agoria.app.core.llm.LlmMessage
import com.agoria.app.core.llm.LlmRequest
import com.agoria.app.data.AgentEntity
import com.agoria.app.data.ApprovalEntity
import com.agoria.app.data.BuildEntity
import com.agoria.app.data.Repository
import com.agoria.app.util.Files
import com.agoria.app.util.Json
import com.agoria.app.util.Notifier
import com.agoria.app.util.Time
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.isActive
import org.json.JSONArray
import org.json.JSONObject
import java.io.File

sealed interface BuildProgress {
    data class Started(val approvalId: String) : BuildProgress
    data class GeneratingFiles(val approvalId: String) : BuildProgress
    data class WritingFiles(val approvalId: String, val written: Int, val total: Int) : BuildProgress
    data class Zipping(val approvalId: String) : BuildProgress
    data class Completed(val approvalId: String, val build: BuildEntity) : BuildProgress
    data class Failed(val approvalId: String, val error: String) : BuildProgress
}

class BuildRunner(
    private val repo: Repository,
    private val llm: LlmClient,
    private val context: android.content.Context,
) {

    private val _buildProgress = MutableStateFlow<BuildProgress?>(null)
    val buildProgress = _buildProgress.asStateFlow()

    suspend fun build(agent: AgentEntity, approval: ApprovalEntity): BuildEntity? {
        if (!coroutineContext.isActive) return null
        _buildProgress.value = BuildProgress.Started(approval.id)

        val details = Json.tryObject(approval.details) ?: JSONObject()
        val requestedName = Json.string(details, "name", approval.title).ifBlank { "untitled-build" }
        val plan = Json.string(details, "plan", approval.summary)
        val description = Json.string(details, "description", approval.summary)

        // Everything the office builds lives in the shared project folder so tasks, builds and
        // files can be inspected in one place instead of on each agent's own tab.
        val project = repo.ensureProject()
        val projectRoot = Files.projectWorkspace(context, project.folder)
        val root = File(projectRoot, Files.safeName(approval.id))
        Files.clear(root)
        root.mkdirs()

        _buildProgress.value = BuildProgress.GeneratingFiles(approval.id)
        val generated = requestProject(agent, requestedName, description, plan)
        val files = generated.files
        val finalName = generated.name.ifBlank { requestedName }
        val finalDescription = generated.description.ifBlank { description }

        // BUG-9: if the model failed or returned invalid JSON, do not pretend stub files are a
        // successful build. Emit Failed and record the build with status "failed".
        val isFallback = files.isEmpty()
        if (isFallback) {
            _buildProgress.value = BuildProgress.Failed(
                approval.id,
                "Model did not return usable files; generated fallback stubs only.",
            )
        }

        var written = 0
        val manifestFiles = JSONArray()
        _buildProgress.value = BuildProgress.WritingFiles(approval.id, 0, files.size)
        for ((index, file) in files.withIndex()) {
            if (!coroutineContext.isActive) {
                _buildProgress.value = BuildProgress.Failed(approval.id, "Build cancelled.")
                return null
            }
            val path = file.path.replace('\\', '/').trimStart('/')
            if (path.isBlank() || path.contains("..") || path.startsWith("/")) continue
            val target = File(root, path)
            if (!target.canonicalPath.startsWith(root.canonicalPath)) continue
            val content = file.content.take(30_000)
            if (file.content.length > 30_000) {
                android.util.Log.w("BuildRunner", "Truncated file '$path' from ${file.content.length} to 30000 chars")
            }
            Files.writeText(target, content)
            written++
            manifestFiles.put(path)
            _buildProgress.value = BuildProgress.WritingFiles(approval.id, written, files.size)
        }

        if (written == 0 && !isFallback) {
            // Model returned JSON but no usable files — treat as failure, not silent success.
            _buildProgress.value = BuildProgress.Failed(approval.id, "Model returned no writable files.")
            return null
        }

        if (isFallback) {
            writeFallback(root, finalName, finalDescription, plan, approval)
            written = 4
            manifestFiles.put("README.md")
            manifestFiles.put("PLAN.md")
            manifestFiles.put("manifest.json")
            manifestFiles.put("src/main.kt")
        }

        // BUG-9: keep next_steps in the manifest so they are not discarded.
        val manifest = JSONObject()
            .put("name", finalName)
            .put("description", finalDescription)
            .put("agent", agent.name)
            .put("agentId", agent.id)
            .put("approvalId", approval.id)
            .put("createdAt", Time.iso(Time.now()))
            .put("goal", agent.goal)
            .put("plan", plan)
            .put("files", manifestFiles)
            .put("nextSteps", generated.nextSteps)
            .put("fallback", isFallback)
        Files.writeText(File(root, "manifest.json"), manifest.toString(2))

        _buildProgress.value = BuildProgress.Zipping(approval.id)
        val zipFile = File(
            Files.exports(context),
            "agoria-${Files.safeName(finalName)}-${approval.id.take(8)}.zip",
        )
        Files.zipDirectory(root, zipFile)

        val buildStatus = if (isFallback) "fallback" else "ready"
        val build = repo.addBuild(
            agentId = agent.id,
            name = finalName,
            description = finalDescription,
            zipPath = zipFile.absolutePath,
            fileCount = written,
            status = buildStatus,
            projectId = project.id,
        )

        repo.addDiary(
            agent.id,
            "build",
            "Built '$finalName': $finalDescription $written files are ready in a downloadable ZIP." +
                (if (isFallback) " (fallback stubs — model did not produce real files)" else ""),
            5,
        )
        repo.addMessage(
            agent.id,
            "system",
            "Build complete: $finalName ($written files). Open Projects to inspect the files or save the ZIP." +
                (if (isFallback) " These are fallback stubs because the model did not return usable files." else ""),
        )
        Notifier.notifyBuild(context, "Build ready: $finalName", "$written files packaged. Open Dunder to download the ZIP.")

        _buildProgress.value = BuildProgress.Completed(approval.id, build)
        return build
    }

    private data class GeneratedFile(val path: String, val content: String)

    private data class Generated(
        val name: String,
        val description: String,
        val files: List<GeneratedFile>,
        val nextSteps: List<String>,
    )

    private suspend fun requestProject(
        agent: AgentEntity,
        requestedName: String,
        description: String,
        plan: String,
    ): Generated {
        val system = """
            You are the build runner for Dunder, a local autonomous AI office.
            You turn an approved idea into a small, runnable starter project.
            Output valid JSON only. No markdown fences, no commentary.
        """.trimIndent()

        val user = """
            Agent: ${agent.name} (${agent.role})
            Goal: ${agent.goal}
            Project name: $requestedName
            Description: $description
            Plan: $plan

            Return this JSON shape exactly:
            {
              "name": "short project name",
              "description": "one sentence",
              "files": [
                {"path": "README.md", "content": "complete markdown"},
                {"path": "PLAN.md", "content": "complete plan"},
                {"path": "src/main.kt", "content": "complete Kotlin source"}
              ],
              "next_steps": ["one concrete next step", "another"]
            }

            Rules:
            - 3 to 12 files.
            - Every file must contain complete content, not a placeholder.
            - Use relative paths only. No absolute paths, no '..'.
            - Keep each file under 8 KB.
            - Prefer a runnable starter: README.md, PLAN.md, src/main.kt or index.html, and one small test or script.
            - Do not invent credentials or external services.
            - If the plan is vague, build the smallest useful version of the idea.
        """.trimIndent()

        val result = llm.complete(
            LlmRequest(
                system = system,
                messages = listOf(LlmMessage("user", user)),
                temperature = 0.35,
                maxTokens = 4000,
                json = true,
            ),
        )

        if (!result.ok) {
            return Generated(
                name = requestedName,
                description = description,
                files = emptyList(),
                nextSteps = listOf("Review the generated fallback files and replace them with real code."),
            )
        }

        val root = Json.tryObject(result.text)
        if (root == null) {
            return Generated(
                name = requestedName,
                description = description,
                files = emptyList(),
                nextSteps = listOf("The model did not return valid JSON; fallback files were generated."),
            )
        }

        val files = mutableListOf<GeneratedFile>()
        val array = root.optJSONArray("files")
        if (array != null) {
            for (i in 0 until minOf(12, array.length())) {
                val item = array.optJSONObject(i) ?: continue
                val path = item.optString("path")
                val content = item.optString("content")
                if (path.isNotBlank() && content.isNotBlank()) {
                    files += GeneratedFile(path, content)
                }
            }
        }

        val nextSteps = mutableListOf<String>()
        val steps = root.optJSONArray("next_steps")
        if (steps != null) {
            for (i in 0 until steps.length()) {
                val step = steps.optString(i)
                if (step.isNotBlank()) nextSteps += step
            }
        }

        return Generated(
            name = root.optString("name").ifBlank { requestedName },
            description = root.optString("description").ifBlank { description },
            files = files,
            nextSteps = nextSteps,
        )
    }

    private fun writeFallback(
        root: File,
        name: String,
        description: String,
        plan: String,
        approval: ApprovalEntity,
    ) {
        Files.writeText(
            File(root, "README.md"),
            "# $name\n\n$description\n\n" +
                "Generated by $AGENT_NAME inside Dunder after approval ${approval.id.take(8)}.\n\n" +
                "## Run\n\nThis is a starter project. Open `src/main.kt` to begin.\n",
        )
        Files.writeText(
            File(root, "PLAN.md"),
            "# Plan\n\n$plan\n\n## Approval\n\n- Agent: ${approval.agentId}\n- Type: ${approval.type}\n- Risk: ${approval.risk}\n- Summary: ${approval.summary}\n",
        )
        val safeName = name.replace("\"", "'")
        Files.writeText(
            File(root, "src/main.kt"),
            "fun main() {\n" +
                "    println(\"$safeName\")\n" +
                "    println(\"Replace this file with your real implementation.\")\n" +
                "}\n",
        )
        Files.writeText(
            File(root, "manifest.json"),
            JSONObject()
                .put("name", name)
                .put("description", description)
                .put("approvalId", approval.id)
                .put("fallback", true)
                .toString(2),
        )
    }

    private companion object {
        const val AGENT_NAME = "Dunder BuildRunner"
    }
}