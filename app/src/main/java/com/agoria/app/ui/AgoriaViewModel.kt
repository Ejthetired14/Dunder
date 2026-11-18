package com.agoria.app.ui

import android.app.Application
import androidx.lifecycle.AndroidViewModel
import androidx.lifecycle.viewModelScope
import com.agoria.app.AgoriaApplication
import com.agoria.app.core.llm.LlmMessage
import com.agoria.app.core.llm.LlmRequest
import com.agoria.app.data.AgentEntity
import com.agoria.app.data.BuildEntity
import com.agoria.app.data.SettingsEntity
import com.agoria.app.service.AutonomyService
import com.agoria.app.util.Files
import com.agoria.app.util.Time
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.SharingStarted
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.flow.combine
import kotlinx.coroutines.flow.stateIn
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext
import org.json.JSONObject
import java.io.File
import java.util.UUID
import kotlin.random.Random

data class ProviderTest(
    val testing: Boolean = false,
    val ok: Boolean? = null,
    val message: String = "",
)

class AgoriaViewModel(application: Application) : AndroidViewModel(application) {

    private val app = application as AgoriaApplication
    private val repo = app.repository
    private val runtime = app.runtime

    val agents = repo.agents.stateIn(
        viewModelScope,
        SharingStarted.WhileSubscribed(5_000),
        emptyList(),
    )

    val diary = repo.diary.stateIn(
        viewModelScope,
        SharingStarted.WhileSubscribed(5_000),
        emptyList(),
    )

    val tasks = repo.tasks.stateIn(
        viewModelScope,
        SharingStarted.WhileSubscribed(5_000),
        emptyList(),
    )

    val approvals = repo.approvals.stateIn(
        viewModelScope,
        SharingStarted.WhileSubscribed(5_000),
        emptyList(),
    )

    val pendingApprovals = repo.pendingApprovals.stateIn(
        viewModelScope,
        SharingStarted.WhileSubscribed(5_000),
        emptyList(),
    )

    val connectors = repo.connectors.stateIn(
        viewModelScope,
        SharingStarted.WhileSubscribed(5_000),
        emptyList(),
    )

    val connectorRequests = repo.connectorRequests.stateIn(
        viewModelScope,
        SharingStarted.WhileSubscribed(5_000),
        emptyList(),
    )

    val pendingConnectorRequests = repo.pendingConnectorRequests.stateIn(
        viewModelScope,
        SharingStarted.WhileSubscribed(5_000),
        emptyList(),
    )

    val builds = repo.builds.stateIn(
        viewModelScope,
        SharingStarted.WhileSubscribed(5_000),
        emptyList(),
    )

    val providers = repo.providers.stateIn(
        viewModelScope,
        SharingStarted.WhileSubscribed(5_000),
        emptyList(),
    )

    val settings = repo.settingsFlow.stateIn(
        viewModelScope,
        SharingStarted.WhileSubscribed(5_000),
        null,
    )

    val memories = repo.memories.stateIn(
        viewModelScope,
        SharingStarted.WhileSubscribed(5_000),
        emptyList(),
    )

    val connectorLogs = repo.connectorLogs.stateIn(
        viewModelScope,
        SharingStarted.WhileSubscribed(5_000),
        emptyList(),
    )

    val revenue = repo.revenue.stateIn(
        viewModelScope,
        SharingStarted.WhileSubscribed(5_000),
        emptyList(),
    )

    val runtimeState = runtime.state

    val pendingCount = combine(repo.pendingApprovals, repo.pendingConnectorRequests) { a, c ->
        a.size + c.size
    }.stateIn(viewModelScope, SharingStarted.WhileSubscribed(5_000), 0)

    private val _providerTests = MutableStateFlow<Map<String, ProviderTest>>(emptyMap())
    val providerTests = _providerTests.asStateFlow()

    private val _setupConnectorId = MutableStateFlow<String?>(null)
    val setupConnectorId = _setupConnectorId.asStateFlow()

