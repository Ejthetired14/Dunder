package com.agoria.app.core

import com.agoria.app.data.AgentEntity
import com.agoria.app.data.ApprovalEntity
import com.agoria.app.data.BuildEntity
import com.agoria.app.data.DiaryEntryEntity
import com.agoria.app.data.MemoryEntity
import com.agoria.app.data.MessageEntity
import com.agoria.app.data.TaskEntity
import org.json.JSONObject

data class ConnectorBrief(
    val id: String,
    val name: String,
    val status: String,
    val summary: String,
    val ready: Boolean,
)

data class ProviderBrief(
    val id: String,
    val name: String,
    val enabled: Boolean,
    val hasKey: Boolean,
    val keyless: Boolean,
    val model: String,
    val lastStatus: String?,
)

data class PeerInfo(
    val name: String,
    val role: String,
    val status: String,
    val goal: String,
)

data class AgentContext(
    val agent: AgentEntity,
    val officeName: String,
    val userName: String,
    val now: Long,
    val diary: List<DiaryEntryEntity>,
    val memories: List<MemoryEntity>,
    val messages: List<MessageEntity>,
    val tasks: List<TaskEntity>,
    val approvals: List<ApprovalEntity>,
    val builds: List<BuildEntity>,
    val connectors: List<ConnectorBrief>,
    val providers: List<ProviderBrief>,
    val peers: List<PeerInfo>,
)

data class AgentDecision(
    val diary: String,
    val diaryKind: String,
    val message: String,
    val action: String,
    val input: JSONObject,
    val importance: Int,
)

data class ApprovalDraft(
    val type: String,
    val title: String,
    val summary: String,
    val details: String,
    val risk: String,
)

data class ConnectorRequestDraft(
    val connectorId: String,
    val reason: String,
    val scopes: String,
)

data class RuntimeLogEntry(
    val at: Long,
    val message: String,
    val level: String,
)

data class RuntimeState(
    val running: Boolean = false,
    val ticking: Boolean = false,
    val lastTickAt: Long? = null,
    val currentAgentId: String? = null,
    val currentAgentName: String? = null,
    val lastSummary: String? = null,
    val log: List<RuntimeLogEntry> = emptyList(),
    val error: String? = null,
)