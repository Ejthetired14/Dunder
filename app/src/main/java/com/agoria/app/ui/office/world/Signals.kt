package com.agoria.app.ui.office.world

import com.agoria.app.data.AgentEntity
import com.agoria.app.data.ApprovalEntity
import com.agoria.app.data.BuildEntity
import com.agoria.app.data.ConnectorLogEntity
import com.agoria.app.data.ConnectorRequestEntity

/**
 * What the app knows an agent is doing right now. The sim turns this into a place to stand.
 * [place] is the room whose job matches the connector the agent used most recently.
 */
class AgentSignal(
    val status: String,
    val needsApproval: Boolean,
    val place: SpotKind?,
    val building: Boolean,
    val detail: String,
)

private const val RECENT_LOG_MS = 20_000L
private const val WORKING_LOG_MS = 60_000L
private const val RECENT_BUILD_MS = 15L * 60_000L

private val DONE_BUILD = setOf("completed", "complete", "done", "failed", "error", "cancelled", "canceled", "ready", "success")

/** Which room a connector belongs to. Local files and the device are used at the desk. */
fun placeForConnector(connectorId: String): SpotKind? = when (connectorId) {
    "wikipedia", "hn", "search", "weather", "currency", "huggingface" -> SpotKind.LIBRARY
    "accounts", "http" -> SpotKind.WEB
    "agentmail", "resend", "slack", "discord", "telegram" -> SpotKind.MAIL
    "github", "vercel", "netlify", "cloudflare_workers", "supabase", "upstash", "linear", "notion" -> SpotKind.SERVER
    "paypal", "polar" -> SpotKind.FINANCE
    else -> null
}

private fun prettyConnector(id: String): String = when (id) {
    "hn" -> "Hacker News"
    "accounts" -> "Office Browser"
    "http" -> "HTTP"
    else -> id.replace('_', ' ').replaceFirstChar { it.uppercase() }
}

fun buildSignals(
    agents: List<AgentEntity>,
    approvals: List<ApprovalEntity>,
    requests: List<ConnectorRequestEntity>,
    logs: List<ConnectorLogEntity>,
    builds: List<BuildEntity>,
    nowMs: Long,
): Map<String, AgentSignal> {
    val out = HashMap<String, AgentSignal>()
    for (a in agents) {
        val needs = a.status == "waiting_approval" ||
            approvals.any { it.agentId == a.id } ||
            requests.any { it.agentId == a.id }

        val lastLog = logs.filter { it.agentId == a.id }.maxByOrNull { it.createdAt }
        val age = if (lastLog == null) Long.MAX_VALUE else nowMs - lastLog.createdAt
        val logCounts = lastLog != null && (
            age < RECENT_LOG_MS || (a.status == "working" && age < WORKING_LOG_MS)
            )
        val place = if (logCounts) placeForConnector(lastLog!!.connectorId) else null
        val detail = if (logCounts) {
            val name = prettyConnector(lastLog!!.connectorId)
            val action = lastLog.action.replace('_', ' ')
            if (action.isBlank()) name else "$name, $action"
        } else {
            ""
        }

        val building = builds.any {
            it.agentId == a.id &&
                it.completedAt == null &&
                it.status.lowercase() !in DONE_BUILD &&
                nowMs - it.createdAt < RECENT_BUILD_MS
        }
        out[a.id] = AgentSignal(a.status, needs, place, building, detail)
    }
    return out
}