    fun messagesFor(agentId: String) = repo.messagesFor(agentId)
    fun diaryFor(agentId: String) = repo.diaryFor(agentId)
    fun tasksFor(agentId: String) = repo.tasksFor(agentId)
    fun approvalsFor(agentId: String) = repo.approvalsFor(agentId)
    fun buildsFor(agentId: String) = repo.buildsFor(agentId)
    fun memoriesFor(agentId: String) = repo.memoriesFor(agentId)

    fun setAutonomy(enabled: Boolean) {
        viewModelScope.launch {
            repo.updateSettings { it.copy(autonomyEnabled = enabled) }
            if (enabled) {
                AutonomyService.start(getApplication())
                runtime.start()
            } else {
                AutonomyService.stop(getApplication())
                runtime.stop()
            }
        }
    }

    fun startAutonomy() = setAutonomy(true)
    fun stopAutonomy() = setAutonomy(false)

    fun syncAutonomy() {
        viewModelScope.launch {
            val settings = repo.settings()
            if (settings.autonomyEnabled) {
                AutonomyService.start(getApplication())
                runtime.start()
            } else {
                AutonomyService.stop(getApplication())
                runtime.stop()
            }
        }
    }

    fun runNow() {
        viewModelScope.launch { runtime.tickOnce() }
    }

    fun nudgeAll() {
        viewModelScope.launch { runtime.nudgeAll() }
    }

    fun sendMessage(agentId: String, text: String) {
        viewModelScope.launch { runtime.chat(agentId, text) }
    }

    fun approve(approvalId: String, note: String = "") {
        viewModelScope.launch { runtime.approve(approvalId, note) }
    }

    fun deny(approvalId: String, note: String = "") {
        viewModelScope.launch { runtime.deny(approvalId, note) }
    }

    fun approveConnectorRequest(requestId: String) {
        viewModelScope.launch {
            val connectorId = runtime.approveConnectorRequest(requestId)
            _setupConnectorId.value = connectorId
        }
    }

    fun denyConnectorRequest(requestId: String, note: String = "") {
        viewModelScope.launch { runtime.denyConnectorRequest(requestId, note) }
    }

    fun clearSetupConnector() {
        _setupConnectorId.value = null
    }

    fun pauseAgent(agentId: String) {
        viewModelScope.launch {
            val agent = repo.agent(agentId) ?: return@launch
            repo.updateAgent(
                agent.copy(
                    autonomy = false,
                    status = "paused",
                    updatedAt = Time.now(),
                ),
            )
        }
    }

    fun resumeAgent(agentId: String) {
        viewModelScope.launch {
            val agent = repo.agent(agentId) ?: return@launch
            repo.updateAgent(
                agent.copy(
                    autonomy = true,
                    status = "idle",
                    lastTickAt = 0L,
                    updatedAt = Time.now(),
                ),
            )
        }
    }

    fun updateAgent(agent: AgentEntity) {
        viewModelScope.launch {
            repo.updateAgent(agent.copy(updatedAt = Time.now()))
        }
    }

    fun addMemory(agentId: String, kind: String, content: String, importance: Int) {
        viewModelScope.launch {
            repo.addMemory(agentId, kind, content, importance)
        }
    }

    fun addAgent(name: String, role: String, persona: String, goal: String) {
        viewModelScope.launch {
            val now = Time.now()
            repo.upsertAgent(
                AgentEntity(
                    id = UUID.randomUUID().toString(),
                    name = name.trim().ifBlank { "Agent" },
                    role = role.trim().ifBlank { "Generalist" },
                    persona = persona.trim().ifBlank { "You are a curious autonomous agent with your own judgment." },
                    goal = goal.trim().ifBlank { "Learn what this office needs and help." },
                    avatarIndex = Random.nextInt(8),
                    status = "idle",
                    autonomy = true,
                    memory = "",
                    lastTickAt = 0L,
                    failureCount = 0,
                    lastError = null,
                    createdAt = now,
                    updatedAt = now,
                ),
            )
        }
    }

