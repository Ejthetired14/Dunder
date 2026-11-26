package com.agoria.app.data

import androidx.room.Entity
import androidx.room.ForeignKey
import androidx.room.Index
import androidx.room.PrimaryKey

@Entity(tableName = "agents")
data class AgentEntity(
    @PrimaryKey val id: String,
    val name: String,
    val role: String,
    val persona: String,
    val goal: String,
    val avatarIndex: Int,
    val status: String,
    val autonomy: Boolean,
    val memory: String,
    val lastTickAt: Long,
    val failureCount: Int,
    val lastError: String?,
    val createdAt: Long,
    val updatedAt: Long,
    /** Comma separated connector ids this agent may use. Empty means every enabled connector. */
    val allowedConnectors: String = "",
    /** Optional bearer token this agent alone can present through the HTTP connector. */
    val apiToken: String = "",
    /** If true, this agent cannot be deleted from the UI. Used for the Platform Engineer (the Boss's representative). */
    val isUndeletable: Boolean = false,
)

@Entity(
    tableName = "diary_entries",
    foreignKeys = [
        ForeignKey(
            entity = AgentEntity::class,
            parentColumns = ["id"],
            childColumns = ["agentId"],
            onDelete = ForeignKey.CASCADE,
        )
    ],
    indices = [Index("agentId"), Index("createdAt")],
)
data class DiaryEntryEntity(
    @PrimaryKey val id: String,
    val agentId: String,
    val kind: String,
    val content: String,
    val importance: Int,
    val pinned: Boolean,
    val createdAt: Long,
)

@Entity(
    tableName = "messages",
    foreignKeys = [
        ForeignKey(
            entity = AgentEntity::class,
            parentColumns = ["id"],
            childColumns = ["agentId"],
            onDelete = ForeignKey.CASCADE,
        )
    ],
    indices = [Index("agentId"), Index("createdAt")],
)
data class MessageEntity(
    @PrimaryKey val id: String,
    val agentId: String,
    val role: String,
    val content: String,
    val createdAt: Long,
)

@Entity(
    tableName = "tasks",
    foreignKeys = [
        ForeignKey(
            entity = AgentEntity::class,
            parentColumns = ["id"],
            childColumns = ["agentId"],
            onDelete = ForeignKey.CASCADE,
        )
    ],
    indices = [Index("agentId"), Index("status"), Index("createdAt"), Index("projectId")],
)
data class TaskEntity(
    @PrimaryKey val id: String,
    val agentId: String,
    val title: String,
    val description: String,
    val status: String,
    val progress: Int,
    val createdAt: Long,
    val updatedAt: Long,
    /** Project this task belongs to. Empty means the office default project. */
    val projectId: String = "",
)

@Entity(
    tableName = "approvals",
    foreignKeys = [
        ForeignKey(
            entity = AgentEntity::class,
            parentColumns = ["id"],
            childColumns = ["agentId"],
            onDelete = ForeignKey.CASCADE,
        )
    ],
    indices = [Index("agentId"), Index("status"), Index("createdAt")],
)
data class ApprovalEntity(
    @PrimaryKey val id: String,
    val agentId: String,
    val type: String,
    val title: String,
    val summary: String,
    val details: String,
    val risk: String,
    val status: String,
    val createdAt: Long,
    val decidedAt: Long?,
    val result: String?,
)

@Entity(tableName = "connectors")
data class ConnectorEntity(
    @PrimaryKey val id: String,
    val name: String,
    val category: String,
    val status: String,
    val configEnc: String?,
    val scopes: String,
    val lastUsed: Long?,
    val lastError: String?,
    val createdAt: Long,
)

@Entity(
    tableName = "connector_requests",
    foreignKeys = [
        ForeignKey(
            entity = AgentEntity::class,
            parentColumns = ["id"],
            childColumns = ["agentId"],
            onDelete = ForeignKey.CASCADE,
        )
    ],
    indices = [Index("agentId"), Index("status")],
)
data class ConnectorRequestEntity(
    @PrimaryKey val id: String,
    val agentId: String,
    val connectorId: String,
    val reason: String,
    val scopes: String,
    val status: String,
    val createdAt: Long,
    val decidedAt: Long?,
)

