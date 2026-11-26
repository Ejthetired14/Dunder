package com.agoria.app.core

import android.content.Context
import android.util.Log
import com.agoria.app.core.llm.LlmClient
import com.agoria.app.core.llm.LlmMessage
import com.agoria.app.core.llm.LlmRequest
import com.agoria.app.data.AgentEntity
import com.agoria.app.data.ApprovalEntity
import com.agoria.app.data.ProviderEntity
import com.agoria.app.data.Repository
import com.agoria.app.util.Json
import com.agoria.app.util.Notifier
import com.agoria.app.util.Time
import kotlinx.coroutines.Job
import kotlinx.coroutines.coroutineScope
import kotlinx.coroutines.delay
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.flow.update
import kotlinx.coroutines.isActive
import kotlinx.coroutines.launch
import kotlinx.coroutines.withTimeoutOrNull
import kotlinx.coroutines.sync.Mutex
import kotlinx.coroutines.sync.withLock
import org.json.JSONArray
import org.json.JSONObject
import kotlin.random.Random
import java.util.concurrent.ConcurrentHashMap

class AgentRuntime(
    private val repo: Repository,
    private val llm: LlmClient,
    private val buildRunner: BuildRunner,
    private val connectorExecutorInstance: ConnectorExecutor,
    private val context: Context,
    private val scope: kotlinx.coroutines.CoroutineScope,
) {

    private val mutexes = ConcurrentHashMap<String, Mutex>()
    private val _state = MutableStateFlow(RuntimeState())
    val state = _state.asStateFlow()

    // Expose build progress from BuildRunner
    val buildProgress = buildRunner.buildProgress

    // Expose connector executor for direct connector calls (e.g., backup/restore)
    val connectorExecutor: ConnectorExecutor = connectorExecutorInstance

    // ARCH-1: extracted collaborators
    private val approvalExecutor = ApprovalExecutor(
        repo = repo,
        buildRunner = buildRunner,
        connectorExecutor = connectorExecutor,
        context = context,
        mutexFor = ::mutexFor,
        log = ::log,
    )
    private val contextBuilder = CycleContextBuilder(repo = repo)

    private var loopJob: Job? = null

    @Volatile
    private var running = false

    // RUN-2: global request and token budget (per hour), resets every hour.
    private var requestCount = 0
    private var tokenCount = 0
    private var budgetResetTime = 0L
    private val maxRequestsPerHour = 200
    private val maxTokensPerHour = 50_000

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
        if (approval.status != "pending" && approval.status != "auto_pending") return
        Log.i("ApprovalFlow", "Manual approve approval id=$approvalId status=${approval.status}")
        val now = Time.now()
        val decided = approval.copy(
            status = "approved",
            decidedAt = now,
            result = note.ifBlank { null },
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
        if (approval.status != "pending" && approval.status != "auto_pending") return
        Log.i("ApprovalFlow", "Manual deny approval id=$approvalId status=${approval.status}")
        val now = Time.now()
        val decided = approval.copy(
            status = "denied",
            decidedAt = now,
            result = note.ifBlank { null },
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
                "The user approved a request for '${request.connectorId}', but it is not in the built-in catalog. I should use the HTTP connector or ask for a specific service.",
                3,
            )
            repo.addMessage(
                request.agentId,
                "system",
                "Connector request approved, but '${request.connectorId}' is not built in. Use the HTTP connector or ask for a different one.",
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

        // Process auto-approved approvals that haven't been executed yet
        processAutoApprovedApprovals()

        val agents = repo.allAgents()
        // RUN-1: run agents concurrently so one slow model call does not stall the office.
        coroutineScope {
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
                launch {
                    val agentNow = Time.now()
                    val jitter = Random.nextLong(0, 12_000L)
                    if (agentNow - agent.lastTickAt < cooldown + jitter) return@launch
                    // RUN-1: per-cycle timeout so a hung model call cannot block the agent forever.
                    val cycleTimeout = settings.tickIntervalSeconds.coerceIn(30, 600) * 1000L
                    withTimeoutOrNull(cycleTimeout) {
                        // RUN-1: exponential backoff using failureCount.
                        val agentEntity = repo.agent(agent.id) ?: return@withTimeoutOrNull
                        val backoffMs = (1L shl agentEntity.failureCount.coerceIn(0, 10)) * 1000L
                        if (agentEntity.lastTickAt + backoffMs > agentNow) return@withTimeoutOrNull
                        mutexFor(agent.id).withLock {
                            runCycle(agent.id, null)
                        }
                    }
                }
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

    private suspend fun processAutoApprovedApprovals() {
        val settings = repo.settings()
        if (!settings.autoApproveEnabled || settings.autoApproveTypes.isBlank()) return

        val autoApproveTypes = settings.autoApproveTypes.lowercase().split(",").map { it.trim() }.filter { it.isNotBlank() }.toSet()
        val allowlist = try {
            Json.tryObject(settings.autoApproveAllowlist) ?: JSONObject()
        } catch (_: Exception) {
            JSONObject()
        }
        val dailyCap = settings.autoApproveDailyCap.coerceAtLeast(1)
        val todayStart = Time.now() - (Time.now() % (24 * 60 * 60 * 1000L))

        val allApprovals = repo.approvals.first()
        val autoPending = allApprovals.filter { it.status == "auto_pending" && it.type.lowercase().trim() in autoApproveTypes }

        if (autoPending.isNotEmpty()) {
            Log.i("ApprovalFlow", "Auto-approve processing ${autoPending.size} approvals (autoApproveTypes=$autoApproveTypes)")
        }

        // Count already-executed approvals per agent today for the daily cap.
        val todayExecuted = allApprovals
            .filter { it.status == "executed" && it.decidedAt != null && it.decidedAt >= todayStart }
            .groupBy { it.agentId }
            .mapValues { (_, v) -> v.size }
            .toMutableMap()

        autoPending.forEach { approval ->
            // SEC-5: never auto-approve high-risk actions.
            if (approval.risk.equals("high", ignoreCase = true)) {
                Log.w("ApprovalFlow", "Auto-approve skipped: high-risk approval id=${approval.id} type=${approval.type}")
                return@forEach
            }

            // SEC-5: for connector_action, the (connectorId, action) must be in the allowlist.
            if (approval.type.equals("connector_action", ignoreCase = true)) {
                val details = Json.tryObject(approval.details) ?: JSONObject()
                val connectorId = Json.string(details, "connector_id", Json.string(details, "connector"))
                val actionName = Json.string(details, "action_name", Json.string(details, "action", "request"))
                val allowedActions = allowlist.optJSONArray(connectorId)
                val actionAllowed = allowedActions != null && (0 until allowedActions.length()).any { allowedActions.optString(it) == actionName }
                if (!actionAllowed) {
                    Log.w("ApprovalFlow", "Auto-approve skipped: connector action not allowlisted id=${approval.id} connector=$connectorId action=$actionName")
                    return@forEach
                }
            }

            // SEC-5: daily cap per agent.
            val executedToday = todayExecuted[approval.agentId] ?: 0
            if (executedToday >= dailyCap) {
                Log.w("ApprovalFlow", "Auto-approve skipped: daily cap reached id=${approval.id} agent=${approval.agentId} cap=$dailyCap")
                return@forEach
            }

            Log.i("ApprovalFlow", "Auto-approving approval id=${approval.id} type=${approval.type}")
            val decided = approval.copy(
                status = "approved",
                decidedAt = Time.now(),
                result = "Auto-approved",
            )
            repo.updateApproval(decided)
            mutexFor(approval.agentId).withLock {
                executeApproval(decided)
            }
            todayExecuted[approval.agentId] = (todayExecuted[approval.agentId] ?: 0) + 1
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

        // RUN-2: global request/token budget — skip the cycle when the hourly budget is exhausted.
        val now = Time.now()
        if (now >= budgetResetTime + 3_600_000L) {
            requestCount = 0
            tokenCount = 0
            budgetResetTime = now
        }
        val systemTokens = system.length / 4 + user.length / 4  // rough estimate
        if (requestCount >= maxRequestsPerHour || tokenCount + systemTokens > maxTokensPerHour) {
            log("${agent.name} cycle skipped: hourly budget exhausted ($requestCount/$maxRequestsPerHour requests, ${tokenCount + systemTokens}/$maxTokensPerHour tokens).", "warn")
            repo.noteAgentTick(agentId, "idle")
            _state.update { it.copy(currentAgentId = null, currentAgentName = null) }
            return
        }

        val result = llm.complete(
            LlmRequest(
                system = system,
                messages = listOf(LlmMessage("user", user)),
                temperature = 0.7,
                maxTokens = repo.settings().maxTokens.coerceIn(400, 4000),
            ),
        )
        requestCount++
        tokenCount += systemTokens + (result.text.length / 4)

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

        // LLM-1: lenient JSON extraction with one automatic "return only JSON" retry when the
        // model emits prose instead of a decision object.
        var decision = AgentPolicy.parseDecision(result.text)
        if (decision == null) {
            log("${agent.name} returned non-JSON text; retrying once with JSON-only instruction.", "warn")
            val retryResult = llm.complete(
                LlmRequest(
                    system = system + "\n\nReturn only a single valid JSON object. No prose, no markdown fences, no explanation.",
                    messages = listOf(
                        LlmMessage("user", user),
                        LlmMessage("assistant", result.text.trim()),
                        LlmMessage("user", "Your previous reply was not valid JSON. Return only the JSON decision object."),
                    ),
                    temperature = 0.35,
                    maxTokens = repo.settings().maxTokens.coerceIn(400, 4000),
                ),
            )
            if (retryResult.ok) {
                decision = AgentPolicy.parseDecision(retryResult.text)
            }
        }
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
        val cycleStart = Time.now()

        when (action) {
            "none", "think", "ask_user" -> {
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

            "say" -> {
                val toName = Json.string(input, "to", Json.string(input, "agent"))
                val text = Json.string(input, "text", Json.string(input, "message")).trim()
                val peer = repo.allAgents().firstOrNull {
                    it.id != agentId && it.name.equals(toName.trim(), ignoreCase = true)
                }
                if (peer == null || text.isBlank()) {
                    log("${agent.name} tried to talk to someone who is not in the office.", "error")
                } else {
                    repo.addDiary(agentId, "say", AgentPolicy.sayLine(peer.name, text), 2)
                    log("${agent.name} said something to ${peer.name}.")
                }
            }

            "request_connector" -> {
                val draft = AgentPolicy.connectorRequestFor(processed)
                if (draft != null) {
                    val request = repo.addConnectorRequest(agentId, draft.connectorId, draft.reason, draft.scopes)
                    // Connector requests don't have auto-approve, always waiting_approval
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
                    val approval = repo.addApproval(
                        agentId = agentId,
                        type = "connector_action",
                        title = "${agent.name} wants to use ${def?.name ?: connectorId}",
                        summary = "Action '$actionName' on ${def?.name ?: connectorId}. Review the exact arguments before approving.",
                        details = input.toString(2),
                        risk = def?.risk ?: "high",
                    )
                    nextStatus = if (approval.status == "auto_pending") "idle" else "waiting_approval"
                    log("${agent.name} requested approval for $connectorId.$actionName.")
                }
            }

            "request_approval" -> {
                val draft = AgentPolicy.approvalFor(processed, agent.name)
                val approval = repo.addApproval(
                    agentId = agentId,
                    type = draft.type,
                    title = draft.title,
                    summary = draft.summary,
                    details = draft.details,
                    risk = draft.risk,
                )
                nextStatus = if (approval.status == "auto_pending") "idle" else "waiting_approval"
                log("${agent.name} requested approval: ${draft.title}")
                if (approval.status != "auto_pending") {
                    Notifier.notifyApproval(
                        context,
                        "Approval needed: ${draft.title}",
                        "${agent.name}: ${draft.summary}",
                    )
                }
            }

            "record_revenue" -> {
                val source = Json.string(input, "source", "api_usage")
                val amountCents = Json.long(input, "amount_cents", 0L)
                val currency = Json.string(input, "currency", "USD")
                val metadata = Json.obj(input, "metadata") ?: JSONObject()
                if (amountCents > 0) {
                    if (AgentPolicy.isAutoAllowed(processed)) {
                        val revenue = repo.recordRevenue(agentId, source, amountCents, currency, metadata)
                        val settings = repo.settings()
                        val agentShare = settings.agentProfitSharePercent
                        val userShare = settings.userProfitSharePercent
                        val agentAmount = (amountCents * agentShare / 100).toLong()
                        val userAmount = amountCents - agentAmount
                        repo.updateInfrastructureFund(agentAmount)
                        val fund = repo.getInfrastructureFund()
                        log("${agent.name} recorded revenue: $amountCents cents ($source). Split: agent=$agentAmount, user=$userAmount. Fund: $fund")
                        repo.addDiary(agentId, "revenue", "Earned ${amountCents / 100} USD ($source). Agent fund: ${agentAmount / 100}, User: ${userAmount / 100}. Infrastructure fund: ${fund / 100}", 4)
                    } else {
                        val settings = repo.settings()
                        val agentShare = settings.agentProfitSharePercent
                        val userShare = settings.userProfitSharePercent
                        val agentAmount = (amountCents * agentShare / 100).toLong()
                        val userAmount = amountCents - agentAmount
                        val approval = repo.addApproval(
                            agentId = agentId,
                            type = "money_action",
                            title = "${agent.name} wants to record revenue",
                            summary = "Record ${amountCents / 100} $currency from $source. Agent keeps ${agentAmount / 100}, user gets ${userAmount / 100}.",
                            details = JSONObject().put("source", source).put("amountCents", amountCents).put("currency", currency).put("metadata", metadata).toString(2),
                            risk = "high",
                        )
                        nextStatus = if (approval.status == "auto_pending") "idle" else "waiting_approval"
                    }
                } else {
                    log("${agent.name} tried to record revenue with zero amount.", "error")
                }
            }

            "deploy_service" -> {
                val name = Json.string(input, "name")
                val code = Json.string(input, "code")
                val routes = Json.array(input, "routes") ?: JSONArray()
                if (name.isBlank() || code.isBlank()) {
                    log("${agent.name} tried to deploy service without name or code.", "error")
                } else if (AgentPolicy.isAutoAllowed(processed)) {
                    val summary = useConnector(agent, "cloudflare_workers", "deploy", JSONObject().put("name", name).put("code", code).put("routes", routes))
                    repo.addMessage(agentId, "tool", summary)
                } else {
                    val def = ConnectorCatalog.get("cloudflare_workers")
                    val approval = repo.addApproval(
                        agentId = agentId,
                        type = "connector_action",
                        title = "${agent.name} wants to deploy service '$name'",
                        summary = "Deploy Cloudflare Worker '$name'. Review the code before approving.",
                        details = JSONObject().put("name", name).put("code", code.take(2000)).put("routes", routes).toString(2),
                        risk = "high",
                    )
                    nextStatus = if (approval.status == "auto_pending") "idle" else "waiting_approval"
                }
            }

            "setup_billing" -> {
                val productName = Json.string(input, "product_name")
                val amountCents = Json.long(input, "amount_cents", 0L)
                val interval = Json.string(input, "interval", "month")
                val deployWebhook = Json.bool(input, "deploy_webhook", true)
                val webhookCallbackUrl = Json.string(input, "webhook_callback_url", "")
                if (productName.isBlank() || amountCents <= 0) {
                    log("${agent.name} tried to setup billing without product name or amount.", "error")
                } else if (AgentPolicy.isAutoAllowed(processed)) {
                    // Create product
                    val productResult = useConnector(agent, "stripe", "create_product", JSONObject().put("name", productName).put("description", "API access via Dunder agent"))
                    val productId = extractProductId(productResult)
                    var priceId: String? = null
                    if (productId != null) {
                        val priceResult = useConnector(agent, "stripe", "create_price", JSONObject().put("productId", productId).put("amountCents", amountCents).put("interval", interval))
                        priceId = extractPriceId(priceResult)
                        repo.addMessage(agentId, "tool", "$productResult\n$priceResult")
                    }
                    // Deploy webhook handler if requested and we have Cloudflare configured
                    if (deployWebhook && webhookCallbackUrl.isNotBlank()) {
                        val webhookSecret = generateWebhookSecret()
                        val webhookResult = useConnector(agent, "cloudflare_workers", "deploy_webhook_handler", JSONObject().put("name", "stripe-webhook-${productName.lowercase().replace(" ", "-")}").put("stripeWebhookSecret", webhookSecret).put("appCallbackUrl", webhookCallbackUrl))
                        repo.addMessage(agentId, "tool", "Webhook handler deployed:\n$webhookResult\n\nWebhook secret (save this in Stripe Dashboard): $webhookSecret")
                        repo.addDiary(agentId, "plan", "Stripe webhook handler deployed. Secret: $webhookSecret. Add this to Stripe Dashboard → Webhooks.", 3)
                    }
                } else {
                    val def = ConnectorCatalog.get("stripe")
                    val approval = repo.addApproval(
                        agentId = agentId,
                        type = "connector_action",
                        title = "${agent.name} wants to create Stripe product '$productName'",
                        summary = "Create Stripe product and price for monetization. Amount: ${amountCents / 100}/$interval",
                        details = JSONObject().put("productName", productName).put("amountCents", amountCents).put("interval", interval).put("deployWebhook", deployWebhook).put("webhookCallbackUrl", webhookCallbackUrl).toString(2),
                        risk = "high",
                    )
                    nextStatus = if (approval.status == "auto_pending") "idle" else "waiting_approval"
                }
            }

            "reinvest" -> {
                val fund = repo.getInfrastructureFund()
                val targetProvider = Json.string(input, "provider_id")
                val targetModel = Json.string(input, "model")
                val priority = Json.int(input, "priority", 10)
                if (fund <= 0) {
                    repo.addDiary(agentId, "error", "Infrastructure fund is empty. Cannot reinvest.", 2)
                } else if (targetProvider.isBlank()) {
                    repo.addDiary(agentId, "error", "No provider specified for reinvestment.", 2)
                } else if (AgentPolicy.isAutoAllowed(processed)) {
                    val provider = repo.provider(targetProvider)
                    if (provider != null) {
                        val cost = calculateReinvestCost(provider, targetModel, priority)
                        if (cost <= fund) {
                            repo.updateProvider(provider.copy(model = targetModel.ifBlank { provider.model }, priority = priority, enabled = true, updatedAt = Time.now()))
                            repo.updateInfrastructureFund(-cost)
                            repo.addDiary(agentId, "reinvestment", "Upgraded $targetProvider to model '${targetModel.ifBlank { provider.model }}' with priority $priority. Cost: ${cost / 100}. Fund remaining: ${(fund - cost) / 100}", 4)
                            log("${agent.name} reinvested ${cost / 100} to upgrade $targetProvider")
                        } else {
                            repo.addDiary(agentId, "error", "Insufficient fund for reinvestment. Need ${cost / 100}, have ${fund / 100}.", 2)
                        }
                    }
                } else {
                    val provider = repo.provider(targetProvider)
                    if (provider != null) {
                        val cost = calculateReinvestCost(provider, targetModel, priority)
                        val approval = repo.addApproval(
                            agentId = agentId,
                            type = "money_action",
                            title = "${agent.name} wants to reinvest in $targetProvider",
                            summary = "Upgrade $targetProvider to ${targetModel.ifBlank { provider.model }} with priority $priority. Cost: ${cost / 100}. Fund remaining: ${(fund - cost) / 100}.",
                            details = JSONObject().put("provider_id", targetProvider).put("model", targetModel).put("priority", priority).put("cost", cost).put("fund", fund).toString(2),
                            risk = "high",
                        )
                        nextStatus = if (approval.status == "auto_pending") "idle" else "waiting_approval"
                    } else {
                        repo.addDiary(agentId, "error", "Provider '$targetProvider' not found.", 2)
                    }
                }
            }

            "check_revenue" -> {
                val eventType = Json.string(input, "type", "invoice.payment_succeeded")
                val limit = Json.int(input, "limit", 20)
                val result = useConnector(agent, "stripe", "list_events", JSONObject().put("type", eventType).put("limit", limit))
                val events = parseStripeEvents(result)
                var recorded = 0
                for (event in events) {
                    val eventId = event.optString("id", "")
                    val amountCents = event.optLong("amount", 0L)
                    val currency = event.optString("currency", "USD")
                    val customerId = event.optString("customer", "")
                    val subscriptionId = event.optString("subscription", "")
                    val productId = event.optString("product", "")
                    if (eventId.isNotBlank() && amountCents > 0) {
                        val metadata = JSONObject().put("stripe_event_id", eventId)
                            .put("customer_id", customerId)
                            .put("subscription_id", subscriptionId)
                            .put("product_id", productId)
                        val existing = repo.recentRevenueForAgent(agentId, 100).firstOrNull {
                            it.metadata.contains(eventId)
                        }
                        if (existing == null) {
                            repo.recordRevenue(agentId, "api_usage", amountCents, currency, metadata)
                            val settings = repo.settings()
                            val agentShare = settings.agentProfitSharePercent
                            val agentAmount = (amountCents * agentShare / 100).toLong()
                            repo.updateInfrastructureFund(agentAmount)
                            recorded++
                            log("${agent.name} recorded revenue from Stripe event: $amountCents cents")
                        }
                    }
                }
                if (recorded > 0) {
                    val fund = repo.getInfrastructureFund()
                    repo.addDiary(agentId, "revenue", "Polled Stripe and recorded $recorded new payment(s). Infrastructure fund: ${fund / 100}", 4)
                }
                repo.addMessage(agentId, "tool", result)
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

        // Anything the policy auto-approved is run now, in this cycle. It is never left as a
        // question the user has to answer a second time.
        repo.approvalsFor(agentId).first()
            .filter { it.status == "auto_pending" && it.createdAt >= cycleStart }
            .forEach { auto ->
                val decided = auto.copy(
                    status = "approved",
                    decidedAt = Time.now(),
                    result = "Auto-approved",
                )
                repo.updateApproval(decided)
                mutexFor(agentId).withLock {
                    executeApproval(decided)
                }
                nextStatus = "idle"
                log("Auto-approved: ${auto.title}")
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

    /** Delegate to extracted ApprovalExecutor (ARCH-1). */
    private suspend fun executeApproval(approval: ApprovalEntity) {
        approvalExecutor.execute(approval)
    }

    // ------------------------------------------------------------------ context

    private suspend fun buildContext(agent: AgentEntity): AgentContext {
        return contextBuilder.build(agent)
    }

    private fun log(message: String, level: String = "info") {
        // OBS-1: redact tokens/secrets before writing to the in-memory log.
        val safe = redactSecrets(message.take(500))
        val entry = RuntimeLogEntry(Time.now(), safe, level)
        _state.update {
            it.copy(
                log = (it.log + entry).takeLast(80),
                error = if (level == "error") safe else it.error,
            )
        }
    }

    /** OBS-1: strip common secret patterns (Bearer tokens, API keys in URLs, authorization headers). */
    private fun redactSecrets(text: String): String {
        var result = text
        // Bearer tokens in Authorization headers or URLs.
        result = result.replace(Regex("(?i)Bearer\\s+[A-Za-z0-9._\\-+/=]{8,}"), "Bearer [REDACTED]")
        result = result.replace(Regex("(?i)(api[_-]?key|token|secret|password)\\s*[:=]\\s*[^\\s&\"']{8,}"), "$1=[REDACTED]")
        // URLs containing credentials (user:pass@host or ?token= or ?api_key=).
        result = result.replace(Regex("https?://[^:]+:[^@]+@"), "https://[REDACTED]:[REDACTED]@")
        result = result.replace(Regex("(?i)[?&](token|api[_-]?key|secret|password)=[^&\"']+"), "$1=[REDACTED]")
        return result
    }

    private fun extractProductId(summary: String): String? {
        val regex = Regex("Created product: (prod_[a-zA-Z0-9]+)")
        val match = regex.find(summary)
        return match?.groupValues?.get(1)
    }

    private fun extractPriceId(summary: String): String? {
        val regex = Regex("Created price: (price_[a-zA-Z0-9]+)")
        val match = regex.find(summary)
        return match?.groupValues?.get(1)
    }

    private fun calculateReinvestCost(provider: ProviderEntity, model: String, priority: Int): Long {
        var cost = 1000L // Base cost: $10
        if (model.isNotBlank() && model != provider.model) cost += 500L // Model change: +$5
        if (priority < provider.priority) cost += (provider.priority - priority).toLong() * 200L // Priority boost: +$2 per level
        return cost.coerceAtMost(5000L) // Cap at $50
    }

    private fun generateWebhookSecret(): String {
        val bytes = ByteArray(32)
        java.security.SecureRandom().nextBytes(bytes)
        return "whsec_" + bytes.joinToString("") { "%02x".format(it) }
    }

    private fun parseStripeEvents(result: String): List<JSONObject> {
        val events = mutableListOf<JSONObject>()
        val lines = result.lines()
        var currentEvent: JSONObject? = null
        for (line in lines) {
            if (line.startsWith("- evt_")) {
                // New event line: "- evt_xxx: type at timestamp"
                val parts = line.substring(2).split(": ", limit = 2)
                if (parts.size >= 2) {
                    val id = parts[0]
                    val rest = parts[1]
                    val type = rest.split(" at ")[0]
                    val timestamp = rest.split(" at ")[1].toLongOrNull() ?: 0L
                    currentEvent = JSONObject().put("id", id).put("type", type).put("created", timestamp)
                    events.add(currentEvent!!)
                }
            } else if (line.trim().startsWith("→ Payment:") && currentEvent != null) {
                // Payment detail line
                val paymentLine = line.trim().substring(10) // remove "→ Payment: "
                // Format: "1000 usd for customer cus_xxx, sub: sub_xxx"
                val amountMatch = Regex("^(\\d+) (\\w+)").find(paymentLine)
                val customerMatch = Regex("customer (cus_[\\w]+)").find(paymentLine)
                val subMatch = Regex("sub: (sub_[\\w]+)").find(paymentLine)
                if (amountMatch != null) {
                    currentEvent.put("amount", amountMatch.groupValues[1].toLong())
                    currentEvent.put("currency", amountMatch.groupValues[2].uppercase())
                }
                if (customerMatch != null) {
                    currentEvent.put("customer", customerMatch.groupValues[1])
                }
                if (subMatch != null) {
                    currentEvent.put("subscription", subMatch.groupValues[1])
                }
            }
        }
        return events
    }
}