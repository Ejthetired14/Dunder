package com.agoria.app.core

import com.agoria.app.data.ApprovalEntity
import com.agoria.app.data.Repository
import com.agoria.app.util.Json
import com.agoria.app.util.Notifier
import com.agoria.app.util.Time
import kotlinx.coroutines.sync.Mutex
import org.json.JSONObject

/**
 * Executes approved actions: build projects, connector actions, connector installs.
 *
 * Extracted from [AgentRuntime] as part of ARCH-1. Keeps the approval execution logic
 * separate from the agent ticking loop.
 */
class ApprovalExecutor(
    private val repo: Repository,
    private val buildRunner: BuildRunner,
    private val connectorExecutor: ConnectorExecutor,
    private val context: android.content.Context,
    private val mutexFor: (String) -> Mutex,
    private val log: (String, String) -> Unit,
) {

    /** The outcome of executing an approval, replacing fragile string-prefix matching. */
    data class ApprovalOutcome(val ok: Boolean, val summary: String, val retryable: Boolean = false)

    suspend fun execute(approval: ApprovalEntity) {
        val agent = repo.agent(approval.agentId)
            ?: return run {
                repo.updateApproval(approval.copy(status = "failed", decidedAt = Time.now(), result = "Agent no longer exists"))
            }

        val outcome = when (approval.type) {
            "build_project" -> {
                val project = repo.ensureProject()
                val lead = project.leadAgentId
                if (lead.isNotBlank() && lead != agent.id) {
                    val leadName = repo.agent(lead)?.name ?: "the lead agent"
                    ApprovalOutcome(false, "Build skipped: only $leadName can build in ${project.name}. Ask $leadName to run it.")
                } else {
                    val build = buildRunner.build(agent, approval)
                    if (build != null) {
                        ApprovalOutcome(true, "Build complete: ${build.name} (${build.fileCount} files)")
                    } else {
                        ApprovalOutcome(false, "Build failed: no files were generated.", retryable = true)
                    }
                }
            }

            "connector_action" -> {
                val details = Json.tryObject(approval.details) ?: JSONObject()
                val connectorId = Json.string(
                    details,
                    "connector_id",
                    Json.string(details, "connector"),
                )
                val actionName = Json.string(
                    details,
                    "action_name",
                    Json.string(details, "action", "request"),
                )
                val args = Json.obj(details, "args") ?: JSONObject()
                if (connectorId.isBlank() || actionName.isBlank()) {
                    ApprovalOutcome(false, "Approval data did not include a connector action.")
                } else {
                    val result = connectorExecutor.execute(connectorId, approval.agentId, actionName, args)
                    if (result.ok) ApprovalOutcome(true, result.summary)
                    else ApprovalOutcome(false, "Failed: ${result.summary}", retryable = true)
                }
            }

            "connector_install" -> {
                val connectorId = Json.string(Json.tryObject(approval.details), "connector_id")
                if (connectorId.isNotBlank()) {
                    val connector = repo.connector(connectorId)
                    if (connector != null) {
                        repo.updateConnector(connector.copy(status = "configured"))
                    }
                }
                ApprovalOutcome(true, "Connector install approved.")
            }

            else -> ApprovalOutcome(true, "Approved. There is no automatic executor for '${approval.type}'.")
        }

        repo.updateApproval(
            approval.copy(
                status = if (outcome.ok) "executed" else "failed",
                decidedAt = Time.now(),
                result = outcome.summary.take(2000),
            ),
        )

        if (approval.type != "build_project") {
            repo.addDiary(
                approval.agentId,
                if (outcome.ok) "decision" else "error",
                "Approval ${if (outcome.ok) "executed" else "failed"}: ${approval.title} — ${outcome.summary}",
                if (outcome.ok) 3 else 2,
            )
            repo.addMessage(
                approval.agentId,
                "system",
                "Approval ${if (outcome.ok) "executed" else "failed"}: ${approval.title}. ${outcome.summary}",
            )
        }
        repo.setAgentStatus(approval.agentId, "idle")
        log("${approval.title}: ${if (outcome.ok) "executed" else "failed"}", "info")
    }
}
