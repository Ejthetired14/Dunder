package com.agoria.app.core

import com.agoria.app.util.Json
import org.json.JSONObject

object AgentPolicy {

    val autoActions: Set<String> = setOf(
        "none",
        "create_task",
        "update_goal",
        "update_memory",
        "complete_task",
        "ask_user",
        "think",
        "say",
    )

    /**
     * Extracts the first JSON object from arbitrary text. Handles markdown code fences,
     * prose embedding, nested braces, and escaped quotes inside strings. Returns null
     * when no JSON object is found.
     */
    fun extractFirstJsonObject(text: String): String? {
        val start = text.indexOf('{')
        if (start == -1) return null
        var depth = 0
        var inString = false
        var escaped = false
        var end = -1
        for (i in start until text.length) {
            val c = text[i]
            when {
                escaped -> escaped = false
                c == '\\' && inString -> escaped = true
                c == '"' -> inString = !inString
                !inString && c == '{' -> depth++
                !inString && c == '}' -> {
                    depth--
                    if (depth == 0) {
                        end = i
                        break
                    }
                }
            }
        }
        return if (end == -1) null else text.substring(start, end + 1)
    }

    fun parseDecision(text: String): AgentDecision? {
        val json = extractFirstJsonObject(text) ?: return null
        val root = Json.tryObject(json) ?: return null
        val action = Json.string(root, "action", "none").lowercase().ifBlank { "none" }
        val inputObject = Json.obj(root, "action_input")
        val input = inputObject ?: when (action) {
            "create_task", "request_approval", "request_connector", "use_connector", "update_goal", "update_memory", "complete_task", "say" -> root
            else -> JSONObject()
        }
        return AgentDecision(
            diary = Json.string(root, "diary").trim(),
            diaryKind = Json.string(root, "diary_kind", "thought").lowercase().ifBlank { "thought" },
            message = Json.string(root, "message").trim(),
            action = action,
            input = input,
            importance = Json.int(root, "importance", 2).coerceIn(1, 5),
        )
    }

    fun isAutoAllowed(decision: AgentDecision): Boolean {
        return when (decision.action) {
            "use_connector" -> {
                val connectorId = Json.string(decision.input, "connector_id", Json.string(decision.input, "connector"))
                val actionName = Json.string(decision.input, "action_name", Json.string(decision.input, "action", "request"))
                ConnectorCatalog.isReadOnly(connectorId, actionName)
            }
            else -> decision.action in autoActions
        }
    }

    fun isApprovalRequest(decision: AgentDecision): Boolean {
        return decision.action == "request_approval" ||
            (decision.action == "use_connector" && !isAutoAllowed(decision))
    }

    fun isConnectorRequest(decision: AgentDecision): Boolean {
        return decision.action == "request_connector"
    }

    fun approvalFor(decision: AgentDecision, agentName: String): ApprovalDraft {
        if (decision.action == "use_connector") {
            val connectorId = Json.string(decision.input, "connector_id", Json.string(decision.input, "connector"))
            val actionName = Json.string(decision.input, "action_name", Json.string(decision.input, "action", "request"))
            val def = ConnectorCatalog.get(connectorId)
            val title = "$agentName wants to use ${def?.name ?: connectorId}"
            return ApprovalDraft(
                type = "connector_action",
                title = title,
                summary = "Action '$actionName' on ${def?.name ?: connectorId}. The exact arguments are in the details.",
                details = decision.input.toString(2),
                risk = def?.risk ?: "high",
            )
        }

        val type = Json.string(decision.input, "type", "other").lowercase().ifBlank { "other" }
        val title = Json.string(decision.input, "title", "$agentName wants to build something").ifBlank {
            "$agentName wants to build something"
        }
        val summary = Json.string(
            decision.input,
            "summary",
            Json.string(decision.input, "description", title),
        )
        return ApprovalDraft(
            type = type,
            title = title,
            summary = summary,
            details = decision.input.toString(2),
            risk = riskFor(type, Json.string(decision.input, "connector_id")),
        )
    }

    fun connectorRequestFor(decision: AgentDecision): ConnectorRequestDraft? {
        val connectorId = Json.string(
            decision.input,
            "connector_id",
            Json.string(decision.input, "connector", Json.string(decision.input, "name")),
        ).ifBlank { return null }
        val def = ConnectorCatalog.get(connectorId)
        return ConnectorRequestDraft(
            connectorId = if (def != null) def.id else connectorId,
            reason = Json.string(decision.input, "reason", "I need this connector to do my work."),
            scopes = Json.string(decision.input, "scopes", "").ifBlank {
                def?.let { ConnectorCatalog.defaultScopes(it) } ?: "not specified"
            },
        )
    }

    fun riskFor(type: String, connectorId: String?): String {
        val connectorRisk = connectorId?.let { ConnectorCatalog.get(it)?.risk }
        return when (type) {
            "build_project" -> "medium"
            "connector_action" -> connectorRisk ?: "high"
            "send_email", "external_action", "publish", "delete", "spend_api" -> "high"
            "connector_install" -> "medium"
            else -> connectorRisk ?: "low"
        }
    }

    fun normalizeImportance(value: Int): Int = value.coerceIn(1, 5)

    fun diaryKind(value: String): String {
        val allowed = setOf(
            "thought",
            "observation",
            "plan",
            "decision",
            "lesson",
            "error",
            "build",
            "connector",
            "review",
        )
        return if (value.lowercase() in allowed) value.lowercase() else "thought"
    }

    /** A `say` is stored in the diary (kind "say") as "To <name>: <text>". */
    val SAY_REGEX = Regex("^To ([^:]{1,40}): (.*)$", RegexOption.DOT_MATCHES_ALL)

    fun sayLine(toName: String, text: String): String =
        "To ${toName.trim()}: ${text.trim().take(400)}"
}