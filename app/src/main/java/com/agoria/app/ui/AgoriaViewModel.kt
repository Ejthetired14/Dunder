package com.agoria.app.ui

import android.app.Application
import android.content.Context
import android.net.Uri
import androidx.lifecycle.AndroidViewModel
import androidx.lifecycle.viewModelScope
import com.agoria.app.AgoriaApplication
import com.agoria.app.core.BuildProgress
import com.agoria.app.core.llm.LlmMessage
import com.agoria.app.core.llm.LlmRequest
import com.agoria.app.data.AgentEntity
import com.agoria.app.data.BuildEntity
import com.agoria.app.data.DiaryEntryEntity
import com.agoria.app.data.MessageEntity
import com.agoria.app.data.MemoryEntity
import com.agoria.app.data.SettingsEntity
import com.agoria.app.data.TaskEntity
import com.agoria.app.service.AutonomyService
import com.agoria.app.util.Files
import com.agoria.app.util.Share
import com.agoria.app.util.Time
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.SharingStarted
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.flow.combine
import kotlinx.coroutines.flow.distinctUntilChanged
import kotlinx.coroutines.flow.map
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

// Combined state for HomeScreen to reduce Flow collections
data class HomeScreenState(
    val agents: List<AgentEntity>,
    val diary: List<DiaryEntryEntity>,
    val pendingApprovals: List<com.agoria.app.data.ApprovalEntity>,
    val settings: SettingsEntity?,
    val runtimeState: com.agoria.app.core.RuntimeState,
)

// Combined state for SettingsScreen to reduce Flow collections
data class SettingsScreenState(
    val settings: SettingsEntity?,
    val providers: List<com.agoria.app.data.ProviderEntity>,
    val providerTests: Map<String, ProviderTest>,
    val revenue: List<com.agoria.app.data.RevenueEntity>,
    val connectors: List<com.agoria.app.data.ConnectorEntity>,
)

// Combined state for AgentScreen to reduce Flow collections
data class AgentScreenState(
    val agent: AgentEntity?,
    val messages: List<MessageEntity>,
    val diary: List<DiaryEntryEntity>,
    val tasks: List<com.agoria.app.data.TaskEntity>,
    val builds: List<BuildEntity>,
    val memories: List<com.agoria.app.data.MemoryEntity>,
)

// Pagination state for lists
data class PaginationState(
    val page: Int = 0,
    val pageSize: Int = 20,
    val hasMore: Boolean = true,
    val isLoading: Boolean = false,
)

class AgoriaViewModel(application: Application) : AndroidViewModel(application) {

    private val app = application as AgoriaApplication
    private val repo = app.repository
    private val runtime = app.runtime
    val appScope = app.appScope

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

    // Combined state flows to reduce individual Flow collections
    val homeScreenState = combine(
        agents,
        diary,
        pendingApprovals,
        settings,
        runtimeState,
    ) { a, d, p, s, r ->
        HomeScreenState(a, d, p, s, r)
    }.stateIn(viewModelScope, SharingStarted.WhileSubscribed(5_000), HomeScreenState(emptyList(), emptyList(), emptyList(), null, com.agoria.app.core.RuntimeState()))

    private val _providerTests = MutableStateFlow<Map<String, ProviderTest>>(emptyMap())
    val providerTests = _providerTests.asStateFlow()

    private val _setupConnectorId = MutableStateFlow<String?>(null)
    val setupConnectorId = _setupConnectorId.asStateFlow()

    val settingsScreenState = combine(
        settings,
        providers,
        providerTests,
        revenue,
        connectors,
    ) { s, p, t, r, c ->
        SettingsScreenState(s, p, t, r, c)
    }.stateIn(viewModelScope, SharingStarted.WhileSubscribed(5_000), SettingsScreenState(null, emptyList(), emptyMap(), emptyList(), emptyList()))

    // Agent screen state is per-agent, created on demand via agentScreenState(agentId)
    private val agentScreenStates = MutableStateFlow<Map<String, kotlinx.coroutines.flow.StateFlow<AgentScreenState>>>(emptyMap())

    private data class AgentScreenPartialState(
        val agent: AgentEntity?,
        val messages: List<MessageEntity>,
        val diary: List<DiaryEntryEntity>,
        val tasks: List<TaskEntity>,
        val builds: List<BuildEntity> = emptyList(),
    )

