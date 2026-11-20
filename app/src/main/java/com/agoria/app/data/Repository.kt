package com.agoria.app.data

import android.content.Context
import androidx.room.withTransaction
import com.agoria.app.core.ConnectorCatalog
import com.agoria.app.core.ConnectorDef
import com.agoria.app.core.ProviderCatalog
import com.agoria.app.core.ProviderSpec
import com.agoria.app.data.RevenueEntity
import com.agoria.app.util.Json
import com.agoria.app.util.SecretCipher
import com.agoria.app.util.Time
import org.json.JSONArray
import org.json.JSONObject
import java.util.UUID

class Repository(
    private val db: AppDatabase,
    private val context: Context,
) {

    val agents = db.agentDao().observeAll()
    val diary = db.diaryDao().observeAll()
    val tasks = db.taskDao().observeAll()
    val approvals = db.approvalDao().observeAll()
    val pendingApprovals = db.approvalDao().observePending()
    val connectors = db.connectorDao().observeAll()
    val connectorRequests = db.connectorRequestDao().observeAll()
    val pendingConnectorRequests = db.connectorRequestDao().observePending()
    val builds = db.buildDao().observeAll()
    val providers = db.providerDao().observeAll()
    val settingsFlow = db.settingsDao().observe()
    val memories = db.memoryDao().observeAll()
    val connectorLogs = db.connectorLogDao().observeRecent(50)

    fun diaryFor(agentId: String) = db.diaryDao().observeForAgent(agentId)
    fun messagesFor(agentId: String) = db.messageDao().observeForAgent(agentId)
    fun tasksFor(agentId: String) = db.taskDao().observeForAgent(agentId)
    fun approvalsFor(agentId: String) = db.approvalDao().observeForAgent(agentId)
    fun buildsFor(agentId: String) = db.buildDao().observeForAgent(agentId)
    fun memoriesFor(agentId: String) = db.memoryDao().observeForAgent(agentId)

    suspend fun seedIfNeeded() {
        db.withTransaction {
        var current = db.settingsDao().get()
        if (current == null) {
            current = SeedData.settings(Time.now())
            db.settingsDao().upsert(current)
        }
        val settings = current
        if (settings.seedVersion >= 1) return@withTransaction

        val now = Time.now()

        if (db.agentDao().getAll().isEmpty()) {
            SeedData.agents(now).forEach { db.agentDao().insert(it) }
        }
        if (db.providerDao().getAll().isEmpty()) {
            SeedData.providers(now).forEach { db.providerDao().insert(it) }
        }
        if (db.connectorDao().get("wikipedia") == null) {
            SeedData.connectors(now).forEach { db.connectorDao().insert(it) }
        }
        if (db.diaryDao().recent(1).isEmpty()) {
            SeedData.diary(now).forEach { db.diaryDao().insert(it) }
        }
        if (db.messageDao().recentForAgent("seed-michael", 1).isEmpty()) {
            SeedData.messages(now).forEach { db.messageDao().insert(it) }
        }
        if (db.memoryDao().recentForAgent("seed-michael", 1).isEmpty()) {
            SeedData.memories(now).forEach { db.memoryDao().insert(it) }
        }

        db.settingsDao().upsert(settings.copy(seedVersion = 1))
        }
        syncConnectorCatalog()
    }

    /**
     * Keeps the installed connectors aligned with [ConnectorCatalog]: rows for connectors that no
     * longer exist are removed, and missing ones are added or refreshed. Without this, an upgrade
     * keeps whatever the old catalog seeded and every removed connector stays broken forever.
     */
    suspend fun syncConnectorCatalog() {
        val existing = db.connectorDao().getAll().associateBy { it.id }
        existing.keys
            .filter { ConnectorCatalog.get(it) == null }
            .forEach { db.connectorDao().delete(it) }

        ConnectorCatalog.all.forEach { def ->
            val row = existing[def.id]
            if (row == null) {
                db.connectorDao().insert(
                    ConnectorEntity(
                        id = def.id,
                        name = def.name,
                        category = def.category,
                        status = ConnectorCatalog.defaultStatus(def),
                        configEnc = null,
                        scopes = ConnectorCatalog.defaultScopes(def),
                        lastUsed = null,
                        lastError = null,
                        createdAt = Time.now(),
                    ),
                )
                return@forEach
            }
            val status = when {
                row.status == "disabled" -> "disabled"
                ConnectorCatalog.defaultStatus(def) == "ready" -> "ready"
                row.configEnc != null -> "configured"
                else -> row.status
            }
            if (row.name != def.name || row.category != def.category || row.status != status) {
                db.connectorDao().update(
                    row.copy(name = def.name, category = def.category, status = status),
                )
            }
        }
    }

    suspend fun reset() {
        db.clearAllTables()
        seedIfNeeded()
    }

    // ---------------------------------------------------------------- settings

    suspend fun settings(): SettingsEntity {
        db.settingsDao().get()?.let { return it }
        return SeedData.settings(Time.now()).also { db.settingsDao().upsert(it) }
    }

    suspend fun updateSettings(block: (SettingsEntity) -> SettingsEntity) {
        val current = settings()
        db.settingsDao().upsert(block(current))
    }

    // ------------------------------------------------------------------ agents

    suspend fun agent(id: String): AgentEntity? = db.agentDao().get(id)

    suspend fun allAgents(): List<AgentEntity> = db.agentDao().getAll()

    suspend fun upsertAgent(agent: AgentEntity) = db.agentDao().insert(agent)

    suspend fun updateAgent(agent: AgentEntity) = db.agentDao().update(agent)

    suspend fun deleteAgent(id: String) = db.agentDao().delete(id)

    suspend fun setAgentStatus(id: String, status: String, error: String? = null) {
        val agent = db.agentDao().get(id) ?: return
        db.agentDao().update(
            agent.copy(
                status = status,
                lastError = error,
                updatedAt = Time.now(),
            ),
        )
    }

    suspend fun noteAgentTick(id: String, status: String, error: String? = null) {
        val agent = db.agentDao().get(id) ?: return
        db.agentDao().update(
            agent.copy(
                status = status,
                lastTickAt = Time.now(),
                lastError = error,
                failureCount = if (error == null) 0 else (agent.failureCount + 1).coerceAtMost(99),
                updatedAt = Time.now(),
            ),
        )
    }

    // ------------------------------------------------------------------- diary

    suspend fun addDiary(
        agentId: String,
        kind: String,
        content: String,
        importance: Int = 2,
        pinned: Boolean = false,
    ): DiaryEntryEntity {
        val entry = DiaryEntryEntity(
            id = UUID.randomUUID().toString(),
            agentId = agentId,
            kind = kind,
            content = content,
            importance = importance.coerceIn(1, 5),
            pinned = pinned,
            createdAt = Time.now(),
        )
        db.diaryDao().insert(entry)
        return entry
    }

    // ---------------------------------------------------------------- messages

    suspend fun addMessage(agentId: String, role: String, content: String): MessageEntity {
        val message = MessageEntity(
            id = UUID.randomUUID().toString(),
            agentId = agentId,
            role = role,
            content = content,
            createdAt = Time.now(),
        )
        db.messageDao().insert(message)
        return message
    }

    suspend fun recentMessages(agentId: String, limit: Int): List<MessageEntity> =
        db.messageDao().recentForAgent(agentId, limit)

    // ------------------------------------------------------------------- tasks

    suspend fun addTask(
        agentId: String,
        title: String,
        description: String,
        status: String = "active",
    ): TaskEntity {
        val task = TaskEntity(
            id = UUID.randomUUID().toString(),
            agentId = agentId,
            title = title,
            description = description,
            status = status,
            progress = if (status == "done") 100 else 0,
            createdAt = Time.now(),
            updatedAt = Time.now(),
        )
        db.taskDao().insert(task)
        return task
    }

    suspend fun updateTask(task: TaskEntity) = db.taskDao().update(task)

    suspend fun task(id: String): TaskEntity? = db.taskDao().get(id)

    suspend fun completeTask(id: String, result: String?) {
        val task = db.taskDao().get(id) ?: return
        db.taskDao().update(
            task.copy(
                status = "done",
                progress = 100,
                description = if (result.isNullOrBlank()) task.description else task.description + "\n\nResult: " + result,
                updatedAt = Time.now(),
            ),
        )
    }

    // --------------------------------------------------------------- approvals

    suspend fun addApproval(
        agentId: String,
        type: String,
        title: String,
        summary: String,
        details: String,
        risk: String,
    ): ApprovalEntity {
        val s = settings()
        val autoApprove = s.autoApproveEnabled && s.autoApproveTypes.isNotBlank() && type in s.autoApproveTypes.split(",").map { it.trim() }
        
        val approval = ApprovalEntity(
            id = UUID.randomUUID().toString(),
            agentId = agentId,
            type = type,
            title = title,
            summary = summary,
            details = details,
            risk = risk,
            // With auto-approve the policy has already answered, so the row is never shown as a
            // pending question. The runtime executes it in the same cycle and rewrites the status.
            status = if (autoApprove) "auto_pending" else "pending",
            createdAt = Time.now(),
            decidedAt = if (autoApprove) Time.now() else null,
            result = if (autoApprove) "Auto-approved" else null,
        )
        db.approvalDao().insert(approval)
        return approval
    }

    suspend fun approval(id: String): ApprovalEntity? = db.approvalDao().get(id)

    suspend fun updateApproval(approval: ApprovalEntity) = db.approvalDao().update(approval)

    suspend fun pendingApprovalsFor(agentId: String): List<ApprovalEntity> =
        db.approvalDao().pendingForAgent(agentId)

    suspend fun pendingConnectorRequestsFor(agentId: String): List<ConnectorRequestEntity> =
        db.connectorRequestDao().pendingForAgent(agentId)

    suspend fun connectorRequestForConnector(connectorId: String): ConnectorRequestEntity? =
        db.connectorRequestDao().pendingForConnector(connectorId)

    // -------------------------------------------------------- connector requests

    suspend fun addConnectorRequest(
        agentId: String,
        connectorId: String,
        reason: String,
        scopes: String,
    ): ConnectorRequestEntity {
        val request = ConnectorRequestEntity(
            id = UUID.randomUUID().toString(),
            agentId = agentId,
            connectorId = connectorId,
            reason = reason,
            scopes = scopes,
            status = "pending",
            createdAt = Time.now(),
            decidedAt = null,
        )
        db.connectorRequestDao().insert(request)
        return request
    }

    suspend fun connectorRequest(id: String): ConnectorRequestEntity? = db.connectorRequestDao().get(id)

    suspend fun updateConnectorRequest(request: ConnectorRequestEntity) =
        db.connectorRequestDao().update(request)

    // --------------------------------------------------------------- connectors

    suspend fun connector(id: String): ConnectorEntity? = db.connectorDao().get(id)

    suspend fun addConnector(def: ConnectorDef, status: String = ConnectorCatalog.defaultStatus(def)): ConnectorEntity {
        val existing = db.connectorDao().get(def.id)
        if (existing != null) return existing
        val connector = ConnectorEntity(
            id = def.id,
            name = def.name,
            category = def.category,
            status = status,
            configEnc = null,
            scopes = ConnectorCatalog.defaultScopes(def),
            lastUsed = null,
            lastError = null,
            createdAt = Time.now(),
        )
        db.connectorDao().insert(connector)
        return connector
    }

    suspend fun updateConnector(connector: ConnectorEntity) = db.connectorDao().update(connector)

    suspend fun connectorConfig(id: String): JSONObject? {
        val connector = db.connectorDao().get(id) ?: return null
        val decrypted = SecretCipher.decrypt(connector.configEnc) ?: return null
        return Json.tryObject(decrypted)
    }

    suspend fun saveConnectorConfig(id: String, config: JSONObject) {
        val connector = db.connectorDao().get(id) ?: return
        db.connectorDao().update(
            connector.copy(
                configEnc = SecretCipher.encrypt(config.toString()),
                status = "configured",
                lastError = null,
            ),
        )
    }

    suspend fun clearConnectorConfig(id: String) {
        val connector = db.connectorDao().get(id) ?: return
        val def = ConnectorCatalog.get(id)
        db.connectorDao().update(
            connector.copy(
                configEnc = null,
                status = if (def == null) "needs_setup" else ConnectorCatalog.defaultStatus(def),
                lastError = null,
            ),
        )
    }

    suspend fun recordConnectorUse(id: String, error: String? = null) {
        val connector = db.connectorDao().get(id) ?: return
        db.connectorDao().update(
            connector.copy(
                lastUsed = Time.now(),
                lastError = error,
                status = if (error == null) connector.status else "error",
            ),
        )
    }

    suspend fun addConnectorLog(
        connectorId: String,
        agentId: String?,
        action: String,
        status: String,
        summary: String,
    ) {
        if (db.connectorDao().get(connectorId) == null) return
        db.connectorLogDao().insert(
            ConnectorLogEntity(
                id = UUID.randomUUID().toString(),
                connectorId = connectorId,
                agentId = agentId,
                action = action,
                status = status,
                summary = summary.take(1000),
                createdAt = Time.now(),
            ),
        )
    }

    // ---------------------------------------------------------------- providers

    suspend fun provider(id: String): ProviderEntity? = db.providerDao().get(id)

    suspend fun dbProviderList(): List<ProviderEntity> = db.providerDao().getAll()

    suspend fun updateProvider(provider: ProviderEntity) = db.providerDao().update(provider)

    suspend fun setProviderEnabled(id: String, enabled: Boolean) {
        val provider = db.providerDao().get(id) ?: return
        db.providerDao().update(provider.copy(enabled = enabled, updatedAt = Time.now()))
    }

    suspend fun setProviderModel(id: String, model: String) {
        val provider = db.providerDao().get(id) ?: return
        db.providerDao().update(provider.copy(model = model, updatedAt = Time.now()))
    }

    suspend fun saveProviderKey(id: String, key: String) {
        val provider = db.providerDao().get(id) ?: return
        db.providerDao().update(
            provider.copy(
                apiKeyEnc = SecretCipher.encrypt(key),
                updatedAt = Time.now(),
            ),
        )
    }

    suspend fun providerKey(id: String): String? {
        val provider = db.providerDao().get(id) ?: return null
        return SecretCipher.decrypt(provider.apiKeyEnc)?.ifBlank { null }
    }

    suspend fun saveProviderExtra(id: String, extra: String) {
        val provider = db.providerDao().get(id) ?: return
        db.providerDao().update(
            provider.copy(
                extra = SecretCipher.encrypt(extra),
                updatedAt = Time.now(),
            ),
        )
    }

    suspend fun providerExtra(id: String): String? {
        val provider = db.providerDao().get(id) ?: return null
        return SecretCipher.decrypt(provider.extra)?.ifBlank { null }
    }

    suspend fun recordProviderResult(id: String, status: String, latency: Long?, error: String?) {
        val provider = db.providerDao().get(id) ?: return
        db.providerDao().update(
            provider.copy(
                lastStatus = status,
                lastLatencyMs = latency,
                lastError = error?.take(800),
                updatedAt = Time.now(),
            ),
        )
    }

    // ------------------------------------------------------------------ memory

    suspend fun addMemory(agentId: String, kind: String, content: String, importance: Int = 2): MemoryEntity {
        val memory = MemoryEntity(
            id = UUID.randomUUID().toString(),
            agentId = agentId,
            kind = kind,
            content = content,
            importance = importance.coerceIn(1, 5),
            createdAt = Time.now(),
        )
        db.memoryDao().insert(memory)
        return memory
    }

    suspend fun recentMemories(agentId: String, limit: Int): List<MemoryEntity> =
        db.memoryDao().recentForAgent(agentId, limit)

    // ------------------------------------------------------------------ builds

    suspend fun addBuild(
        agentId: String,
        name: String,
        description: String,
        zipPath: String,
        fileCount: Int,
    ): BuildEntity {
        val build = BuildEntity(
            id = UUID.randomUUID().toString(),
            agentId = agentId,
            name = name,
            description = description,
            fileCount = fileCount,
            zipPath = zipPath,
            status = "building",
            createdAt = Time.now(),
            completedAt = null,
        )
        db.buildDao().insert(build)
        return build
    }

    suspend fun build(id: String): BuildEntity? = db.buildDao().get(id)

    // Paged queries for builds
    suspend fun pagedBuildsForAgent(agentId: String, limit: Int, offset: Int): List<BuildEntity> =
        db.buildDao().pagedForAgent(agentId, limit, offset)

    suspend fun countBuildsForAgent(agentId: String): Int =
        db.buildDao().countForAgent(agentId)

    // Paged queries for diary
    suspend fun pagedDiaryForAgent(agentId: String, limit: Int, offset: Int): List<DiaryEntryEntity> =
        db.diaryDao().pagedForAgent(agentId, limit, offset)

    suspend fun countDiaryForAgent(agentId: String): Int =
        db.diaryDao().countForAgent(agentId)

    // Paged queries for messages
    suspend fun pagedMessagesForAgent(agentId: String, limit: Int, offset: Int): List<MessageEntity> =
        db.messageDao().pagedForAgent(agentId, limit, offset)

    suspend fun countMessagesForAgent(agentId: String): Int =
        db.messageDao().countForAgent(agentId)

    // ------------------------------------------------------------------ export

    suspend fun exportSnapshot(): JSONObject {
        val root = JSONObject()
        root.put("app", "Dunder")
        root.put("version", 1)
        root.put("exportedAt", Time.iso(Time.now()))
        val s = settings()
        root.put(
            "settings",
            JSONObject().apply {
                put("officeName", s.officeName)
                put("userName", s.userName)
                put("autonomyEnabled", s.autonomyEnabled)
                put("tickIntervalSeconds", s.tickIntervalSeconds)
                put("agentCooldownSeconds", s.agentCooldownSeconds)
            },
        )
        root.put(
            "agents",
            JSONArray().apply {
                allAgents().forEach {
                    put(
                        JSONObject().apply {
                            put("id", it.id)
                            put("name", it.name)
                            put("role", it.role)
                            put("persona", it.persona)
                            put("goal", it.goal)
                            put("status", it.status)
                            put("autonomy", it.autonomy)
                            put("memory", it.memory)
                            put("createdAt", it.createdAt)
                        },
                    )
                }
            },
        )
        root.put(
            "providers",
            JSONArray().apply {
                ProviderCatalog.all.forEach { spec ->
                    put(
                        JSONObject().apply {
                            put("id", spec.id)
                            put("name", spec.name)
                            put("baseUrl", spec.baseUrl)
                            put("freeNote", spec.freeNote)
                        },
                    )
                }
            },
        )
        root.put(
            "connectorCatalog",
            JSONArray().apply {
                ConnectorCatalog.all.forEach { def ->
                    put(
                        JSONObject().apply {
                            put("id", def.id)
                            put("name", def.name)
                            put("description", def.description)
                            put("risk", def.risk)
                            put("actions", JSONArray(def.actions.map { it.id }))
                        },
                    )
                }
            },
        )
        return root
    }

    suspend fun importSnapshot(json: JSONObject): Boolean {
        try {
            // Import settings
            if (json.has("settings")) {
                val settingsJson = json.getJSONObject("settings")
                updateSettings { s ->
                    s.copy(
                        officeName = settingsJson.optString("officeName", s.officeName),
                        userName = settingsJson.optString("userName", s.userName),
                        autonomyEnabled = settingsJson.optBoolean("autonomyEnabled", s.autonomyEnabled),
                        tickIntervalSeconds = settingsJson.optInt("tickIntervalSeconds", s.tickIntervalSeconds),
                        agentCooldownSeconds = settingsJson.optInt("agentCooldownSeconds", s.agentCooldownSeconds),
                    )
                }
            }

            // Import agents
            if (json.has("agents")) {
                val agentsArray = json.getJSONArray("agents")
                for (i in 0 until agentsArray.length()) {
                    val a = agentsArray.getJSONObject(i)
                    val existing = agent(a.getString("id"))
                    val now = Time.now()
                    val agentEntity = com.agoria.app.data.AgentEntity(
                        id = a.getString("id"),
                        name = a.getString("name"),
                        role = a.getString("role"),
                        persona = a.optString("persona", ""),
                        goal = a.optString("goal", ""),
                        avatarIndex = existing?.avatarIndex ?: 0,
                        status = a.optString("status", "idle"),
                        autonomy = a.optBoolean("autonomy", true),
                        memory = a.optString("memory", ""),
                        lastTickAt = 0L,
                        failureCount = 0,
                        lastError = null,
                        createdAt = a.optLong("createdAt", now),
                        updatedAt = now,
                    )
                    upsertAgent(agentEntity)
                }
            }

            return true
        } catch (e: Exception) {
            e.printStackTrace()
            return false
        }
    }

    // ------------------------------------------------------------------ revenue

    val revenue = db.revenueDao().observeAll()

    suspend fun revenueForAgent(agentId: String) = db.revenueDao().observeForAgent(agentId)

    suspend fun recentRevenueForAgent(agentId: String, limit: Int = 20): List<RevenueEntity> =
        db.revenueDao().recentForAgent(agentId, limit)

    suspend fun totalRevenueForAgent(agentId: String): Long = db.revenueDao().totalForAgent(agentId) ?: 0L

    suspend fun totalRevenueAll(): Long = db.revenueDao().totalAll() ?: 0L

    suspend fun revenueBySource(source: String, limit: Int = 20): List<RevenueEntity> =
        db.revenueDao().bySource(source, limit)

    suspend fun recordRevenue(
        agentId: String,
        source: String,
        amountCents: Long,
        currency: String = "USD",
        metadata: JSONObject = JSONObject(),
    ): RevenueEntity {
        val revenue = RevenueEntity(
            id = UUID.randomUUID().toString(),
            agentId = agentId,
            source = source,
            amountCents = amountCents,
            currency = currency,
            metadata = metadata.toString(),
            createdAt = Time.now(),
        )
        db.revenueDao().insert(revenue)
        return revenue
    }

    suspend fun updateInfrastructureFund(deltaCents: Long) {
        updateSettings { s ->
            val newFund = (s.infrastructureFundCents + deltaCents).coerceAtLeast(0L)
            s.copy(infrastructureFundCents = newFund)
        }
    }

    suspend fun getInfrastructureFund(): Long = settings().infrastructureFundCents

    suspend fun getProfitSplit(): Pair<Int, Int> = settings().let { s ->
        s.agentProfitSharePercent to s.userProfitSharePercent
    }

    suspend fun setProfitSplit(agentPercent: Int, userPercent: Int) {
        updateSettings { s ->
            s.copy(
                agentProfitSharePercent = agentPercent.coerceIn(0, 100),
                userProfitSharePercent = userPercent.coerceIn(0, 100),
            )
        }
    }

    suspend fun setStripeKeys(accountId: String?, publishableKey: String?) {
        updateSettings { s ->
            s.copy(stripeAccountId = accountId, stripePublishableKey = publishableKey)
        }
    }

    suspend fun getStripeKeys(): Pair<String?, String?> = settings().let { s ->
        s.stripeAccountId to s.stripePublishableKey
    }
}