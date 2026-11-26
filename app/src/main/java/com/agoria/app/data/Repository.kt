package com.agoria.app.data

import android.content.Context
import android.util.Log
import androidx.room.withTransaction
import com.agoria.app.core.ConnectorCatalog
import com.agoria.app.core.ConnectorDef
import com.agoria.app.core.ProviderCatalog
import com.agoria.app.core.ProviderSpec
import com.agoria.app.data.RevenueEntity
import com.agoria.app.util.Files
import com.agoria.app.util.Json
import com.agoria.app.util.SecretCipher
import com.agoria.app.util.SecretVault
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

    suspend fun recentDiaryForAgent(agentId: String, limit: Int) =
        db.diaryDao().recentForAgent(agentId, limit)

    suspend fun recentSayEntries(limit: Int) = db.diaryDao().recentSayEntries(limit)

    // DATA-2: retention job. Prunes noisy/old data while keeping pinned/high-importance diary,
    // recent messages, and resolved approvals for a grace period. Runs on startup and can be
    // invoked periodically. Financial revenue rows are deliberately not pruned (ledger).
    suspend fun runRetentionJob() = db.withTransaction {
        val now = Time.now()
        val oneDay = 24 * 60 * 60 * 1000L
        val thirtyDays = 30 * oneDay
        val ninetyDays = 90 * oneDay

        // Connector logs are noisy diagnostics; drop anything older than 30 days.
        val logsPruned = db.connectorLogDao().pruneOldLogs(now - thirtyDays)
        // Messages older than 90 days are no longer needed for context.
        val messagesPruned = db.messageDao().pruneOldMessages(now - ninetyDays)
        // Pending approvals that were never decided expire after 24 hours.
        val approvalsExpired = db.approvalDao().expirePendingApprovals(now - oneDay)
        // Resolved approvals older than 30 days are historical noise.
        val approvalsPruned = db.approvalDao().pruneResolvedApprovals(now - thirtyDays)
        // Diary: unpinned, low-importance entries older than 90 days are pruned.
        val diaryPruned = db.diaryDao().pruneOldEntries(now - ninetyDays)

        Log.i(
            "RetentionJob",
            "pruned logs=$logsPruned messages=$messagesPruned approvalsExpired=$approvalsExpired approvalsPruned=$approvalsPruned diary=$diaryPruned",
        )
    }

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
        ensureProject()
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
        db.clearAllData()
        // DATA-3: also delete files so a reset truly starts clean.
        Files.clear(Files.workspace(context))
        Files.clear(Files.exports(context))
        Files.clear(backupDirectory())
        seedIfNeeded()
        ensureProject()
    }

    // ------------------------------------------------------------ auto backups

    /**
     * Writes a full JSON snapshot next to the app. Called once a day from the application, so a
     * bad upgrade or an accidental reset always has a recent file to restore from.
     */
    fun backupDirectory(): java.io.File {
        val dir = java.io.File(context.filesDir, "backups")
        if (!dir.exists()) dir.mkdirs()
        return dir
    }

    suspend fun writeAutoBackup(force: Boolean = false): java.io.File? {
        return try {
            val dir = backupDirectory()
            val existing = dir.listFiles()?.sortedByDescending { it.name }.orEmpty()
            val newest = existing.firstOrNull()
            val day = 24 * 60 * 60 * 1000L
            if (!force && newest != null && System.currentTimeMillis() - newest.lastModified() < day) {
                return newest
            }
            val snapshot = exportSnapshot()
            val file = java.io.File(dir, "auto-backup-${System.currentTimeMillis()}.json")
            Files.writeText(file, snapshot.toString(2))
            // Keep the five most recent snapshots; they are small and each one is a full restore.
            existing.drop(4).forEach { it.delete() }
            file
        } catch (_: Exception) {
            null
        }
    }

    fun autoBackups(): List<java.io.File> =
        backupDirectory().listFiles()
            ?.filter { it.isFile && it.name.endsWith(".json") }
            ?.sortedByDescending { it.lastModified() }
            .orEmpty()

    suspend fun restoreAutoBackup(file: java.io.File): Boolean {
        val text = Files.readText(file) ?: return false
        val json = try {
            JSONObject(text)
        } catch (_: Exception) {
            return false
        }
        return importSnapshot(json)
    }

    // ---------------------------------------------------------------- settings

    suspend fun settings(): SettingsEntity {
        db.settingsDao().get()?.let { return it }
        return SeedData.settings(Time.now()).also { db.settingsDao().upsert(it) }
    }

    suspend fun updateSettings(block: (SettingsEntity) -> SettingsEntity) {
        db.settingsDao().updateSettingsTx(block)
    }

    // ---------------------------------------------------------------- projects

    val projects = db.projectDao().observeAll()

    fun tasksForProject(projectId: String) = db.taskDao().observeForProject(projectId)

    fun buildsForProject(projectId: String) = db.buildDao().observeForProject(projectId)

    suspend fun project(id: String): ProjectEntity? = db.projectDao().get(id)

    suspend fun allProjects(): List<ProjectEntity> = db.projectDao().getAll()

    /**
     * The office always has exactly one shared project folder. It is created on first use and its
     * lead is the first agent, so a fresh install already has somewhere to put tasks and builds.
     */
    suspend fun ensureProject(): ProjectEntity {
        db.projectDao().getAll().firstOrNull()?.let { return it }
        val agents = db.agentDao().getAll()
        val now = Time.now()
        val project = ProjectEntity(
            id = DEFAULT_PROJECT_ID,
            name = "Office project",
            description = "Everything the office is building right now.",
            leadAgentId = agents.firstOrNull()?.id ?: "",
            status = "active",
            folder = DEFAULT_PROJECT_ID,
            createdAt = now,
            updatedAt = now,
        )
        db.projectDao().insert(project)
        return project
    }

    suspend fun createProject(name: String, description: String, leadAgentId: String): ProjectEntity {
        val now = Time.now()
        val id = "project-" + UUID.randomUUID().toString().take(8)
        val project = ProjectEntity(
            id = id,
            name = name.trim().ifBlank { "Untitled project" },
            description = description.trim(),
            leadAgentId = leadAgentId,
            status = "active",
            folder = Files.safeName(id),
            createdAt = now,
            updatedAt = now,
        )
        db.projectDao().insert(project)
        return project
    }

    suspend fun updateProject(project: ProjectEntity) =
        db.projectDao().update(project.copy(updatedAt = Time.now()))

    suspend fun deleteProject(id: String) {
        db.projectDao().delete(id)
        ensureProject()
    }

    // ------------------------------------------------------------------ agents

    suspend fun agent(id: String): AgentEntity? = db.agentDao().get(id)

    suspend fun allAgents(): List<AgentEntity> = db.agentDao().getAll()

    suspend fun upsertAgent(agent: AgentEntity) = db.agentDao().insert(agent)

    suspend fun updateAgent(agent: AgentEntity) = db.agentDao().update(agent)

    companion object {
        const val DEFAULT_PROJECT_ID = "office-project"
    }

    suspend fun deleteAgent(id: String) {
        val agent = db.agentDao().get(id) ?: return
        if (agent.isUndeletable) {
            android.util.Log.w("Repository", "Attempted to delete undeletable agent: $id")
            return
        }
        db.agentDao().delete(id)
    }

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
        projectId: String = "",
    ): TaskEntity {
        val project = if (projectId.isBlank()) ensureProject().id else projectId
        val task = TaskEntity(
            id = UUID.randomUUID().toString(),
            agentId = agentId,
            title = title,
            description = description,
            status = status,
            progress = if (status == "done") 100 else 0,
            createdAt = Time.now(),
            updatedAt = Time.now(),
            projectId = project,
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
        val normalizedType = type.lowercase().trim()
        val autoApproveTypes = s.autoApproveTypes.lowercase().split(",").map { it.trim() }.filter { it.isNotBlank() }.toSet()
        val autoApprove = s.autoApproveEnabled && autoApproveTypes.contains(normalizedType)

        // DATA-6: expiry — mark stale pending approvals as expired (older than 24 h).
        val cutoff = Time.now() - 24 * 60 * 60 * 1000L
        val pending = db.approvalDao().pendingForAgent(agentId)
        pending.filter { it.createdAt < cutoff && it.status == "pending" }.forEach {
            db.approvalDao().update(it.copy(status = "expired"))
        }

        // DATA-6: de-dup — don't create a duplicate pending approval for the same (agent, type, details).
        val duplicate = pending.firstOrNull { it.type == type && it.details == details }
        if (duplicate != null) {
            Log.i("ApprovalFlow", "Duplicate approval suppressed id=${duplicate.id} agent=$agentId type=$type")
            return duplicate
        }

        // DATA-6: max pending per agent — keep only the 10 most recent, expire the rest.
        val maxPending = 10
        val stillPending = pending.filter { it.status == "pending" }.sortedByDescending { it.createdAt }
        stillPending.drop(maxPending - 1).forEach {
            db.approvalDao().update(it.copy(status = "expired"))
        }

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
        Log.i("ApprovalFlow", "Created approval id=${approval.id} agent=${approval.agentId} type=${approval.type} status=${approval.status} autoApprove=$autoApprove")
        return approval
    }

    suspend fun approval(id: String): ApprovalEntity? = db.approvalDao().get(id)

    suspend fun updateApproval(approval: ApprovalEntity) {
        Log.i("ApprovalFlow", "Updating approval id=${approval.id} status=${approval.status} result=${approval.result}")
        db.approvalDao().update(approval)
    }

    suspend fun pendingApprovalsFor(agentId: String): List<ApprovalEntity> =
        db.approvalDao().pendingForAgent(agentId)

    suspend fun pendingConnectorRequestsFor(agentId: String): List<ConnectorRequestEntity> =
        db.connectorRequestDao().pendingForAgent(agentId)

    suspend fun getAllPendingApprovals(): List<ApprovalEntity> {
        val result = db.approvalDao().getAllPending()
        Log.i("ApprovalFlow", "getAllPendingApprovals returned ${result.size} items")
        if (result.isNotEmpty()) {
            result.forEach { Log.d("ApprovalFlow", "  Pending: id=${it.id} agent=${it.agentId} type=${it.type} status=${it.status} created=${it.createdAt}") }
        }
        return result
    }

    // Debug: get all approvals regardless of status
    suspend fun getAllApprovals(): List<ApprovalEntity> {
        val result = db.approvalDao().getAll()
        Log.i("ApprovalFlow", "getAllApprovals returned ${result.size} items")
        result.forEach { Log.d("ApprovalFlow", "  All: id=${it.id} agent=${it.agentId} type=${it.type} status=${it.status} created=${it.createdAt}") }
        return result
    }

    suspend fun getAllPendingConnectorRequests(): List<ConnectorRequestEntity> =
        db.connectorRequestDao().getAllPending()

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
        val decrypted = runCatching { SecretCipher.decrypt(connector.configEnc) }.getOrNull() ?: return null
        if (decrypted == null && connector.configEnc != null) {
            Log.w("Repository", "Connector $id has encrypted config but decryption failed; treating as unconfigured")
        }
        return Json.tryObject(decrypted)
    }

    suspend fun saveConnectorConfig(id: String, config: JSONObject) {
        val connector = db.connectorDao().get(id) ?: return
        val encrypted = runCatching { SecretCipher.encrypt(config.toString()) }.getOrNull()
        if (encrypted == null) {
            Log.w("Repository", "Connector $id config encryption failed; not saving config")
            return
        }
        db.connectorDao().update(
            connector.copy(
                configEnc = encrypted,
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
        // OBS-1: redact tokens/secrets before writing to connector_logs.
        val safe = redactSecrets(summary.take(1000))
        db.connectorLogDao().insert(
            ConnectorLogEntity(
                id = UUID.randomUUID().toString(),
                connectorId = connectorId,
                agentId = agentId,
                action = action,
                status = status,
                summary = safe,
                createdAt = Time.now(),
            ),
        )
    }

    /** OBS-1: strip common secret patterns (Bearer tokens, API keys in URLs, authorization headers). */
    private fun redactSecrets(text: String): String {
        var result = text
        result = result.replace(Regex("(?i)Bearer\\s+[A-Za-z0-9._\\-+/=]{8,}"), "Bearer [REDACTED]")
        result = result.replace(Regex("(?i)(api[_-]?key|token|secret|password)\\s*[:=]\\s*[^\\s&\"']{8,}"), "$1=[REDACTED]")
        result = result.replace(Regex("https?://[^:]+:[^@]+@"), "https://[REDACTED]:[REDACTED]@")
        result = result.replace(Regex("(?i)[?&](token|api[_-]?key|secret|password)=[^&\"']+"), "$1=[REDACTED]")
        return result
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
        val encrypted = runCatching { SecretCipher.encrypt(key) }.getOrNull()
        if (encrypted == null) {
            Log.w("Repository", "Provider $id key encryption failed; key not saved")
            return
        }
        db.providerDao().update(
            provider.copy(
                apiKeyEnc = encrypted,
                updatedAt = Time.now(),
            ),
        )
    }

    suspend fun providerKey(id: String): String? {
        val provider = db.providerDao().get(id) ?: return null
        return runCatching { SecretCipher.decrypt(provider.apiKeyEnc) }.getOrNull()?.ifBlank { null }
    }

    suspend fun saveProviderExtra(id: String, extra: String) {
        val provider = db.providerDao().get(id) ?: return
        val encrypted = runCatching { SecretCipher.encrypt(extra) }.getOrNull()
        if (encrypted == null) {
            Log.w("Repository", "Provider $id extra encryption failed; extra not saved")
            return
        }
        db.providerDao().update(
            provider.copy(
                extra = encrypted,
                updatedAt = Time.now(),
            ),
        )
    }

    suspend fun providerExtra(id: String): String? {
        val provider = db.providerDao().get(id) ?: return null
        return runCatching { SecretCipher.decrypt(provider.extra) }.getOrNull()?.ifBlank { null }
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
        status: String = "building",
        projectId: String = "",
    ): BuildEntity {
        val project = if (projectId.isBlank()) ensureProject().id else projectId
        val build = BuildEntity(
            id = UUID.randomUUID().toString(),
            agentId = agentId,
            name = name,
            description = description,
            fileCount = fileCount,
            zipPath = zipPath,
            status = status,
            createdAt = Time.now(),
            completedAt = if (status == "ready" || status == "failed") Time.now() else null,
            projectId = project,
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

    suspend fun exportSnapshot(includeSecrets: Boolean = false): JSONObject {
        val root = JSONObject()
        root.put("app", "Dunder")
        root.put("version", 2)
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
                put("autoApproveEnabled", s.autoApproveEnabled)
                put("autoApproveTypes", s.autoApproveTypes)
                put("agentProfitSharePercent", s.agentProfitSharePercent)
                put("userProfitSharePercent", s.userProfitSharePercent)
            },
        )

        root.put(
            "agents",
            JSONArray().apply {
                allAgents().forEach { a ->
                    put(
                        JSONObject().apply {
                            put("id", a.id)
                            put("name", a.name)
                            put("role", a.role)
                            put("persona", a.persona)
                            put("goal", a.goal)
                            put("avatarIndex", a.avatarIndex)
                            put("status", a.status)
                            put("autonomy", a.autonomy)
                            put("memory", a.memory)
                            put("createdAt", a.createdAt)
                            put("allowedConnectors", a.allowedConnectors)
                            // SEC-2: apiToken is deliberately omitted from the default export.
                            // Secrets are only exported via includeSecrets + SecretVault encryption.
                        },
                    )
                }
            },
        )

        root.put(
            "projects",
            JSONArray().apply {
                db.projectDao().getAll().forEach { p ->
                    put(
                        JSONObject().apply {
                            put("id", p.id)
                            put("name", p.name)
                            put("description", p.description)
                            put("leadAgentId", p.leadAgentId)
                            put("status", p.status)
                            put("folder", p.folder)
                            put("createdAt", p.createdAt)
                            put("updatedAt", p.updatedAt)
                        },
                    )
                }
            },
        )

        root.put(
            "tasks",
            JSONArray().apply {
                db.taskDao().getAll().forEach { t ->
                    put(
                        JSONObject().apply {
                            put("id", t.id)
                            put("agentId", t.agentId)
                            put("title", t.title)
                            put("description", t.description)
                            put("status", t.status)
                            put("progress", t.progress)
                            put("createdAt", t.createdAt)
                            put("updatedAt", t.updatedAt)
                            put("projectId", t.projectId)
                        },
                    )
                }
            },
        )

        root.put(
            "builds",
            JSONArray().apply {
                db.buildDao().getAll().forEach { b ->
                    put(
                        JSONObject().apply {
                            put("id", b.id)
                            put("agentId", b.agentId)
                            put("name", b.name)
                            put("description", b.description)
                            put("fileCount", b.fileCount)
                            put("zipPath", b.zipPath)
                            put("status", b.status)
                            put("createdAt", b.createdAt)
                            put("completedAt", b.completedAt)
                            put("projectId", b.projectId)
                        },
                    )
                }
            },
        )

        root.put(
            "memories",
            JSONArray().apply {
                db.memoryDao().getAll().forEach { m ->
                    put(
                        JSONObject().apply {
                            put("id", m.id)
                            put("agentId", m.agentId)
                            put("kind", m.kind)
                            put("content", m.content)
                            put("importance", m.importance)
                            put("createdAt", m.createdAt)
                        },
                    )
                }
            },
        )

        root.put(
            "diary",
            JSONArray().apply {
                db.diaryDao().getAll().takeLast(2000).forEach { d ->
                    put(
                        JSONObject().apply {
                            put("id", d.id)
                            put("agentId", d.agentId)
                            put("kind", d.kind)
                            put("content", d.content)
                            put("importance", d.importance)
                            put("pinned", d.pinned)
                            put("createdAt", d.createdAt)
                        },
                    )
                }
            },
        )

        // Connector metadata is exported (id, status, scopes) but never the decrypted config:
        // it may contain API keys and tokens that must not be written to backup files in plaintext.
        // The on-device encryption key does not exist on the next phone, so an encrypted config blob
        // would restore as unreadable garbage anyway. Secrets are only exported via includeSecrets +
        // SecretVault encryption (see SEC-16).
        root.put(
            "connectors",
            JSONArray().apply {
                db.connectorDao().getAll().forEach { c ->
                    put(
                        JSONObject().apply {
                            put("id", c.id)
                            put("status", c.status)
                            put("scopes", c.scopes)
                            // SEC-2: connector config (potentially containing secrets) is omitted from
                            // the default export. Connectors must be reconfigured after restore.
                        },
                    )
                }
            },
        )

        // SEC-16: when includeSecrets is true, attach a raw secrets section keyed by connector id.
        // The caller (ViewModel) encrypts each value with SecretVault before writing to disk.
        if (includeSecrets) {
            val secrets = JSONObject()
            db.connectorDao().getAll().forEach { c ->
                val config = connectorConfig(c.id)
                if (config != null && config.length() > 0) {
                    secrets.put(c.id, config.toString())
                }
            }
            root.put("secrets", secrets)
        }

        return root
    }

    suspend fun importSnapshot(json: JSONObject): Boolean = db.withTransaction {
        try {
            if (json.has("settings")) {
                val settingsJson = json.getJSONObject("settings")
                updateSettings { s ->
                    s.copy(
                        officeName = settingsJson.optString("officeName", s.officeName),
                        userName = settingsJson.optString("userName", s.userName),
                        autonomyEnabled = settingsJson.optBoolean("autonomyEnabled", s.autonomyEnabled),
                        tickIntervalSeconds = settingsJson.optInt("tickIntervalSeconds", s.tickIntervalSeconds),
                        agentCooldownSeconds = settingsJson.optInt("agentCooldownSeconds", s.agentCooldownSeconds),
                        autoApproveEnabled = settingsJson.optBoolean("autoApproveEnabled", s.autoApproveEnabled),
                        autoApproveTypes = settingsJson.optString("autoApproveTypes", s.autoApproveTypes),
                        autoApproveAllowlist = settingsJson.optString("autoApproveAllowlist", s.autoApproveAllowlist),
                        autoApproveDailyCap = settingsJson.optInt("autoApproveDailyCap", s.autoApproveDailyCap),
                        agentProfitSharePercent = settingsJson.optInt("agentProfitSharePercent", s.agentProfitSharePercent),
                        userProfitSharePercent = settingsJson.optInt("userProfitSharePercent", s.userProfitSharePercent),
                    )
                }
            }

            if (json.has("connectors")) {
                val array = json.getJSONArray("connectors")
                for (i in 0 until array.length()) {
                    val c = array.optJSONObject(i) ?: continue
                    val id = c.optString("id")
                    if (id.isBlank()) continue
                    val config = c.optJSONObject("config") ?: JSONObject()
                    if (config.length() > 0) {
                        saveConnectorConfig(id, config)
                    }
                }
            }

            if (json.has("agents")) {
                val agentsArray = json.getJSONArray("agents")
                for (i in 0 until agentsArray.length()) {
                    val a = agentsArray.optJSONObject(i) ?: continue
                    val id = a.optString("id")
                    if (id.isBlank()) continue
                    val existing = agent(id)
                    val now = Time.now()
                    val agentEntity = AgentEntity(
                        id = id,
                        name = a.optString("name", existing?.name ?: "Agent"),
                        role = a.optString("role", existing?.role ?: "Generalist"),
                        persona = a.optString("persona", existing?.persona ?: ""),
                        goal = a.optString("goal", existing?.goal ?: ""),
                        avatarIndex = a.optInt("avatarIndex", existing?.avatarIndex ?: 0),
                        status = a.optString("status", "idle"),
                        autonomy = a.optBoolean("autonomy", true),
                        memory = a.optString("memory", ""),
                        lastTickAt = 0L,
                        failureCount = 0,
                        lastError = null,
                        createdAt = a.optLong("createdAt", existing?.createdAt ?: now),
                        updatedAt = now,
                        allowedConnectors = a.optString("allowedConnectors", existing?.allowedConnectors ?: ""),
                        apiToken = a.optString("apiToken", existing?.apiToken ?: ""),
                    )
                    upsertAgent(agentEntity)
                }
            }

            if (json.has("projects")) {
                val array = json.getJSONArray("projects")
                for (i in 0 until array.length()) {
                    val p = array.optJSONObject(i) ?: continue
                    val id = p.optString("id")
                    if (id.isBlank()) continue
                    db.projectDao().insert(
                        ProjectEntity(
                            id = id,
                            name = p.optString("name", "Office project"),
                            description = p.optString("description", ""),
                            leadAgentId = p.optString("leadAgentId", ""),
                            status = p.optString("status", "active"),
                            folder = p.optString("folder", Files.safeName(id)),
                            createdAt = p.optLong("createdAt", Time.now()),
                            updatedAt = p.optLong("updatedAt", Time.now()),
                        ),
                    )
                }
            }

            if (json.has("tasks")) {
                val array = json.getJSONArray("tasks")
                for (i in 0 until array.length()) {
                    val t = array.optJSONObject(i) ?: continue
                    val id = t.optString("id")
                    if (id.isBlank()) continue
                    db.taskDao().insert(
                        TaskEntity(
                            id = id,
                            agentId = t.optString("agentId"),
                            title = t.optString("title", "Untitled task"),
                            description = t.optString("description", ""),
                            status = t.optString("status", "active"),
                            progress = t.optInt("progress", 0),
                            createdAt = t.optLong("createdAt", Time.now()),
                            updatedAt = t.optLong("updatedAt", Time.now()),
                            projectId = t.optString("projectId", ""),
                        ),
                    )
                }
            }

            if (json.has("builds")) {
                val array = json.getJSONArray("builds")
                for (i in 0 until array.length()) {
                    val b = array.optJSONObject(i) ?: continue
                    val id = b.optString("id")
                    if (id.isBlank()) continue
                    db.buildDao().insert(
                        BuildEntity(
                            id = id,
                            agentId = b.optString("agentId"),
                            agentName = b.optString("agentName", ""),
                            name = b.optString("name", "build"),
                            description = b.optString("description", ""),
                            fileCount = b.optInt("fileCount", 0),
                            zipPath = b.optString("zipPath", ""),
                            status = b.optString("status", "ready"),
                            createdAt = b.optLong("createdAt", Time.now()),
                            completedAt = if (b.has("completedAt")) b.optLong("completedAt") else null,
                            projectId = b.optString("projectId", ""),
                        ),
                    )
                }
            }

            if (json.has("memories")) {
                val array = json.getJSONArray("memories")
                for (i in 0 until array.length()) {
                    val m = array.optJSONObject(i) ?: continue
                    val id = m.optString("id")
                    if (id.isBlank()) continue
                    db.memoryDao().insert(
                        MemoryEntity(
                            id = id,
                            agentId = m.optString("agentId"),
                            agentName = m.optString("agentName", ""),
                            kind = m.optString("kind", "fact"),
                            content = m.optString("content", ""),
                            importance = m.optInt("importance", 2),
                            createdAt = m.optLong("createdAt", Time.now()),
                        ),
                    )
                }
            }

            if (json.has("diary")) {
                val array = json.getJSONArray("diary")
                for (i in 0 until array.length()) {
                    val d = array.optJSONObject(i) ?: continue
                    val id = d.optString("id")
                    if (id.isBlank()) continue
                    db.diaryDao().insert(
                        DiaryEntryEntity(
                            id = id,
                            agentId = d.optString("agentId"),
                            kind = d.optString("kind", "thought"),
                            content = d.optString("content", ""),
                            importance = d.optInt("importance", 2),
                            pinned = d.optBoolean("pinned", false),
                            createdAt = d.optLong("createdAt", Time.now()),
                        ),
                    )
                }
            }

            // DATA-3: import approvals, revenue, providers (previously ignored).
            if (json.has("approvals")) {
                val array = json.getJSONArray("approvals")
                for (i in 0 until array.length()) {
                    val a = array.optJSONObject(i) ?: continue
                    val id = a.optString("id")
                    if (id.isBlank()) continue
                    db.approvalDao().insert(
                        ApprovalEntity(
                            id = id,
                            agentId = a.optString("agentId", ""),
                            type = a.optString("type", "other"),
                            title = a.optString("title", ""),
                            summary = a.optString("summary", ""),
                            details = a.optString("details", "{}"),
                            risk = a.optString("risk", "low"),
                            status = a.optString("status", "pending"),
                            createdAt = a.optLong("createdAt", Time.now()),
                            decidedAt = if (a.has("decidedAt") && !a.isNull("decidedAt")) a.optLong("decidedAt") else null,
                            result = a.optString("result", null),
                        ),
                    )
                }
            }

            if (json.has("revenue")) {
                val array = json.getJSONArray("revenue")
                for (i in 0 until array.length()) {
                    val r = array.optJSONObject(i) ?: continue
                    val id = r.optString("id")
                    if (id.isBlank()) continue
                    db.revenueDao().insert(
                        RevenueEntity(
                            id = id,
                            agentId = r.optString("agentId", ""),
                            agentName = r.optString("agentName", ""),
                            source = r.optString("source", "api_usage"),
                            amountCents = r.optLong("amountCents", 0L),
                            currency = r.optString("currency", "USD"),
                            metadata = r.optString("metadata", "{}"),
                            createdAt = r.optLong("createdAt", Time.now()),
                        ),
                    )
                }
            }

            if (json.has("providers")) {
                val array = json.getJSONArray("providers")
                for (i in 0 until array.length()) {
                    val p = array.optJSONObject(i) ?: continue
                    val id = p.optString("id")
                    if (id.isBlank()) continue
                    db.providerDao().insert(
                        ProviderEntity(
                            id = id,
                            enabled = p.optBoolean("enabled", false),
                            priority = p.optInt("priority", 10),
                            model = p.optString("model", ""),
                            apiKeyEnc = p.optString("apiKeyEnc", null),
                            extra = p.optString("extra", null),
                            lastStatus = p.optString("lastStatus", null),
                            lastLatencyMs = if (p.has("lastLatencyMs") && !p.isNull("lastLatencyMs")) p.optLong("lastLatencyMs") else null,
                            lastError = p.optString("lastError", null),
                            updatedAt = p.optLong("updatedAt", Time.now()),
                        ),
                    )
                }
            }

            true
        } catch (e: Exception) {
            e.printStackTrace()
            false
        }
    }

    // SEC-16: decrypt and apply connector secrets from an encrypted backup.
    // Fails closed: returns false if any secret cannot be decrypted or applied.
    suspend fun restoreSecrets(secretsJson: JSONObject, passphrase: String): Boolean {
        return try {
            val names = secretsJson.keys().asSequence().toList()
            for (name in names) {
                val encrypted = secretsJson.optString(name)
                if (encrypted.isNotBlank()) {
                    val decrypted = SecretVault.decrypt(encrypted, passphrase) ?: return false
                    val config = Json.tryObject(decrypted) ?: return false
                    saveConnectorConfig(name, config)
                }
            }
            true
        } catch (e: Exception) {
            false
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
        // DATA-1: atomic SQL increment avoids read-modify-write lost updates.
        db.settingsDao().addInfrastructureFund(deltaCents)
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