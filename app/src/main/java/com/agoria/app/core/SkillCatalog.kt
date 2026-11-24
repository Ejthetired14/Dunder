package com.agoria.app.core

import org.json.JSONArray
import org.json.JSONObject

/**
 * Skill definition - a reusable capability that can be installed on agents.
 * Skills are like "apps" for agents - they provide specific actions the agent can perform.
 */
data class SkillDef(
    val id: String,
    val name: String,
    val category: String,
    val description: String,
    val actions: List<SkillActionDef>,
    val version: Int = 1,
)

data class SkillActionDef(
    val id: String,
    val label: String,
    val description: String,
    val argsHint: String, // JSON schema hint for arguments
)

object SkillCatalog {

    val all: List<SkillDef> = listOf(
        SkillDef(
            id = "code_review",
            name = "Code Review",
            category = "Development",
            description = "Review code changes, suggest improvements, find bugs, and enforce standards.",
            actions = listOf(
                SkillActionDef("review_diff", "Review Diff", "Review a git diff and provide feedback.", "{\"diff\": \"...\", \"context\": \"...\"}"),
                SkillActionDef("check_style", "Check Style", "Check code against style guide.", "{\"files\": [\"...\"], \"style_guide\": \"...\"}"),
                SkillActionDef("suggest_fixes", "Suggest Fixes", "Suggest concrete fixes for issues found.", "{\"issues\": [...], \"code\": \"...\"}"),
            ),
        ),
        SkillDef(
            id = "testing",
            name = "Testing",
            category = "Development",
            description = "Write and run tests, generate test cases, analyze coverage.",
            actions = listOf(
                SkillActionDef("generate_tests", "Generate Tests", "Generate unit tests for given code.", "{\"code\": \"...\", \"framework\": \"junit\"}"),
                SkillActionDef("run_tests", "Run Tests", "Execute test suite and report results.", "{\"command\": \"./gradlew test\", \"path\": \"/workspace/app\"}"),
                SkillActionDef("analyze_coverage", "Analyze Coverage", "Parse coverage report and find gaps.", "{\"report_path\": \"...\"}"),
            ),
        ),
        SkillDef(
            id = "documentation",
            name = "Documentation",
            category = "Development",
            description = "Write and maintain documentation, API docs, README files.",
            actions = listOf(
                SkillActionDef("write_readme", "Write README", "Generate a README for a project.", "{\"project_path\": \"/workspace/app\", \"type\": \"android\"}"),
                SkillActionDef("write_api_docs", "Write API Docs", "Generate API documentation from code.", "{\"source_path\": \"...\", \"format\": \"markdown\"}"),
                SkillActionDef("update_changelog", "Update Changelog", "Add entry to changelog.", "{\"version\": \"1.0.0\", \"changes\": [...]}"),
            ),
        ),
        SkillDef(
            id = "deployment",
            name = "Deployment",
            category = "Operations",
            description = "Deploy applications, manage releases, configure CI/CD.",
            actions = listOf(
                SkillActionDef("build_apk", "Build APK", "Build Android APK (debug/release).", "{\"variant\": \"release\", \"sign\": true}"),
                SkillActionDef("deploy_worker", "Deploy Cloudflare Worker", "Deploy a Cloudflare Worker script.", "{\"name\": \"my-api\", \"script\": \"...\"}"),
                SkillActionDef("create_release", "Create GitHub Release", "Create a GitHub release with artifacts.", "{\"tag\": \"v1.0.0\", \"notes\": \"...\", \"assets\": [...]}"),
            ),
        ),
        SkillDef(
            id = "git_operations",
            name = "Git Operations",
            category = "Development",
            description = "Perform git operations: commit, branch, merge, rebase, push.",
            actions = listOf(
                SkillActionDef("commit", "Commit Changes", "Stage and commit changes with message.", "{\"message\": \"feat: add feature\", \"files\": [\"...\"]}"),
                SkillActionDef("branch", "Create Branch", "Create and checkout a new branch.", "{\"name\": \"feature/new-feature\", \"base\": \"main\"}"),
                SkillActionDef("push", "Push Changes", "Push commits to remote.", "{\"remote\": \"origin\", \"branch\": \"feature/new-feature\"}"),
                SkillActionDef("create_pr", "Create Pull Request", "Create a PR on GitHub.", "{\"title\": \"...\", \"body\": \"...\", \"base\": \"main\", \"head\": \"feature/...\"}"),
            ),
        ),
        SkillDef(
            id = "debugging",
            name = "Debugging",
            category = "Development",
            description = "Debug applications, analyze logs, trace issues, profile performance.",
            actions = listOf(
                SkillActionDef("analyze_logs", "Analyze Logs", "Parse and analyze log files for errors.", "{\"log_path\": \"/workspace/app/logs\", \"filter\": \"error\"}"),
                SkillActionDef("profile_build", "Profile Build", "Run build with profiling and report bottlenecks.", "{\"command\": \"./gradlew assembleDebug --profile\"}"),
                SkillActionDef("trace_issue", "Trace Issue", "Trace an issue through the codebase.", "{\"error\": \"...\", \"stack_trace\": \"...\"}"),
            ),
        ),
        SkillDef(
            id = "research",
            name = "Research",
            category = "Knowledge",
            description = "Research topics, compare technologies, find best practices.",
            actions = listOf(
                SkillActionDef("search_docs", "Search Documentation", "Search official docs for a technology.", "{\"query\": \"Kotlin coroutines\", \"source\": \"kotlinlang.org\"}"),
                SkillActionDef("compare_options", "Compare Options", "Compare multiple libraries/tools.", "{\"options\": [\"Retrofit\", \"Ktor\", \"OkHttp\"], \"criteria\": [\"performance\", \"ease\"]}"),
                SkillActionDef("find_best_practice", "Find Best Practice", "Find recommended approach for a problem.", "{\"problem\": \"background sync\", \"context\": \"Android\"}"),
            ),
        ),
        SkillDef(
            id = "security_audit",
            name = "Security Audit",
            category = "Security",
            description = "Audit code for security vulnerabilities, check dependencies, validate permissions.",
            actions = listOf(
                SkillActionDef("scan_dependencies", "Scan Dependencies", "Check for vulnerable dependencies.", "{\"gradle_file\": \"/workspace/app/build.gradle.kts\"}"),
                SkillActionDef("check_permissions", "Check Permissions", "Audit Android permissions usage.", "{\"manifest_path\": \"/workspace/app/src/main/AndroidManifest.xml\"}"),
                SkillActionDef("analyze_code", "Analyze Code", "Static analysis for security issues.", "{\"path\": \"/workspace/app/app/src/main\"}"),
            ),
        ),
        SkillDef(
            id = "performance_optimization",
            name = "Performance Optimization",
            category = "Development",
            description = "Optimize app performance, reduce bundle size, improve startup time.",
            actions = listOf(
                SkillActionDef("analyze_apk", "Analyze APK", "Analyze APK size and composition.", "{\"apk_path\": \"/workspace/app/app/build/outputs/apk/release/app-release.apk\"}"),
                SkillActionDef("profile_startup", "Profile Startup", "Measure and analyze app startup time.", "{\"device\": \"emulator\", \"iterations\": 10}"),
                SkillActionDef("optimize_build", "Optimize Build", "Suggest build optimizations.", "{\"gradle_file\": \"/workspace/app/build.gradle.kts\"}"),
            ),
        ),
    )

    val byId: Map<String, SkillDef> = all.associateBy { it.id }

    fun get(id: String): SkillDef? = byId[id]

    fun getByCategory(category: String): List<SkillDef> = all.filter { it.category == category }

    val categories: List<String> = all.map { it.category }.distinct().sorted()
}