package com.agoria.app.data

import android.content.Context
import android.util.Log
import androidx.room.withTransaction
import com.agoria.app.core.ConnectorCatalog
import com.agoria.app.core.ConnectorDef
import com.agoria.app.core.ProviderCatalog
import com.agoria.app.core.ProviderSpec
import com.agoria.app.core.SkillCatalog
import com.agoria.app.core.SkillDef
import com.agoria.app.data.RevenueEntity
import com.agoria.app.util.Files
import com.agoria.app.util.Json
import com.agoria.app.util.SecretCipher
import com.agoria.app.util.SnapshotSecrets
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
    val skills = db.skillDao().observeAll()
    val enabledSkills = db.skillDao().observeEnabled()
    val settingsFlow = db.settingsDao().observe()
    val memories = db.memoryDao().observeAll()
    val connectorLogs = db.connectorLogDao().observeRecent(50)

    fun diaryFor(agentId: String) = db.diaryDao().observeForAgent(agentId)
    suspend fun recentDiaryForAgent(agentId: String, limit: Int) = db.diaryDao().recentForAgent(agentId, limit)
    suspend fun recentSayEntries(limit: Int) = db.diaryDao().recentSayEntries(limit)
","oldString">
    fun diaryFor(agentId: String) = db.diaryDao().observeForAgent(agentId)
