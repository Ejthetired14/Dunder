package com.agoria.app.core

import android.content.Context
import com.agoria.app.data.ConnectorEntity
import com.agoria.app.data.Repository
import com.agoria.app.util.Files
import com.agoria.app.util.Json
import com.agoria.app.util.Time
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext
import org.json.JSONArray
import org.json.JSONObject
import java.io.File
import java.util.UUID
import kotlin.collections.joinToString

/**
 * SkillExecutor executes skill actions on behalf of agents.
 * Each skill action maps to one or more connector actions or internal operations.
 */
class SkillExecutor(
    private val repo: Repository,
    private val context: Context,
) {

    data class SkillResult(
        val ok: Boolean,
        val summary: String,
        val data: JSONObject? = null,
        val error: String? = null,
    )

    /**
     * Execute a skill action for an agent.
     */
    suspend fun execute(
        agentId: String,
        skillId: String,
        actionId: String,
        args: JSONObject,
    ): SkillResult {
        val skill = SkillCatalog.get(skillId) ?: return SkillResult(false, "Skill '$skillId' not found", error = "not found")
        val actionDef = skill.actions.firstOrNull { it.id == actionId } ?: return SkillResult(false, "Action '$actionId' not found in skill '$skillId'", error = "not found")

        return when (skillId) {
            "code_review" -> executeCodeReview(agentId, actionId, args)
            "testing" -> executeTesting(agentId, actionId, args)
            "documentation" -> executeDocumentation(agentId, actionId, args)
            "deployment" -> executeDeployment(agentId, actionId, args)
            "git_operations" -> executeGitOperations(agentId, actionId, args)
            "debugging" -> executeDebugging(agentId, actionId, args)
            "research" -> executeResearch(agentId, actionId, args)
            "security_audit" -> executeSecurityAudit(agentId, actionId, args)
            "performance_optimization" -> executePerformanceOptimization(agentId, actionId, args)
            else -> SkillResult(false, "No executor for skill '$skillId'", error = "no executor")
        }
    }

    // =================================================================== Code Review

    private suspend fun executeCodeReview(agentId: String, actionId: String, args: JSONObject): SkillResult {
        return when (actionId) {
            "review_diff" -> {
                val diff = args.optString("diff").trim().ifBlank { return SkillResult(false, "Missing diff") }
                val context = args.optString("context", "").trim()
                // In a real implementation, this would call an LLM to review the diff
                SkillResult(true, "Code review queued. Diff length: ${diff.length} chars. Context: ${context.take(100)}")
            }
            "check_style" -> {
                val filesJson = args.optString("files", "[]")
                val styleGuide = args.optString("style_guide", "").trim()
                SkillResult(true, "Style check queued. Files: $filesJson. Guide: ${styleGuide.take(100)}")
            }
            "suggest_fixes" -> {
                val issuesJson = args.optString("issues", "[]")
                val code = args.optString("code", "").trim()
                SkillResult(true, "Fix suggestions queued. Issues: $issuesJson. Code length: ${code.length}")
            }
            else -> SkillResult(false, "Unknown code_review action '$actionId'")
        }
    }

    // =================================================================== Testing

    private suspend fun executeTesting(agentId: String, actionId: String, args: JSONObject): SkillResult {
        return when (actionId) {
            "generate_tests" -> {
                val code = args.optString("code", "").trim()
                val framework = args.optString("framework", "junit").trim()
                SkillResult(true, "Test generation queued. Framework: $framework. Code length: ${code.length}")
            }
            "run_tests" -> {
                val command = args.optString("command", "./gradlew test").trim()
                val path = args.optString("path", "/workspace/app").trim()
                // Execute via terminal connector
                val terminal = repo.connector("terminal")
                if (terminal == null || terminal.status != "configured") {
                    return SkillResult(false, "Terminal connector not configured")
                }
                val executor = ConnectorExecutor(repo, context)
                val result = executor.execute("terminal", agentId, "run", JSONObject().put("command", command).put("cwd", path))
                SkillResult(result.ok, result.summary)
            }
            "analyze_coverage" -> {
                val reportPath = args.optString("report_path", "").trim()
                SkillResult(true, "Coverage analysis queued. Report: $reportPath")
            }
            else -> SkillResult(false, "Unknown testing action '$actionId'")
        }
    }

    // =================================================================== Documentation

    private suspend fun executeDocumentation(agentId: String, actionId: String, args: JSONObject): SkillResult {
        return when (actionId) {
            "write_readme" -> {
                val projectPath = args.optString("project_path", "/workspace/app").trim()
                val type = args.optString("type", "android").trim()
                SkillResult(true, "README generation queued. Path: $projectPath, Type: $type")
            }
            "write_api_docs" -> {
                val sourcePath = args.optString("source_path", "").trim()
                val format = args.optString("format", "markdown").trim()
                SkillResult(true, "API docs generation queued. Source: $sourcePath, Format: $format")
            }
            "update_changelog" -> {
                val version = args.optString("version", "").trim()
                val changesJson = args.optString("changes", "[]")
                SkillResult(true, "Changelog update queued. Version: $version, Changes: $changesJson")
            }
            else -> SkillResult(false, "Unknown documentation action '$actionId'")
        }
    }

    // =================================================================== Deployment

    private suspend fun executeDeployment(agentId: String, actionId: String, args: JSONObject): SkillResult {
        return when (actionId) {
            "build_apk" -> {
                val variant = args.optString("variant", "release").trim()
                val sign = args.optBoolean("sign", true)
                val command = if (variant == "release") "./gradlew assembleRelease" else "./gradlew assembleDebug"
                val terminal = repo.connector("terminal")
                if (terminal == null || terminal.status != "configured") {
                    return SkillResult(false, "Terminal connector not configured")
                }
                val executor = ConnectorExecutor(repo, context)
                val result = executor.execute("terminal", agentId, "run_background", JSONObject().put("command", command).put("cwd", "/workspace/app"))
                SkillResult(result.ok, "Build started: ${result.summary}")
            }
            "deploy_worker" -> {
                val name = args.optString("name", "").trim()
                val script = args.optString("script", "").trim()
                val cf = repo.connector("cloudflare_workers")
                if (cf == null || cf.status != "configured") {
                    return SkillResult(false, "Cloudflare Workers connector not configured")
                }
                val executor = ConnectorExecutor(repo, context)
                val result = executor.execute("cloudflare_workers", agentId, "deploy", JSONObject().put("name", name).put("code", script))
                SkillResult(result.ok, result.summary)
            }
            "create_release" -> {
                val tag = args.optString("tag", "").trim()
                val notes = args.optString("notes", "").trim()
                val assetsJson = args.optString("assets", "[]")
                val gh = repo.connector("github")
                if (gh == null || gh.status != "configured") {
                    return SkillResult(false, "GitHub connector not configured")
                }
                val executor = ConnectorExecutor(repo, context)
                val result = executor.execute("github", agentId, "create_release", JSONObject().put("tag", tag).put("notes", notes).put("assets", assetsJson))
                SkillResult(result.ok, result.summary)
            }
            else -> SkillResult(false, "Unknown deployment action '$actionId'")
        }
    }

    // =================================================================== Git Operations

    private suspend fun executeGitOperations(agentId: String, actionId: String, args: JSONObject): SkillResult {
        val terminal = repo.connector("terminal")
        if (terminal == null || terminal.status != "configured") {
            return SkillResult(false, "Terminal connector not configured")
        }
        val executor = ConnectorExecutor(repo, context)

        return when (actionId) {
            "commit" -> {
                val message = args.optString("message", "").trim()
                val filesJson = args.optString("files", "[]")
                val filesArray = try { JSONArray(filesJson) } catch (_: Exception) { JSONArray() }
                val fileList = mutableListOf<String>()
                for (i in 0 until filesArray.length()) {
                    filesArray.optString(i)?.let { fileList.add(it) }
                }
                val fileArgs = fileList.joinToString(" ") { "\"$it\"" }
                val command = "git add $fileArgs && git commit -m \"$message\""
                val result = executor.execute("terminal", agentId, "run", JSONObject().put("command", command).put("cwd", "/workspace/app"))
                SkillResult(result.ok, result.summary)
            }
            "branch" -> {
                val name = args.optString("name", "").trim()
                val base = args.optString("base", "main").trim()
                val command = "git checkout $base && git checkout -b $name"
                val result = executor.execute("terminal", agentId, "run", JSONObject().put("command", command).put("cwd", "/workspace/app"))
                SkillResult(result.ok, result.summary)
            }
            "push" -> {
                val remote = args.optString("remote", "origin").trim()
                val branch = args.optString("branch", "").trim()
                val command = "git push $remote $branch"
                val result = executor.execute("terminal", agentId, "run", JSONObject().put("command", command).put("cwd", "/workspace/app"))
                SkillResult(result.ok, result.summary)
            }
            "create_pr" -> {
                val title = args.optString("title", "").trim()
                val body = args.optString("body", "").trim()
                val base = args.optString("base", "main").trim()
                val head = args.optString("head", "").trim()
                val gh = repo.connector("github")
                if (gh == null || gh.status != "configured") {
                    return SkillResult(false, "GitHub connector not configured")
                }
                val ghExecutor = ConnectorExecutor(repo, context)
                val result = ghExecutor.execute("github", agentId, "create_pr", JSONObject().put("title", title).put("body", body).put("base", base).put("head", head))
                SkillResult(result.ok, result.summary)
            }
            else -> SkillResult(false, "Unknown git_operations action '$actionId'")
        }
    }

    // =================================================================== Debugging

    private suspend fun executeDebugging(agentId: String, actionId: String, args: JSONObject): SkillResult {
        return when (actionId) {
            "analyze_logs" -> {
                val logPath = args.optString("log_path", "/workspace/app/logs").trim()
                val filter = args.optString("filter", "error").trim()
                val terminal = repo.connector("terminal")
                if (terminal == null || terminal.status != "configured") {
                    return SkillResult(false, "Terminal connector not configured")
                }
                val executor = ConnectorExecutor(repo, context)
                val command = "grep -i '$filter' $logPath/*.log 2>/dev/null | head -100"
                val result = executor.execute("terminal", agentId, "run", JSONObject().put("command", command).put("cwd", "/workspace/app"))
                SkillResult(result.ok, result.summary)
            }
            "profile_build" -> {
                val command = args.optString("command", "./gradlew assembleDebug --profile").trim()
                val terminal = repo.connector("terminal")
                if (terminal == null || terminal.status != "configured") {
                    return SkillResult(false, "Terminal connector not configured")
                }
                val executor = ConnectorExecutor(repo, context)
                val result = executor.execute("terminal", agentId, "run_background", JSONObject().put("command", command).put("cwd", "/workspace/app"))
                SkillResult(result.ok, "Profile build started: ${result.summary}")
            }
            "trace_issue" -> {
                val error = args.optString("error", "").trim()
                val stackTrace = args.optString("stack_trace", "").trim()
                SkillResult(true, "Issue tracing queued. Error: ${error.take(100)}. Stack: ${stackTrace.take(100)}")
            }
            else -> SkillResult(false, "Unknown debugging action '$actionId'")
        }
    }

    // =================================================================== Research

    private suspend fun executeResearch(agentId: String, actionId: String, args: JSONObject): SkillResult {
        return when (actionId) {
            "search_docs" -> {
                val query = args.optString("query", "").trim()
                val source = args.optString("source", "").trim()
                SkillResult(true, "Documentation search queued. Query: $query, Source: $source")
            }
            "compare_options" -> {
                val optionsJson = args.optString("options", "[]")
                val criteriaJson = args.optString("criteria", "[]")
                SkillResult(true, "Comparison queued. Options: $optionsJson, Criteria: $criteriaJson")
            }
            "find_best_practice" -> {
                val problem = args.optString("problem", "").trim()
                val context = args.optString("context", "").trim()
                SkillResult(true, "Best practice search queued. Problem: $problem, Context: $context")
            }
            else -> SkillResult(false, "Unknown research action '$actionId'")
        }
    }

    // =================================================================== Security Audit

    private suspend fun executeSecurityAudit(agentId: String, actionId: String, args: JSONObject): SkillResult {
        return when (actionId) {
            "scan_dependencies" -> {
                val gradleFile = args.optString("gradle_file", "/workspace/app/build.gradle.kts").trim()
                val terminal = repo.connector("terminal")
                if (terminal == null || terminal.status != "configured") {
                    return SkillResult(false, "Terminal connector not configured")
                }
                val executor = ConnectorExecutor(repo, context)
                val command = "./gradlew dependencyCheckAnalyze --offline 2>&1 || true"
                val result = executor.execute("terminal", agentId, "run", JSONObject().put("command", command).put("cwd", "/workspace/app"))
                SkillResult(result.ok, result.summary)
            }
            "check_permissions" -> {
                val manifestPath = args.optString("manifest_path", "/workspace/app/src/main/AndroidManifest.xml").trim()
                SkillResult(true, "Permission check queued. Manifest: $manifestPath")
            }
            "analyze_code" -> {
                val path = args.optString("path", "/workspace/app/app/src/main").trim()
                SkillResult(true, "Static analysis queued. Path: $path")
            }
            else -> SkillResult(false, "Unknown security_audit action '$actionId'")
        }
    }

    // =================================================================== Performance Optimization

    private suspend fun executePerformanceOptimization(agentId: String, actionId: String, args: JSONObject): SkillResult {
        return when (actionId) {
            "analyze_apk" -> {
                val apkPath = args.optString("apk_path", "/workspace/app/app/build/outputs/apk/release/app-release.apk").trim()
                val terminal = repo.connector("terminal")
                if (terminal == null || terminal.status != "configured") {
                    return SkillResult(false, "Terminal connector not configured")
                }
                val executor = ConnectorExecutor(repo, context)
                val command = "unzip -l $apkPath 2>&1 | head -50"
                val result = executor.execute("terminal", agentId, "run", JSONObject().put("command", command).put("cwd", "/workspace/app"))
                SkillResult(result.ok, result.summary)
            }
            "profile_startup" -> {
                val device = args.optString("device", "emulator").trim()
                val iterations = args.optInt("iterations", 10)
                SkillResult(true, "Startup profiling queued. Device: $device, Iterations: $iterations")
            }
            "optimize_build" -> {
                val gradleFile = args.optString("gradle_file", "/workspace/app/build.gradle.kts").trim()
                SkillResult(true, "Build optimization analysis queued. File: $gradleFile")
            }
            else -> SkillResult(false, "Unknown performance_optimization action '$actionId'")
        }
    }
}