@Entity(
    tableName = "builds",
    foreignKeys = [
        ForeignKey(
            entity = AgentEntity::class,
            parentColumns = ["id"],
            childColumns = ["agentId"],
            onDelete = ForeignKey.CASCADE,
        )
    ],
    indices = [Index("agentId"), Index("createdAt"), Index("projectId")],
)
data class BuildEntity(
    @PrimaryKey val id: String,
    val agentId: String,
    val agentName: String = "",          // Denormalized: build history keeps the agent name.
    val name: String,
    val description: String,
    val fileCount: Int,
    val zipPath: String,
    val status: String,
    val createdAt: Long,
    val completedAt: Long?,
    /** Project this build belongs to. Empty means the office default project. */
    val projectId: String = "",
)

/**
 * One shared project folder for the whole office. Every build, task and file of the project lives
 * here, and only its lead agent may start a build, so the progress is in one place instead of
 * scattered across each agent's tab.
 */
@Entity(tableName = "projects")
data class ProjectEntity(
    @PrimaryKey val id: String,
    val name: String,
    val description: String,
    val leadAgentId: String,
    val status: String,
    /** Folder name under the app's project workspace. */
    val folder: String,
    val createdAt: Long,
    val updatedAt: Long,
)

@Entity(tableName = "providers")
data class ProviderEntity(
    @PrimaryKey val id: String,
    val enabled: Boolean,
    val priority: Int,
    val model: String,
    val apiKeyEnc: String?,
    val extra: String?,
    val lastStatus: String?,
    val lastLatencyMs: Long?,
    val lastError: String?,
    val updatedAt: Long,
)

@Entity(
    tableName = "connector_logs",
    foreignKeys = [
        ForeignKey(
            entity = ConnectorEntity::class,
            parentColumns = ["id"],
            childColumns = ["connectorId"],
            onDelete = ForeignKey.CASCADE,
        ),
        ForeignKey(
            entity = AgentEntity::class,
            parentColumns = ["id"],
            childColumns = ["agentId"],
            onDelete = ForeignKey.SET_NULL,
        ),
    ],
    indices = [Index("connectorId"), Index("agentId"), Index("createdAt")],
)
data class ConnectorLogEntity(
    @PrimaryKey val id: String,
    val connectorId: String,
    val agentId: String?,
    val action: String,
    val status: String,
    val summary: String,
    val createdAt: Long,
)

@Entity(
    tableName = "revenue",
    foreignKeys = [
        ForeignKey(
            entity = AgentEntity::class,
            parentColumns = ["id"],
            childColumns = ["agentId"],
            onDelete = ForeignKey.CASCADE,
        )
    ],
    indices = [Index("agentId"), Index("createdAt"), Index("source")]
)
data class RevenueEntity(
    @PrimaryKey val id: String,
    val agentId: String,
    val agentName: String = "",          // Denormalized: survives agent deletion so financial
                                          // history keeps a name even if the agent row is gone.
    val source: String,           // "api_usage", "subscription", "one_time", "deployment_fee"
    val amountCents: Long,
    val currency: String = "USD",
    val metadata: String,         // JSON: endpoint, customer, stripe_event_id, etc.
    val createdAt: Long,
)

@Entity(tableName = "settings")
data class SettingsEntity(
    @PrimaryKey val id: Int = 1,
    val autonomyEnabled: Boolean,
    val tickIntervalSeconds: Int,
    val agentCooldownSeconds: Int,
    val setupComplete: Boolean,
    val userName: String,
    val officeName: String,
    val theme: String,
    val maxTokens: Int,
    val seedVersion: Int,
    // Monetization settings
    val agentProfitSharePercent: Int = 50,
    val userProfitSharePercent: Int = 50,
    val infrastructureFundCents: Long = 0,
    val stripeAccountId: String? = null,
    val stripePublishableKey: String? = null,
    // Auto-approval settings
    val autoApproveEnabled: Boolean = false,
    val autoApproveTypes: String = "", // comma-separated: build_project,connector_action,send_email,etc.
    val createdAt: Long,
)

@Entity(
    tableName = "memories",
    foreignKeys = [
        ForeignKey(
            entity = AgentEntity::class,
            parentColumns = ["id"],
            childColumns = ["agentId"],
            onDelete = ForeignKey.CASCADE,
        )
    ],
    indices = [Index("agentId"), Index("createdAt")]
)
data class MemoryEntity(
    @PrimaryKey val id: String,
    val agentId: String,
    val agentName: String = "",          // Denormalized: memory keeps the agent name.
    val kind: String,
    val content: String,
    val importance: Int,
    val createdAt: Long,
)