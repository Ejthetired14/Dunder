package com.agoria.app.core

import android.content.Context
import com.agoria.app.core.llm.LlmClient
import com.agoria.app.core.llm.LlmMessage
import com.agoria.app.core.llm.LlmRequest
import com.agoria.app.data.AgentEntity
import com.agoria.app.data.ApprovalEntity
import com.agoria.app.data.Repository
import com.agoria.app.util.Json
import com.agoria.app.util.Notifier
import com.agoria.app.util.Time
import kotlinx.coroutines.Job
import kotlinx.coroutines.delay
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.flow.update
import kotlinx.coroutines.isActive
import kotlinx.coroutines.launch
import kotlinx.coroutines.sync.Mutex
import kotlinx.coroutines.sync.withLock
import org.json.JSONObject
import kotlin.random.Random
import java.util.concurrent.ConcurrentHashMap

class AgentRuntime(
    private val repo: Repository,
    private val llm: LlmClient,
    private val buildRunner: BuildRunner,
    private val connectorExecutor: ConnectorExecutor,
    private val context: Context,
    private val scope: kotlinx.coroutines.CoroutineScope,
) {

    private val mutexes = ConcurrentHashMap<String, Mutex>()
    private val _state = MutableStateFlow(RuntimeState())
    val state = _state.asStateFlow()

    private var loopJob: Job? = null

    @Volatile
    private var running = false

    fun isRunning(): Boolean = running

    fun start() {
        if (running) return
        running = true
        _state.update { it.copy(running = true, error = null) }
        log("Autonomy started.")
        loopJob = scope.launch {
            while (isActive) {
                try {
                    tickAll()
                } catch (e: Exception) {
                    log("Tick failed: ${e.message ?: e.javaClass.simpleName}", "error")
                }
                val settings = try {
                    repo.settings()
                } catch (_: Exception) {
                    null
                }
                val seconds = settings?.tickIntervalSeconds?.coerceIn(20, 3600) ?: 120
                delay(seconds * 1000L)
            }
        }
    }

    fun stop() {
        if (!running && loopJob == null) return
        running = false
        loopJob?.cancel()
        loopJob = null
        _state.update {
            it.copy(
                running = false,
                ticking = false,
                currentAgentId = null,
                currentAgentName = null,
            )
        }
        log("Autonomy stopped.")
    }

    suspend fun tickOnce() {
        tickAll()
    }

    suspend fun nudgeAll() {
        repo.allAgents().forEach { agent ->
            repo.updateAgent(agent.copy(lastTickAt = 0L))
        }
        log("Office nudged; all agents are due for a cycle.")
        tickAll()
    }

    suspend fun runAgentNow(agentId: String) {
        val agent = repo.agent(agentId) ?: return
        repo.updateAgent(agent.copy(lastTickAt = 0L))
        mutexFor(agentId).withLock {
            runCycle(agentId, null)
        }
    }

    suspend fun chat(agentId: String, text: String) {
        if (text.isBlank()) return
        repo.addMessage(agentId, "user", text.trim())
        mutexFor(agentId).withLock {
            runCycle(agentId, text.trim())
        }
    }

    suspend fun approve(approvalId: String, note: String = "") {
        val approval = repo.approval(approvalId) ?: return
        if (approval.status != "pending") return
        val now = Time.now()
        val decided = approval.copy(
            status = "approved",
            decidedAt = now,
            decisionNote = note.ifBlank { null },
        )
        repo.updateApproval(decided)
        repo.setAgentStatus(approval.agentId, "working")
        Notifier.cancelApproval(context, approval.title)
        mutexFor(approval.agentId).withLock {
            executeApproval(decided)
        }
    }

    suspend fun deny(approvalId: String, note: String = "") {
        val approval = repo.approval(approvalId) ?: return
        if (approval.status != "pending") return
        val now = Time.now()
        val decided = approval.copy(
            status = "denied",
            decidedAt = now,
            decisionNote = note.ifBlank { null },
        )
        repo.updateApproval(decided)
        Notifier.cancelApproval(context, approval.title)
        repo.addDiary(
            approval.agentId,
            "decision",
            "Denied: ${approval.title}. ${if (note.isBlank()) "No reason given." else note}",
            3,
        )
        repo.addMessage(
            approval.agentId,
            "system",
            "The user denied: ${approval.title}. ${if (note.isBlank()) "" else note}",
        )
        repo.setAgentStatus(approval.agentId, "idle")
    }

    suspend fun approveConnectorRequest(requestId: String): String? {
        val request = repo.connectorRequest(requestId) ?: return null
        if (request.status != "pending") return null
        repo.updateConnectorRequest(request.copy(status = "approved", decidedAt = Time.now()))
        val def = ConnectorCatalog.get(request.connectorId)
        if (def == null) {
            repo.addDiary(
                request.agentId,
                "connector",
                "The user approved a request for '$request.connectorId', but it is not in the built-in catalog. I should use the HTTP connector or ask for a specific service.",
                3,
            )
            repo.addMessage(
                request.agentId,
                "system",
                "Connector request approved, but '$request.connectorId' is not built in. Use the HTTP connector or ask for a different one.",
            )
            repo.setAgentStatus(request.agentId, "idle")
            return null
        }
        repo.addConnector(def, "needs_setup")
        repo.updateConnector(
            repo.connector(def.id)!!.copy(status = "needs_setup", lastError = null),
        )
        repo.addDiary(
            request.agentId,
            "connector",
            "The user approved the ${def.name} connector. It needs credentials in the connector setup screen.",
            3,
        )
        repo.addMessage(
            request.agentId,
            "system",
            "Connector approved: ${def.name}. Configure it in the Connectors screen, then I can use it.",
        )
        repo.setAgentStatus(request.agentId, "idle")
        log("${def.name} connector approved for ${request.agentId}.")
        return def.id
    }

    suspend fun denyConnectorRequest(requestId: String, note: String = "") {
        val request = repo.connectorRequest(requestId) ?: return
        if (request.status != "pending") return
        repo.updateConnectorRequest(request.copy(status = "denied", decidedAt = Time.now()))
        val def = ConnectorCatalog.get(request.connectorId)
        repo.addDiary(
            request.agentId,
            "connector",
            "The user denied the ${def?.name ?: request.connectorId} connector request. ${if (note.isBlank()) "" else note}",
            2,
        )
        repo.setAgentStatus(request.agentId, "idle")
    }

    // ------------------------------------------------------------------ ticking

    private suspend fun tickAll() {
        if (!running) return
        val now = Time.now()
        _state.update { it.copy(ticking = true) }
        val settings = repo.settings()
        val cooldown = settings.agentCooldownSeconds.coerceIn(15, 3600) * 1000L

        val agents = repo.allAgents()
        for (agent in agents) {
            if (!agent.autonomy || agent.status == "paused") continue
            if (agent.status == "waiting_approval") {
                val pendingApprovals = repo.pendingApprovalsFor(agent.id)
                val pendingConnectors = repo.pendingConnectorRequestsFor(agent.id)
                if (pendingApprovals.isEmpty() && pendingConnectors.isEmpty()) {
                    repo.setAgentStatus(agent.id, "idle")
                }
                continue
            }
            val jitter = Random.nextLong(0, 12_000L)
            if (now - agent.lastTickAt < cooldown + jitter) continue

            mutexFor(agent.id).withLock {
                runCycle(agent.id, null)
            }
        }

        _state.update {
            it.copy(
                ticking = false,
                lastTickAt = Time.now(),
                currentAgentId = null,
                currentAgentName = null,
            )
        }
    }

    private fun mutexFor(agentId: String): Mutex =
        mutexes.getOrPut(agentId) { Mutex() }

    // --------------------------------------------------------------- agent cycle

    private suspend fun runCycle(agentId: String, userMessage: String?) {
        val agent = repo.agent(agentId) ?: return
        if (agent.status == "paused" && userMessage == null) return
        if (userMessage == null) {
            val pendingApprovals = repo.pendingApprovalsFor(agentId)
            val pendingConnectors = repo.pendingConnectorRequestsFor(agentId)
            if (agent.status == "waiting_approval" && pendingApprovals.isEmpty() && pendingConnectors.isEmpty()) {
                repo.setAgentStatus(agentId, "idle")
            }
        }

        _state.update {
            it.copy(
                currentAgentId = agent.id,
                currentAgentName = agent.name,
                lastSummary = null,
            )
        }
        repo.setAgentStatus(agentId, "thinking")

        val agentContext = buildContext(agent)
        val system = PromptBuilder.system(agent, agentContext)
        val user = PromptBuilder.userPrompt(agentContext, userMessage)

        val result = llm.complete(
            LlmRequest(
                system = system,
                messages = listOf(LlmMessage("user", user)),
                temperature = 0.7,
                maxTokens = repo.settings().maxTokens.coerceIn(400, 4000),
            ),
        )

        if (!result.ok) {
            val message = "Model call failed: ${result.error ?: "unknown error"}"
            log("$message", "error")
            repo.addDiary(agentId, "error", message.take(800), 2)
            if (userMessage != null) {
                repo.addMessage(
                    agentId,
                    "system",
                    "I could not reach a model. Check Settings → Providers and add a key or enable a keyless provider.",
                )
            }
            repo.noteAgentTick(agentId, "idle", message)
            _state.update { it.copy(currentAgentId = null, currentAgentName = null, error = message) }
            return
        }

        val decision = AgentPolicy.parseDecision(result.text)
        if (decision == null) {
            val fallback = result.text.trim()
            repo.addDiary(agentId, "thought", fallback.take(1200), 2)
            if (userMessage != null) {
                repo.addMessage(agentId, "agent", fallback.take(4000))
            }
            log("${agent.name} returned text instead of JSON; stored it as a diary entry.")
            repo.noteAgentTick(agentId, "idle")
            _state.update { it.copy(currentAgentId = null, currentAgentName = null) }
            return
        }

        var action = decision.action
        var input = decision.input
        if (action == "build") {
            action = "request_approval"
            if (input.optString("type").isBlank()) {
                input = input.put("type", "build_project")
            }
        }

        val processed = decision.copy(action = action, input = input)

        if (processed.diary.isNotBlank()) {
            repo.addDiary(
                agentId,
                AgentPolicy.diaryKind(processed.diaryKind),
                processed.diary,
                processed.importance,
            )
        }
        if (processed.message.isNotBlank()) {
            repo.addMessage(agentId, "agent", processed.message)
        }

        var nextStatus = "idle"

        when (action) {
            "none", "think" -> {
                // A cycle can be just a thought.
            }

            "create_task" -> {
                val title = Json.string(input, "title", "Untitled task")
                val description = Json.string(input, "description", "")
                repo.addTask(agentId, title, description, "active")
                log("${agent.name} created task: $title")
            }

            "update_goal" -> {
                val goal = Json.string(input, "goal")
                if (goal.isNotBlank()) {
                    repo.updateAgent(agent.copy(goal = goal.take(500), updatedAt = Time.now()))
                    log("${agent.name} updated their goal.")
                }
            }

            "update_memory" -> {
                val content = Json.string(input, "content")
                if (content.isNotBlank()) {
                    repo.addMemory(
                        agentId,
                        Json.string(input, "kind", "fact"),
                        content.take(1000),
                        Json.int(input, "importance", 2),
                    )
                    log("${agent.name} stored a memory.")
                }
            }

            "complete_task" -> {
                val taskId = Json.string(input, "task_id")
                if (taskId.isNotBlank()) {
                    repo.completeTask(taskId, Json.string(input, "result"))
                    log("${agent.name} completed a task.")
                }
            }

            "request_connector" -> {
                val draft = AgentPolicy.connectorRequestFor(processed)
                if (draft != null) {
                    repo.addConnectorRequest(agentId, draft.connectorId, draft.reason, draft.scopes)
                    nextStatus = "waiting_approval"
                    log("${agent.name} requested connector ${draft.connectorId}.")
                } else {
                    log("${agent.name} tried to request a connector without an id.", "error")
                }
            }

            "use_connector" -> {
                val connectorId = Json.string(
                    input,
                    "connector_id",
                    Json.string(input, "connector"),
                )
                val actionName = Json.string(
                    input,
                    "action_name",
                    Json.string(input, "action", "request"),
                )
                val args = Json.obj(input, "args") ?: JSONObject()

                if (connectorId.isBlank() || actionName.isBlank()) {
                    log("${agent.name} tried to use a connector without an action.", "error")
                } else if (AgentPolicy.isAutoAllowed(processed)) {
                    val summary = useConnector(agent, connectorId, actionName, args)
                    repo.addMessage(agentId, "tool", summary)
                    log("${agent.name} used $connectorId.$actionName")
                } else {
                    val def = ConnectorCatalog.get(connectorId)
                    repo.addApproval(
                        agentId = agentId,
                        type = "connector_action",
                        title = "${agent.name} wants to use ${def?.name ?: connectorId}",
                        summary = "Action '$actionName' on ${def?.name ?: connectorId}. Review the exact arguments before approving.",
                        details = input.toString(2),
                        risk = def?.risk ?: "high",
                    )
                    nextStatus = "waiting_approval"
                    log("${agent.name} requested approval for $connectorId.$actionName.")
                }
            }

            "request_approval" -> {
                val draft = AgentPolicy.approvalFor(processed, agent.name)
                repo.addApproval(
                    agentId = agentId,
                    type = draft.type,
                    title = draft.title,
                    summary = draft.summary,
                    details = draft.details,
                    risk = draft.risk,
                )
                nextStatus = "waiting_approval"
                log("${agent.name} requested approval: ${draft.title}")
                Notifier.notifyApproval(
                    context,
                    "Approval needed: ${draft.title}",
                    "${agent.name}: ${draft.summary}",
                )
            }

            else -> {
                repo.addDiary(
                    agentId,
                    "error",
                    "I chose an action I do not know how to perform: $action",
                    2,
                )
                log("${agent.name} used unknown action '$action'.", "error")
            }
        }

        val summary = processed.message.ifBlank { processed.diary }
        _state.update {
            it.copy(
                currentAgentId = null,
                currentAgentName = null,
                lastSummary = summary.ifBlank { null },
            )
        }
        repo.noteAgentTick(agentId, nextStatus)
    }

    private suspend fun useConnector(
        agent: AgentEntity,
        connectorId: String,
        actionName: String,
        args: JSONObject,
    ): String {
        val connector = repo.connector(connectorId)
        if (connector == null || connector.status == "needs_setup") {
            repo.addConnectorRequest(
                agent.id,
                connectorId,
                "I need the $connectorId connector configured to run $actionName.",
                ConnectorCatalog.get(connectorId)?.let { ConnectorCatalog.defaultScopes(it) } ?: "",
            )
            return "The $connectorId connector is not configured. I requested setup."
        }
        val result = connectorExecutor.execute(connectorId, agent.id, actionName, args)
        if (result.ok) {
            repo.addDiary(agent.id, "connector", "$connectorId.$actionName -> ${result.summary.take(600)}", 3)
            return result.summary
        }
        repo.addDiary(agent.id, "error", "$connectorId.$actionName failed: ${result.summary.take(600)}", 3)
        return result.summary
    }

    // ---------------------------------------------------------------- approvals

    private suspend fun executeApproval(approval: ApprovalEntity) {
        val agent = repo.agent(approval.agentId)
            ?: return run {
                repo.updateApproval(approval.copy(status = "failed", executedAt = Time.now(), result = "Agent no longer exists"))
            }

        val outcome = when (approval.type) {
            "build_project" -> {
                val details = Json.tryObject(approval.details) ?: JSONObject()
                val build = buildRunner.build(agent, approval)
                if (build != null) {
                    "Build complete: ${build.name} (${build.fileCount} files)"
                } else {
                    "Build failed: no files were generated."
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
                    "Approval data did not include a connector action."
                } else {
                    val result = connectorExecutor.execute(connectorId, approval.agentId, actionName, args)
                    if (result.ok) result.summary else "Failed: ${result.summary}"
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
                "Connector install approved."
            }

            else -> "Approved. There is no automatic executor for '${approval.type}'."
        }

        val ok = !outcome.startsWith("Failed") && !outcome.startsWith("Build failed") &&
            !outcome.contains("no longer exists") && !outcome.startsWith("Approval data")
        repo.updateApproval(
            approval.copy(
                status = if (ok) "executed" else "failed",
                executedAt = Time.now(),
                result = outcome.take(2000),
            ),
        )

        if (approval.type != "build_project") {
            repo.addDiary(
                approval.agentId,
                if (ok) "decision" else "error",
                "Approval ${if (ok) "executed" else "failed"}: ${approval.title} — $outcome",
                if (ok) 3 else 2,
            )
            repo.addMessage(
                approval.agentId,
                "system",
                "Approval ${if (ok) "executed" else "failed"}: ${approval.title}. $outcome",
            )
        }
        repo.setAgentStatus(approval.agentId, "idle")
        log("${approval.title}: ${if (ok) "executed" else "failed"}")
    }

    // ------------------------------------------------------------------ context

    private suspend fun buildContext(agent: AgentEntity): AgentContext {
        val settings = repo.settings()
        val diary = repo.diaryFor(agent.id).first().take(18)
        val memories = repo.memoriesFor(agent.id).first().take(14)
        val messages = repo.messagesFor(agent.id).first().takeLast(14)
        val tasks = repo.tasksFor(agent.id).first()
        val approvals = repo.approvalsFor(agent.id).first()
        val builds = repo.buildsFor(agent.id).first()

        val connectors = repo.connectors.first().map { connector ->
            val def = ConnectorCatalog.get(connector.id)
            ConnectorBrief(
                id = connector.id,
                name = connector.name,
                status = connector.status,
                summary = def?.description ?: "Custom connector.",
                ready = connector.status == "ready" || connector.status == "configured",
            )
        }

        val providers = repo.providers.first().map { provider ->
            val spec = ProviderCatalog.byId[provider.id]
            ProviderBrief(
                id = provider.id,
                name = spec?.name ?: provider.id,
                enabled = provider.enabled,
                hasKey = !provider.apiKeyEnc.isNullOrBlank(),
                keyless = spec?.keyRequired == false,
                model = provider.model.ifBlank { spec?.defaultModel ?: "" },
                lastStatus = provider.lastStatus,
            )
        }

        val peers = repo.agents.first().filter { it.id != agent.id }.map {
            PeerInfo(
                name = it.name,
                role = it.role,
                status = it.status,
                goal = it.goal,
            )
        }

        return AgentContext(
            agent = agent,
            officeName = settings.officeName,
            userName = settings.userName,
            now = Time.now(),
            diary = diary,
            memories = memories,
            messages = messages,
            tasks = tasks,
            approvals = approvals,
            builds = builds,
            connectors = connectors,
            providers = providers,
            peers = peers,
        )
    }

    private fun log(message: String, level: String = "info") {
        val entry = RuntimeLogEntry(Time.now(), message.take(500), level)
        _state.update {
            it.copy(
                log = (it.log + entry).takeLast(80),
                error = if (level == "error") message else it.error,
            )
        }
    }
}