    fun agentScreenState(agentId: String): kotlinx.coroutines.flow.StateFlow<AgentScreenState> = agentScreenStates.value[agentId]
        ?: run {
            val agentFlow = agents.map { list ->
                list.firstOrNull { it.id == agentId }
            }
            val flow = combine(
                agentFlow,
                messagesFor(agentId),
                diaryFor(agentId),
                tasksFor(agentId),
            ) { agent, messages, diary, tasks ->
                AgentScreenPartialState(agent, messages, diary, tasks)
            }.combine(buildsFor(agentId)) { state, builds ->
                state.copy(builds = builds)
            }.combine(memoriesFor(agentId)) { state, memories ->
                AgentScreenState(
                    state.agent, state.messages, state.diary,
                    state.tasks, state.builds, memories,
                )
            }.distinctUntilChanged()
                .stateIn(
                    viewModelScope,
                    SharingStarted.WhileSubscribed(5_000),
                    AgentScreenState(null, emptyList(), emptyList(), emptyList(), emptyList(), emptyList()),
                )
            agentScreenStates.value = agentScreenStates.value + (agentId to flow)
            flow
        }

    // Pagination states
    private val _buildsPagination = MutableStateFlow(PaginationState())
    val buildsPagination = _buildsPagination.asStateFlow()

    private val _diaryPagination = MutableStateFlow(PaginationState())
    val diaryPagination = _diaryPagination.asStateFlow()

    private val _messagesPagination = MutableStateFlow<Map<String, PaginationState>>(emptyMap())
    fun messagesPagination(agentId: String): PaginationState = _messagesPagination.value[agentId] ?: PaginationState()
    fun setMessagesPagination(agentId: String, state: PaginationState) {
        _messagesPagination.value = _messagesPagination.value + (agentId to state)
    }

    // Build progress flow (exposed from AgentRuntime)
    val buildProgress = runtime.buildProgress

    fun messagesFor(agentId: String) = repo.messagesFor(agentId)
    fun diaryFor(agentId: String) = repo.diaryFor(agentId)
    fun tasksFor(agentId: String) = repo.tasksFor(agentId)
    fun approvalsFor(agentId: String) = repo.approvalsFor(agentId)
    fun buildsFor(agentId: String) = repo.buildsFor(agentId)
    fun memoriesFor(agentId: String) = repo.memoriesFor(agentId)

    // Paged data holders with loading state
    private val _pagedBuilds = MutableStateFlow<List<BuildEntity>>(emptyList())
    val pagedBuilds = _pagedBuilds.asStateFlow()

    private val _pagedDiary = MutableStateFlow<List<DiaryEntryEntity>>(emptyList())
    val pagedDiary = _pagedDiary.asStateFlow()

    private val _pagedMessages = MutableStateFlow<Map<String, List<MessageEntity>>>(emptyMap())
    fun pagedMessages(agentId: String) = _pagedMessages.value[agentId] ?: emptyList()

    // Paged data loading functions
    fun loadPagedBuilds(agentId: String, refresh: Boolean = false) {
        val pagination = if (refresh) PaginationState() else _buildsPagination.value
        if (pagination.isLoading && !refresh) return
        
        val currentPagination = if (refresh) PaginationState() else pagination.copy(isLoading = true)
        _buildsPagination.value = currentPagination
        
        viewModelScope.launch(Dispatchers.IO) {
            val limit = currentPagination.pageSize
            val offset = currentPagination.page * limit
            val data = repo.pagedBuildsForAgent(agentId, limit, offset)
            val totalCount = repo.countBuildsForAgent(agentId)
            val hasMore = offset + data.size < totalCount
            
            withContext(Dispatchers.Main) {
                if (refresh || currentPagination.page == 0) {
                    _pagedBuilds.value = data
                } else {
                    _pagedBuilds.value = _pagedBuilds.value + data
                }
                _buildsPagination.value = currentPagination.copy(isLoading = false, hasMore = hasMore)
            }
        }
    }

