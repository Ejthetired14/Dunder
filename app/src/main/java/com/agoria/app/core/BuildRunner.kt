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
import org.json.JSONArray
import org.json.JSONObject
import java.io.File

class BuildRunner(
    private val repo: Repository,
    private val llm: LlmClient,
    private val context: android.content.Context,
) {

    suspend fun build(agent: AgentEntity, approval: ApprovalEntity): BuildEntity? {
        val details = Json.tryObject(approval.details) ?: JSONObject()
        val requestedName = Json.string(details, "name", approval.title).ifBlank { "untitled-build" }
        val plan = Json.string(details, "plan", approval.summary)
        val description = Json.string(details, "description", approval.summary)

        val root = File(Files.agentWorkspace(context, agent.id), Files.safeName(approval.id))
        Files.clear(root)
        root.mkdirs()

        val generated = requestProject(agent, requestedName, description, plan)
        val files = generated.files
        val finalName = generated.name.ifBlank { requestedName }
        val finalDescription = generated.description.ifBlank { description }

        var written = 0
        val manifestFiles = JSONArray()
        files.forEach { file ->
            val path = file.path.replace('\\', '/').trimStart('/')
            if (path.isBlank() || path.contains("..") || path.startsWith("/")) return@forEach
            val target = File(root, path)
            if (!target.canonicalPath.startsWith(root.canonicalPath)) return@forEach
            Files.writeText(target, file.content.take(30_000))
            written++
            manifestFiles.put(path)
        }

        if (written == 0) {
            writeFallback(root, finalName, finalDescription, plan, approval)
            written = 4
            manifestFiles.put("README.md")
            manifestFiles.put("PLAN.md")
            manifestFiles.put("manifest.json")
            manifestFiles.put("src/main.kt")
        }

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
        Files.writeText(File(root, "manifest.json"), manifest.toString(2))

        val zipFile = File(
            Files.exports(context),
            "agoria-${Files.safeName(finalName)}-${approval.id.take(8)}.zip",
        )
        Files.zipDirectory(root, zipFile)

        val build = repo.addBuild(
            agentId = agent.id,
            approvalId = approval.id,
            name = finalName,
            description = finalDescription,
            relativePath = root.absolutePath,
            zipPath = zipFile.absolutePath,
            fileCount = written,
        )

        repo.addDiary(
            agent.id,
            "build",
            "Built '$finalName': $finalDescription $written files are ready in a downloadable ZIP.",
            5,
        )
        repo.addMessage(
            agent.id,
            "system",
            "Build complete: $finalName ($written files). Download the ZIP from the Builds tab.",
        )
        Notifier.notifyBuild(context, "Build ready: $finalName", "$written files packaged. Open Dunder to download the ZIP.")
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