    fun deleteAgent(agentId: String) {
        viewModelScope.launch { repo.deleteAgent(agentId) }
    }

    fun saveProviderKey(providerId: String, key: String) {
        viewModelScope.launch { repo.saveProviderKey(providerId, key) }
    }

    fun setProviderEnabled(providerId: String, enabled: Boolean) {
        viewModelScope.launch { repo.setProviderEnabled(providerId, enabled) }
    }

    fun setProviderModel(providerId: String, model: String) {
        viewModelScope.launch { repo.setProviderModel(providerId, model) }
    }

    fun saveProviderExtra(providerId: String, extra: String) {
        viewModelScope.launch { repo.saveProviderExtra(providerId, extra) }
    }

    fun testProvider(providerId: String) {
        _providerTests.value = _providerTests.value + (providerId to ProviderTest(testing = true))
        viewModelScope.launch {
            val result = app.llm.completeWithProvider(
                providerId,
                LlmRequest(
                    system = "You are a connectivity test. Reply with exactly: OK",
                    messages = listOf(LlmMessage("user", "Reply with exactly: OK")),
                    temperature = 0.0,
                    maxTokens = 16,
                ),
            )
            _providerTests.value = _providerTests.value + (
                providerId to ProviderTest(
                    testing = false,
                    ok = result.ok,
                    message = if (result.ok) {
                        "OK — ${result.providerName} (${result.latencyMs} ms)"
                    } else {
                        result.error ?: "Failed"
                    },
                )
                )
        }
    }

    fun saveConnectorConfig(connectorId: String, config: JSONObject) {
        viewModelScope.launch {
            repo.saveConnectorConfig(connectorId, config)
            val request = repo.connectorRequestForConnector(connectorId)
            if (request != null) {
                repo.updateConnectorRequest(request.copy(status = "approved", decidedAt = Time.now()))
                repo.addDiary(
                    request.agentId,
                    "connector",
                    "The ${request.connectorId} connector is configured. I can use it now.",
                    3,
                )
                repo.setAgentStatus(request.agentId, "idle")
            }
        }
    }

    suspend fun getConnectorConfig(connectorId: String): JSONObject? = repo.connectorConfig(connectorId)

    fun clearConnectorConfig(connectorId: String) {
        viewModelScope.launch { repo.clearConnectorConfig(connectorId) }
    }

    fun updateSettings(block: (SettingsEntity) -> SettingsEntity) {
        viewModelScope.launch { repo.updateSettings(block) }
    }

    fun finishSetup(userName: String) {
        viewModelScope.launch {
            repo.updateSettings {
                it.copy(
                    setupComplete = true,
                    userName = userName.trim().ifBlank { it.userName },
                )
            }
            AutonomyService.start(getApplication())
            runtime.start()
        }
    }

    fun exportData(onFile: (File) -> Unit) {
        viewModelScope.launch {
            val snapshot = repo.exportSnapshot()
            val file = File(
                Files.exports(getApplication()),
                "agoria-export-${System.currentTimeMillis()}.json",
            )
            withContext(Dispatchers.IO) {
                Files.writeText(file, snapshot.toString(2))
            }
            withContext(Dispatchers.Main) {
                onFile(file)
            }
        }
    }

    // Revenue methods
    fun setProfitSplit(agentPercent: Int, userPercent: Int) {
        viewModelScope.launch { repo.setProfitSplit(agentPercent, userPercent) }
    }

    fun setStripeKeys(accountId: String?, publishableKey: String?) {
        viewModelScope.launch { repo.setStripeKeys(accountId, publishableKey) }
    }

    fun resetData() {
        viewModelScope.launch {
            runtime.stop()
            repo.reset()
        }
    }

    fun shareBuild(build: BuildEntity, onReady: (File) -> Unit) {
        val file = File(build.zipPath)
        if (file.exists()) {
            onReady(file)
        }
    }
}