    fun loadPagedDiary(agentId: String, refresh: Boolean = false) {
        val pagination = if (refresh) PaginationState() else _diaryPagination.value
        if (pagination.isLoading && !refresh) return
        
        val currentPagination = if (refresh) PaginationState() else pagination.copy(isLoading = true)
        _diaryPagination.value = currentPagination
        
        viewModelScope.launch(Dispatchers.IO) {
            val limit = currentPagination.pageSize
            val offset = currentPagination.page * limit
            val data = repo.pagedDiaryForAgent(agentId, limit, offset)
            val totalCount = repo.countDiaryForAgent(agentId)
            val hasMore = offset + data.size < totalCount
            
            withContext(Dispatchers.Main) {
                if (refresh || currentPagination.page == 0) {
                    _pagedDiary.value = data
                } else {
                    _pagedDiary.value = _pagedDiary.value + data
                }
                _diaryPagination.value = currentPagination.copy(isLoading = false, hasMore = hasMore)
            }
        }
    }

    fun loadPagedMessages(agentId: String, refresh: Boolean = false) {
        val pagination = if (refresh) PaginationState() else messagesPagination(agentId)
        if (pagination.isLoading && !refresh) return
        
        val currentPagination = if (refresh) PaginationState() else pagination.copy(isLoading = true)
        setMessagesPagination(agentId, currentPagination)
        
        viewModelScope.launch(Dispatchers.IO) {
            val limit = currentPagination.pageSize
            val offset = currentPagination.page * limit
            val data = repo.pagedMessagesForAgent(agentId, limit, offset)
            val totalCount = repo.countMessagesForAgent(agentId)
            val hasMore = offset + data.size < totalCount
            
            withContext(Dispatchers.Main) {
                val currentMessages = _pagedMessages.value[agentId] ?: emptyList()
                if (refresh || currentPagination.page == 0) {
                    _pagedMessages.value = _pagedMessages.value + (agentId to data)
                } else {
                    _pagedMessages.value = _pagedMessages.value + (agentId to (currentMessages + data))
                }
                setMessagesPagination(agentId, currentPagination.copy(isLoading = false, hasMore = hasMore))
            }
        }
    }

    fun resetPagedBuilds(agentId: String) {
        _buildsPagination.value = PaginationState()
        _pagedBuilds.value = emptyList()
        loadPagedBuilds(agentId, refresh = true)
    }

    fun resetPagedDiary(agentId: String) {
        _diaryPagination.value = PaginationState()
        _pagedDiary.value = emptyList()
        loadPagedDiary(agentId, refresh = true)
    }

    fun resetPagedMessages(agentId: String) {
        setMessagesPagination(agentId, PaginationState())
        _pagedMessages.value = _pagedMessages.value - agentId
        loadPagedMessages(agentId, refresh = true)
    }

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

    fun importData(file: File, onResult: (Boolean) -> Unit) {
        viewModelScope.launch {
            val content = withContext(Dispatchers.IO) { Files.readText(file) }
            val json = try { JSONObject(content) } catch (e: Exception) { JSONObject() }
            val success = repo.importSnapshot(json)
            withContext(Dispatchers.Main) {
                onResult(success)
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

    fun saveBuildToDownloads(context: Context, build: BuildEntity, onResult: (Uri?) -> Unit) {
        viewModelScope.launch(Dispatchers.IO) {
            val file = File(build.zipPath)
            val uri = Share.saveToDownloads(context, file, "agoria-${build.name}-${build.id.take(8)}.zip")
            withContext(Dispatchers.Main) {
                onResult(uri)
            }
        }
    }

    fun checkForUpdates(onResult: (com.agoria.app.util.UpdateManager.ReleaseInfo?) -> Unit) {
        app.updateManager.checkForUpdates(onResult)
    }

    fun isNewerRelease(release: com.agoria.app.util.UpdateManager.ReleaseInfo): Boolean {
        return app.updateManager.isNewerRelease(release)
    }

    fun downloadAndInstallUpdate(
        release: com.agoria.app.util.UpdateManager.ReleaseInfo,
        onProgress: (Int) -> Unit,
        onComplete: (Boolean) -> Unit,
    ) {
        app.updateManager.downloadAndInstall(release, onProgress, onComplete)
    }

    suspend fun executeConnector(
        connectorId: String,
        agentId: String?,
        action: String,
        args: org.json.JSONObject,
    ): com.agoria.app.core.ConnectorExecutor.ConnectorResult {
        return app.runtime.connectorExecutor.execute(connectorId, agentId, action, args)
    }
}