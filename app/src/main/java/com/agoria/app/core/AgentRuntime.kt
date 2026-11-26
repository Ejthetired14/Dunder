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
import kotlinx.coroutines.delay
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.flow.update
import kotlinx.coroutines.isActive
import kotlinx.coroutines.launch
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

        // Process auto-approved approvals that haven't been executed yet
        processAutoApprovedApprovals()

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

    private suspend fun processAutoApprovedApprovals() {
        val settings = repo.settings()
        if (!settings.autoApproveEnabled || settings.autoApproveTypes.isBlank()) return

        val autoApproveTypes = settings.autoApproveTypes.lowercase().split(",").map { it.trim() }.filter { it.isNotBlank() }.toSet()
        val allApprovals = repo.approvals.first()
        val autoPending = allApprovals.filter { it.status == "auto_pending" && it.type.lowercase().trim() in autoApproveTypes }

        if (autoPending.isNotEmpty()) {
            Log.i("ApprovalFlow", "Auto-approve processing ${autoPending.size} approvals (autoApproveTypes=$autoApproveTypes)")
        }
        autoPending.forEach { approval ->
            Log.i("ApprovalFlow", "Auto-approving approval id=${approval.id} type=${approval.type}")
            val decided = approval.copy(
                status = "approved",
                decidedAt = Time.now(),
                result = "Auto-approved",
            )
            repo.updateApproval(decided)
            executeApproval(decided)
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
                    val revenue = repo.recordRevenue(agentId, source, amountCents, currency, metadata)
                    val settings = repo.settings()
                    val agentShare = settings.agentProfitSharePercent
                    val userShare = settings.userProfitSharePercent
                    val agentAmount = (amountCents * agentShare / 100).toLong()
                    val userAmount = amountCents - agentAmount
                    repo.updateInfrastructureFund(agentAmount)
                    val fund = repo.getInfrastructureFund()
                    log("${agent.name} recorded revenue: $amountCents cents ($source). Split: agent=$agentAmount, user=$userAmount. Fund: $fund")
                    repo.addDiary(agentId, "revenue", "Earned \${amountCents/100} USD ($source). Agent fund: \${agentAmount/100}, User: \${userAmount/100}. Infrastructure fund: \${fund/100}", 4)
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
                        summary = "Create Stripe product and price for monetization. Amount: \${amountCents/100}/$interval",
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
                } else {
                    val provider = repo.provider(targetProvider)
                    if (provider != null) {
                        val cost = calculateReinvestCost(provider, targetModel, priority)
                        if (cost <= fund) {
                            repo.updateProvider(provider.copy(model = targetModel.ifBlank { provider.model }, priority = priority, enabled = true, updatedAt = Time.now()))
                            repo.updateInfrastructureFund(-cost)
                            repo.addDiary(agentId, "reinvestment", "Upgraded $targetProvider to model '\${targetModel.ifBlank { provider.model }}' with priority $priority. Cost: \${cost/100}. Fund remaining: \${(fund-cost)/100}", 4)
                            log("${agent.name} reinvested \${cost/100} to upgrade $targetProvider")
                        } else {
                            repo.addDiary(agentId, "error", "Insufficient fund for reinvestment. Need \${cost/100}, have \${fund/100}.", 2)
                        }
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
                    repo.addDiary(agentId, "revenue", "Polled Stripe and recorded $recorded new payment(s). Infrastructure fund: \${fund/100}", 4)
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
                executeApproval(decided)
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

    private suspend fun executeApproval(approval: ApprovalEntity) {
        val agent = repo.agent(approval.agentId)
            ?: return run {
                repo.updateApproval(approval.copy(status = "failed", decidedAt = Time.now(), result = "Agent no longer exists"))
            }

        val outcome = when (approval.type) {
            "build_project" -> {
                // The shared project folder has one owner. Other agents can still plan, write tasks
                // and report progress; only the lead agent writes into the project.
                val project = repo.ensureProject()
                val lead = project.leadAgentId
                if (lead.isNotBlank() && lead != agent.id) {
                    val leadName = repo.agent(lead)?.name ?: "the lead agent"
                    "Build skipped: only $leadName can build in ${project.name}. Ask $leadName to run it."
                } else {
                    val build = buildRunner.build(agent, approval)
                    if (build != null) {
                        "Build complete: ${build.name} (${build.fileCount} files)"
                    } else {
                        "Build failed: no files were generated."
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
            !outcome.startsWith("Build skipped") &&
            !outcome.contains("no longer exists") && !outcome.startsWith("Approval data")
        repo.updateApproval(
            approval.copy(
                status = if (ok) "executed" else "failed",
                decidedAt = Time.now(),
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
        val diary = repo.recentDiaryForAgent(agent.id, 18)
        val memories = repo.memoryDao().recentForAgent(agent.id, 14)
","oldString">
        val memories = repo.memoriesFor(agent.id).first().take(14)
","path":"/workspace/app/app/src/main/java/com/agoria/app/core/AgentRuntime.kt        val messages = repo.messagesFor(agent.id).first().takeLast(14)
        val tasks = repo.tasksFor(agent.id).first()
        val approvals = repo.approvalsFor(agent.id).first()
        val builds = repo.buildsFor(agent.id).first()
        val allowed = agent.allowedConnectors.split(',').map { it.trim() }.filter { it.isNotBlank() }
        val connectors = repo.connectors.first()
            .filter { it.status != "disabled" && (allowed.isEmpty() || it.id in allowed) }
            .map { connector ->
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

        // Recent talk between agents (diary kind "say") - use limited query instead of loading all diaries
        val chatter = repo.recentSayEntries(50)
            .filter { it.kind == "say" }
            .mapNotNull { e ->
                val a = repo.agent(e.agentId) ?: return@mapNotNull null
                val m = AgentPolicy.SAY_REGEX.find(e.content.trim()) ?: return@mapNotNull null
                ChatterLine(a.name, m.groupValues[1].trim(), m.groupValues[2].trim(), e.createdAt)
            }
            .filter { it.fromName == agent.name || it.toName.equals(agent.name, ignoreCase = true) }
            .sortedByDescending { it.at }
            .take(8)
            .reversed()
","oldString">
        // Recent talk between agents (diary kind "say")
        val chatter = repo.agents.first().flatMap { a ->
            repo.diaryFor(a.id).first()
                .filter { it.kind == "say" }
                .mapNotNull { e ->
                    val m = AgentPolicy.SAY_REGEX.find(e.content.trim()) ?: return@mapNotNull null
                    ChatterLine(a.name, m.groupValues[1].trim(), m.groupValues[2].trim(), e.createdAt)
                }
        }
            .filter { it.fromName == agent.name || it.toName.equals(agent.name, ignoreCase = true) }
            .sortedByDescending { it.at }
            .take(8)
            .reversed()
","path":"/workspace/app/app/src/main/java/com/agoria/app/core/AgentRuntime.kt
        val agentRevenue = repo.totalRevenueForAgent(agent.id)
        val infraFund = repo.getInfrastructureFund()

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
            agentTotalRevenueCents = agentRevenue,
            infrastructureFundCents = infraFund,
            agentProfitSharePercent = settings.agentProfitSharePercent,
            userProfitSharePercent = settings.userProfitSharePercent,
            chatter = chatter,
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