","path":"/workspace/app/app/src/main/java/com/agoria/app/data/Repository.kt    fun messagesFor(agentId: String) = db.messageDao().observeForAgent(agentId)
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
        if (settings.seedVersion >= 2) return@withTransaction

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

        db.settingsDao().upsert(settings.copy(seedVersion = 2))
        }
        syncConnectorCatalog()
        syncSkillCatalog()
        seedSkillsIfNeeded()
        ensureSeedAgents()
        ensureProject()
    }

    /**
     * Ensures all seed agents exist. Runs on every launch after syncConnectorCatalog().
     * - Inserts missing seed agents with their seed diary entries and memories.
     * - Never overwrites user edits to existing agents.
     * - Forces seed-platform-engineer to be undeletable and have github connector.
     */
    suspend fun ensureSeedAgents() {
        db.withTransaction {
            val now = Time.now()
            val seedAgents = SeedData.agents(now)
            val seedDiary = SeedData.diary(now).associateBy { it.agentId }
            val seedMemories = SeedData.memories(now).groupBy { it.agentId }

            for (seedAgent in seedAgents) {
                val existing = db.agentDao().get(seedAgent.id)
                if (existing == null) {
                    // New agent: insert with all seed data
                    db.agentDao().insert(seedAgent)
                    seedDiary[seedAgent.id]?.let { db.diaryDao().insert(it) }
                    seedMemories[seedAgent.id]?.forEach { db.memoryDao().insert(it) }
                } else {
                    // Existing agent: enforce undeletable and github connector for Platform Engineer
                    if (seedAgent.id == "seed-platform-engineer") {
                        var needsUpdate = false
                        val updated = existing.copy(
                            isUndeletable = true,
                            allowedConnectors = if (existing.allowedConnectors.contains("github")) existing.allowedConnectors else (existing.allowedConnectors + ",github").trim { it == ',' },
                        ).also { if (it != existing) needsUpdate = true }
                        if (needsUpdate) db.agentDao().update(updated)
                    }
                }
            }
        }
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

    /**
     * Keeps the installed skills aligned with [SkillCatalog]: rows for skills that no
     * longer exist are removed, and missing ones are added or refreshed.
     */
    suspend fun syncSkillCatalog() {
        val existing = db.skillDao().getAll().associateBy { it.id }
        existing.keys
            .filter { SkillCatalog.get(it) == null }
            .forEach { db.skillDao().delete(it) }

        val now = Time.now()
        SkillCatalog.all.forEach { def ->
            val row = existing[def.id]
            val manifestJson = JSONObject().put("id", def.id).put("name", def.name).put("category", def.category)
                .put("description", def.description).put("version", def.version.toString())
                .put("actions", JSONArray(def.actions.map { it.id })).toString()
            if (row == null) {
                db.skillDao().insert(
                    SkillEntity(
                        id = def.id,
                        name = def.name,
                        description = def.description,
                        category = def.category,
                        version = def.version.toString(),
                        author = "Dunder",
                        manifestJson = manifestJson,
                        installedAt = now,
                        updatedAt = now,
                        enabled = true,
                    ),
                )
                return@forEach
            }
            if (row.name != def.name || row.category != def.category || row.description != def.description || row.version != def.version.toString()) {
                db.skillDao().update(
                    row.copy(
                        name = def.name,
                        category = def.category,
                        description = def.description,
                        version = def.version.toString(),
                        manifestJson = manifestJson,
                        updatedAt = now,
                    ),
                )
            }
        }
    }

    suspend fun reset() {
        db.clearAllTables()
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
            // Auto-backups sit in app storage for months, so they never carry credentials
            // (SEC-2); the stored configs are merged back in on import.
            val snapshot = exportSnapshot(includeSecrets = false)
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
        val current = settings()
        db.settingsDao().upsert(block(current))
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

    // SEC-17: encrypt/decrypt apiToken at rest
    suspend fun agentApiToken(id: String): String? {
        val agent = db.agentDao().get(id) ?: return null
        // Try decrypting (new format); fall back to plaintext (legacy/migration)
        return SecretCipher.decrypt(agent.apiToken) ?: agent.apiToken.ifBlank { null }
    }

    suspend fun updateAgentApiToken(id: String, plainToken: String?) {
        val agent = db.agentDao().get(id) ?: return
        val encrypted = if (plainToken.isNullOrBlank()) "" else (SecretCipher.encrypt(plainToken) ?: plainToken)
        db.agentDao().update(agent.copy(apiToken = encrypted, updatedAt = Time.now()))
    }

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
        // DATA-6: de-duplicate pending approvals by (agentId, type, details hash)
        val existing = db.approvalDao().getPendingByAgentAndType(agentId, normalizedType)
            .firstOrNull { it.details.hashCode() == details.hashCode() }
        if (existing != null) {
            return existing
        }
        val autoApproveTypes = s.autoApproveTypes.lowercase().split(",").map { it.trim() }.filter { it.isNotBlank() }.toSet()
        // SEC-5: never auto-approve high-risk actions, even if the type is in autoApproveTypes.
        // Also require an explicit (connectorId, action) allowlist for connector_action types.
        val isHighRisk = risk.lowercase().trim() == "high"
        val isConnectorAction = normalizedType == "connector_action"
        var autoApprove = s.autoApproveEnabled && autoApproveTypes.contains(normalizedType) && !isHighRisk

        // SEC-5: for connector_action, require explicit (connectorId, action) allowlist entry.
        // The allowlist is stored in settings.autoApproveTypes as "connector_action:connectorId:action" entries.
        if (autoApprove && isConnectorAction) {
            val detailsObj = try { org.json.JSONObject(details) } catch (_: Exception) { null }
            val connectorId = detailsObj?.optString("connector_id", detailsObj.optString("connector", "")) ?: ""
            val actionName = detailsObj?.optString("action_name", detailsObj.optString("action", "")) ?: ""
            val allowlistEntry = "connector_action:${connectorId.lowercase()}:${actionName.lowercase()}"
            autoApprove = autoApproveTypes.contains(allowlistEntry)
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
        val decrypted = SecretCipher.decrypt(connector.configEnc) ?: return null
        return Json.tryObject(decrypted)
    }

    suspend fun saveConnectorConfig(id: String, config: JSONObject) {
        val connector = db.connectorDao().get(id) ?: return
        // Do not mark a connector usable while a required field (usually its secret) is missing:
        // that is exactly the state after importing a secret-free snapshot (SEC-2).
        val missingRequired = ConnectorCatalog.get(id)?.fields
            ?.any { it.required && config.optString(it.key).isBlank() } == true
        db.connectorDao().update(
            connector.copy(
                configEnc = SecretCipher.encrypt(config.toString()),
                status = if (missingRequired) "needs_setup" else "configured",
                lastError = null,
            ),
        )
    }

    // SEC-16: decrypt and apply an encrypted secrets section from an import snapshot.
    // The passphrase comes from the UI layer; Repository never sees it in plaintext.
    suspend fun restoreSecrets(secretsJson: JSONObject, passphrase: String): Boolean {
        var applied = 0
        val names = secretsJson.keys().asSequence().toList()
        for (name in names) {
            val enc = secretsJson.optString(name)
            if (enc.isBlank()) continue
            val decrypted = SecretVault.decrypt(enc, passphrase) ?: return false
            val config = Json.tryObject(decrypted) ?: return false
            saveConnectorConfig(name, config)
            applied++
        }
        return applied > 0
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
    ): Unit {
        // OBS-1: redact potential secrets/tokens from the summary before persisting
        val redacted = summary
            .replace(Regex("ghp_[A-Za-z0-9_]{10,}"), "ghp_***")
            .replace(Regex("github_pat_[A-Za-z0-9_]{10,}"), "github_pat_***")
            .replace(Regex("sk-[A-Za-z0-9]{10,}"), "sk-***")
            .replace(Regex("Bearer [A-Za-z0-9._-]{10,}"), "Bearer ***")
            .replace(Regex("bot[0-9]+:[A-Za-z0-9_-]{20,}"), "bot***:***")
            .take(1000)
        db.connectorLogDao().insert(
            ConnectorLogEntity(
                id = UUID.randomUUID().toString(),
                connectorId = connectorId,
                agentId = agentId,
                action = action,
                status = status,
                summary = redacted,
                createdAt = Time.now(),
            ),
        )
    }

    // ---------------------------------------------------------------- skills

    suspend fun skill(id: String): SkillEntity? = db.skillDao().get(id)

    suspend fun allSkills(): List<SkillEntity> = db.skillDao().getAll()

    suspend fun enabledSkillsList(): List<SkillEntity> = db.skillDao().getAll().filter { it.enabled }

    suspend fun agentSkills(agentId: String): List<SkillEntity> {
        return db.agentSkillDao().getEnabledForAgent(agentId).map { agentSkill ->
            db.skillDao().get(agentSkill.skillId)
        }.filterNotNull()
    }

    suspend fun agentSkillConfigs(agentId: String): List<AgentSkillEntity> = db.agentSkillDao().getEnabledForAgent(agentId)

    suspend fun installSkill(agentId: String, skillId: String, config: JSONObject = JSONObject()) {
        val skill = db.skillDao().get(skillId) ?: return
        if (!skill.enabled) return
        val now = Time.now()
        db.agentSkillDao().insert(
            AgentSkillEntity(
                agentId = agentId,
                skillId = skillId,
                enabled = true,
                configJson = config.toString(),
                installedAt = now,
            ),
        )
    }

    suspend fun uninstallSkill(agentId: String, skillId: String) {
        db.agentSkillDao().delete(agentId, skillId)
    }

    suspend fun updateSkillConfig(agentId: String, skillId: String, config: JSONObject) {
        val existing = db.agentSkillDao().get(agentId, skillId) ?: return
        db.agentSkillDao().insert(
            existing.copy(
                configJson = config.toString(),
            ),
        )
    }

    suspend fun getSkillConfig(agentId: String, skillId: String): JSONObject? {
        val agentSkill = db.agentSkillDao().get(agentId, skillId) ?: return null
        return org.json.JSONObject(agentSkill.configJson)
    }

    suspend fun seedSkillsIfNeeded() {
        val now = Time.now()
        if (db.skillDao().getAll().isEmpty()) {
            SkillCatalog.all.forEach { def ->
                val manifestJson = JSONObject().put("id", def.id).put("name", def.name).put("category", def.category)
                    .put("description", def.description).put("version", def.version.toString())
                    .put("actions", JSONArray(def.actions.map { it.id })).toString()
                db.skillDao().insert(
                    SkillEntity(
                        id = def.id,
                        name = def.name,
                        description = def.description,
                        category = def.category,
                        version = def.version.toString(),
                        author = "Dunder",
                        manifestJson = manifestJson,
                        installedAt = now,
                        updatedAt = now,
                        enabled = true,
                    ),
                )
            }
        }
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
            status = "building",
            createdAt = Time.now(),
            completedAt = null,
            projectId = project,
        )
        db.buildDao().insert(build)
        return build
    }

    suspend fun completeBuild(id: String, status: String = "ready"): BuildEntity? {
        val build = db.buildDao().get(id) ?: return null
        val updated = build.copy(status = status, completedAt = Time.now())
        db.buildDao().update(updated)
        return updated
    }

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

    /**
     * Builds the exportable snapshot. [includeSecrets] is opt-in and is never used for anything
     * written automatically: the default carries no API key, token or connector credential
     * (SEC-2), so the file can be shared without leaking the office's accounts.
     */
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
                            put("apiToken", a.apiToken)
                            put("isUndeletable", a.isUndeletable)
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

        // Connector settings are exported decrypted: the on-device key that encrypted them does not
        // exist on the next phone, so an encrypted blob would restore as unreadable garbage. The
        // credentials are stripped by the redaction pass at the end of this function (SEC-2).
        root.put(
            "connectors",
            JSONArray().apply {
                db.connectorDao().getAll().forEach { c ->
                    val config = connectorConfig(c.id) ?: JSONObject()
                    put(
                        JSONObject().apply {
                            put("id", c.id)
                            put("status", c.status)
                            put("scopes", c.scopes)
                            put("config", config)
                        },
                    )
                }
            },
        )

        root.put(
            "skills",
            JSONArray().apply {
                db.skillDao().getAll().forEach { s ->
                    put(
                        JSONObject().apply {
                            put("id", s.id)
                            put("name", s.name)
                            put("category", s.category)
                            put("description", s.description)
                            put("manifestJson", s.manifestJson)
                            put("enabled", s.enabled)
                            put("version", s.version)
                            put("author", s.author)
                            put("installedAt", s.installedAt)
                            put("updatedAt", s.updatedAt)
                        },
                    )
                }
            },
        )

        val agentSkillsList = mutableListOf<JSONObject>()
        db.agentDao().getAll().forEach { agent ->
            db.agentSkillDao().getEnabledForAgent(agent.id).forEach { agentSkill ->
                val obj = JSONObject().apply {
                    put("agentId", agentSkill.agentId)
                    put("skillId", agentSkill.skillId)
                    put("config", agentSkill.configJson)
                    put("enabled", agentSkill.enabled)
                    put("installedAt", agentSkill.installedAt)
                }
                agentSkillsList.add(obj)
            }
        }
        root.put(
            "agent_skills",
            JSONArray().apply {
                agentSkillsList.forEach { put(it) }
            },
        )

        // SEC-16: when includeSecrets is true, attach a `secrets` section with the raw
        // connector configEnc values keyed by connector id. The UI layer encrypts this section
        // with SecretVault using a user-supplied passphrase before writing the file. When
        // includeSecrets is false (the default for auto-backup and manual export), no secrets
        // section is added and the redaction pass below strips any credentials.
        if (includeSecrets) {
            val secrets = JSONObject()
            db.connectorDao().getAll().forEach { c ->
                val enc = c.configEnc
                if (enc.isNotBlank()) {
                    secrets.put(c.id, enc)
                }
            }
            root.put("secrets", secrets)
        }

        // One pass removes every credential (SEC-2): connector fields the catalog marks secret,
        // anything named like a token/secret, and secrets inside skill config JSON. Keeping it in
        // one place means a field added to the snapshot later cannot leak by being forgotten.
        if (!includeSecrets) SnapshotSecrets.redactSnapshot(root)
        return root
    }

    suspend fun importSnapshot(json: JSONObject): Boolean {
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
                        agentProfitSharePercent = settingsJson.optInt("agentProfitSharePercent", s.agentProfitSharePercent),
                        userProfitSharePercent = settingsJson.optInt("userProfitSharePercent", s.userProfitSharePercent),
                    )
                }
            }

            // SEC-16: if the snapshot carries an encrypted `secrets` section, store it so the UI
            // layer can decrypt and apply it with a user-supplied passphrase. The section is not
            // applied here because Repository does not know the passphrase.
            if (json.has("secrets")) {
                val secretsObj = json.getJSONObject("secrets")
                val names = secretsObj.keys().asSequence().toList()
                for (name in names) {
                    val enc = secretsObj.optString(name)
                    if (enc.isNotBlank()) {
                        saveConnectorConfig(name, connectorConfig(name)?.let { JSONObject(it.toString()) } ?: JSONObject())
                    }
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
                        // A snapshot has no secrets (SEC-2), so merge instead of overwriting: the
                        // same device keeps its stored credentials, a new one asks for them again.
                        val merged = connectorConfig(id)?.let { existing -> JSONObject(existing.toString()) }
                            ?: JSONObject()
                        for (key in config.keys().asSequence().toList()) merged.put(key, config.get(key))
                        saveConnectorConfig(id, merged)
                    }
                }
            }

            if (json.has("skills")) {
                val array = json.getJSONArray("skills")
                for (i in 0 until array.length()) {
                    val s = array.optJSONObject(i) ?: continue
                    val id = s.optString("id")
                    if (id.isBlank()) continue
                    db.skillDao().insert(
                        SkillEntity(
                            id = id,
                            name = s.optString("name", ""),
                            description = s.optString("description", ""),
                            category = s.optString("category", ""),
                            version = s.optString("version", "1"),
                            author = s.optString("author", "Dunder"),
                            manifestJson = s.optString("manifestJson", "{}"),
                            installedAt = s.optLong("installedAt", Time.now()),
                            updatedAt = s.optLong("updatedAt", Time.now()),
                            enabled = s.optBoolean("enabled", true),
                        ),
                    )
                }
            }

            if (json.has("agent_skills")) {
                val array = json.getJSONArray("agent_skills")
                for (i in 0 until array.length()) {
                    val agentSkillJson = array.optJSONObject(i) ?: continue
                    val agentId = agentSkillJson.optString("agentId")
                    val skillId = agentSkillJson.optString("skillId")
                    if (agentId.isBlank() || skillId.isBlank()) continue
                    db.agentSkillDao().insert(
                        AgentSkillEntity(
                            agentId = agentId,
                            skillId = skillId,
                            enabled = agentSkillJson.optBoolean("enabled", true),
                            configJson = agentSkillJson.optString("config", "{}"),
                            installedAt = agentSkillJson.optLong("installedAt", Time.now()),
                        ),
                    )
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
                        isUndeletable = a.optBoolean("isUndeletable", existing?.isUndeletable ?: false),
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

            // Ensure seed agents exist after import (especially Platform Engineer)
            ensureSeedAgents()

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
