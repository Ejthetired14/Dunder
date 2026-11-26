package com.agoria.app.ui

import android.app.Application
import android.content.Context
import android.net.ConnectivityManager
import android.net.NetworkCapabilities
import android.net.Uri
import androidx.lifecycle.AndroidViewModel
import androidx.lifecycle.viewModelScope
import com.agoria.app.AgoriaApplication
import com.agoria.app.core.BuildProgress
import com.agoria.app.core.ConnectorResult
import com.agoria.app.core.llm.LlmMessage
import com.agoria.app.core.llm.LlmRequest
import com.agoria.app.data.AgentEntity
import com.agoria.app.data.AgentSkillEntity
import com.agoria.app.data.ApprovalEntity
import com.agoria.app.data.BuildEntity
import com.agoria.app.data.ConnectorRequestEntity
import com.agoria.app.data.DiaryEntryEntity
import com.agoria.app.data.MessageEntity
import com.agoria.app.data.MemoryEntity
import com.agoria.app.data.SettingsEntity
import com.agoria.app.data.TaskEntity
import com.agoria.app.core.SkillExecutor
import android.util.Log
import com.agoria.app.service.AutonomyService
import com.agoria.app.util.Files
import com.agoria.app.util.SecretVault
import com.agoria.app.util.Share
import com.agoria.app.util.Time
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.Job
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.delay
import kotlinx.coroutines.flow.SharingStarted
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.flow.combine
import kotlinx.coroutines.flow.distinctUntilChanged
import kotlinx.coroutines.flow.map
import kotlinx.coroutines.flow.stateIn
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext
import kotlin.coroutines.Continuation
import kotlin.coroutines.suspendCoroutine
import kotlin.Result
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

    // Polling-based pending approvals (replaces unreliable Room Flow observation)
    private val _pendingApprovals = MutableStateFlow<List<ApprovalEntity>>(emptyList())
    val pendingApprovals = _pendingApprovals.asStateFlow()

    private val _pendingConnectorRequests = MutableStateFlow<List<ConnectorRequestEntity>>(emptyList())
    val pendingConnectorRequests = _pendingConnectorRequests.asStateFlow()

    private fun startPendingApprovalsPolling() {
        viewModelScope.launch {
            Log.i("ApprovalFlow", "Pending approvals polling STARTED")
            // One-time debug dump of all approvals on startup
            viewModelScope.launch {
                try {
                    val all = repo.getAllApprovals()
                    Log.i("ApprovalFlow", "Startup debug: all approvals count = ${all.size}")
                } catch (e: Exception) {
                    Log.e("ApprovalFlow", "Startup debug dump failed", e)
                }
            }
            while (coroutineContext[Job]?.isActive == true) {
                try {
                    val approvals = repo.getAllPendingApprovals()
                    val requests = repo.getAllPendingConnectorRequests()
                    Log.i("ApprovalFlow", "Poll: approvals=${approvals.size} requests=${requests.size}")
                    if (approvals.isNotEmpty()) {
                        approvals.forEach { Log.d("ApprovalFlow", "  Pending: id=${it.id} agent=${it.agentId} type=${it.type} status=${it.status}") }
                    }
                    _pendingApprovals.value = approvals
                    _pendingConnectorRequests.value = requests
                } catch (e: Exception) {
                    Log.e("ApprovalFlow", "Pending approvals polling error", e)
                }
                delay(2000) // Poll every 2 seconds
            }
            Log.i("ApprovalFlow", "Pending approvals polling STOPPED")
        }
    }

    // Manual refresh for debugging
    fun refreshPendingApprovals() {
        viewModelScope.launch {
            try {
                val approvals = repo.getAllPendingApprovals()
                val requests = repo.getAllPendingConnectorRequests()
                Log.i("ApprovalFlow", "Manual refresh: approvals=${approvals.size} requests=${requests.size}")
                _pendingApprovals.value = approvals
                _pendingConnectorRequests.value = requests
            } catch (e: Exception) {
                Log.e("ApprovalFlow", "Manual refresh error", e)
            }
        }
    }

    init {
        startPendingApprovalsPolling()
        startUpdateCheck()
    }

    private fun startUpdateCheck() {
        viewModelScope.launch {
            // Initial check after a short delay
            delay(5000)
            checkForUpdate()
            
            // Periodic check every hour (only on WiFi)
            while (coroutineContext[Job]?.isActive == true) {
                delay(60 * 60 * 1000L) // 1 hour
                checkForUpdate()
            }
        }
    }

    private fun checkForUpdate() {
        // Only check on WiFi to be cheap on low-end devices
        val connectivityManager = getApplication<Application>().getSystemService(Context.CONNECTIVITY_SERVICE) as android.net.ConnectivityManager
        val network = connectivityManager.activeNetwork
        if (network == null) return
        val capabilities = connectivityManager.getNetworkCapabilities(network)
        if (capabilities == null || !capabilities.hasTransport(android.net.NetworkCapabilities.TRANSPORT_WIFI)) return
        
        app.updateManager.checkForUpdates { result ->
            if (result is com.agoria.app.util.UpdateManager.CheckResult.Available) {
                _updateReady.value = result.release
            }
        }
    }

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

    val projects = repo.projects.stateIn(
        viewModelScope,
        SharingStarted.WhileSubscribed(5_000),
        emptyList(),
    )

    /** Live view of every agent's own browser. */
    val browsers = com.agoria.app.browser.BrowserHub.sessions

    val runtimeState = runtime.state

    val pendingCount = combine(pendingApprovals, pendingConnectorRequests) { a, c ->
        a.size + c.size
    }.stateIn(viewModelScope, SharingStarted.WhileSubscribed(5_000), 0)

    // Update ready banner state
    private val _updateReady = MutableStateFlow<com.agoria.app.util.UpdateManager.ReleaseInfo?>(null)
    val updateReady = _updateReady.asStateFlow()

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

    /** Paging state per agent, so the chat can react when a page finishes loading. */
    val messagesPaginationByAgent: StateFlow<Map<String, PaginationState>> = _messagesPagination.asStateFlow()

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

    /** Paged message history per agent, newest first. */
    val pagedMessagesByAgent: StateFlow<Map<String, List<MessageEntity>>> = _pagedMessages.asStateFlow()

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
                    _pagedBuilds.value = (_pagedBuilds.value + data).distinctBy { it.id }
                }
                _buildsPagination.value = currentPagination.copy(
                    page = currentPagination.page + 1,
                    isLoading = false,
                    hasMore = hasMore,
                )
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
                    _pagedDiary.value = (_pagedDiary.value + data).distinctBy { it.id }
                }
                _diaryPagination.value = currentPagination.copy(
                    page = currentPagination.page + 1,
                    isLoading = false,
                    hasMore = hasMore,
                )
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
                val merged = if (refresh || currentPagination.page == 0) {
                    data
                } else {
                    // Pages walk backwards in time, so older messages append after the current ones.
                    (currentMessages + data).distinctBy { it.id }
                }
                _pagedMessages.value = _pagedMessages.value + (agentId to merged)
                setMessagesPagination(
                    agentId,
                    currentPagination.copy(
                        page = currentPagination.page + 1,
                        isLoading = false,
                        hasMore = hasMore,
                    ),
                )
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
        viewModelScope.launch {
            try {
                runtime.chat(agentId, text)
            } catch (e: Exception) {
                // A failed cycle used to vanish silently and leave the chat looking stuck.
                repo.addMessage(
                    agentId,
                    "system",
                    "That message could not be processed: ${e.message ?: e.javaClass.simpleName}",
                )
                repo.setAgentStatus(agentId, "idle")
            }
        }
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

    /** Turning a connector off stops agents reaching it without forgetting its saved keys. */
    fun toggleConnector(connectorId: String, enabled: Boolean) {
        viewModelScope.launch {
            val row = repo.connector(connectorId) ?: return@launch
            if (enabled) {
                val def = com.agoria.app.core.ConnectorCatalog.get(connectorId)
                val clean = when {
                    row.configEnc != null -> "configured"
                    def != null -> com.agoria.app.core.ConnectorCatalog.defaultStatus(def)
                    else -> "needs_setup"
                }
                repo.updateConnector(row.copy(status = clean, lastError = null))
            } else {
                repo.updateConnector(row.copy(status = "disabled"))
            }
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

    fun exportDataWithResult(onResult: (Boolean, String?) -> Unit) {
        viewModelScope.launch {
            try {
                val snapshot = repo.exportSnapshot()
                val file = File(
                    Files.exports(getApplication()),
                    "agoria-export-${System.currentTimeMillis()}.json",
                )
                withContext(Dispatchers.IO) {
                    Files.writeText(file, snapshot.toString(2))
                }
                withContext(Dispatchers.Main) {
                    onResult(true, file.absolutePath)
                }
            } catch (e: Exception) {
                withContext(Dispatchers.Main) {
                    onResult(false, e.message)
                }
            }
        }
    }

    /**
     * Exports data to a user-selected location via Storage Access Framework.
     * Call this after obtaining a URI from ActivityResultContracts.CreateDocument().
     */
    fun exportDataToUri(context: Context, uri: Uri, onResult: (Boolean, String?) -> Unit) {
        viewModelScope.launch(Dispatchers.IO) {
            try {
                val snapshot = repo.exportSnapshot()
                val jsonText = snapshot.toString(2)
                context.contentResolver.openOutputStream(uri).use { outputStream ->
                    outputStream?.write(jsonText.toByteArray())
                }
                withContext(Dispatchers.Main) {
                    onResult(true, "Saved to selected location")
                }
            } catch (e: Exception) {
                withContext(Dispatchers.Main) {
                    onResult(false, "Export failed: ${e.message}")
                }
            }
        }
    }

    /**
     * Reads a backup straight from the document the user picked. [android.net.Uri.path] is not a
     * file path for a document picked by the system, which is why importing used to fail.
     */
    fun importDataFromUri(context: Context, uri: Uri, onResult: (Boolean, String) -> Unit) {
        viewModelScope.launch {
            val text = withContext(Dispatchers.IO) {
                runCatching {
                    context.contentResolver.openInputStream(uri)?.bufferedReader()?.use { it.readText() }
                }.getOrNull()
            }
            if (text.isNullOrBlank()) {
                withContext(Dispatchers.Main) { onResult(false, "Could not read that file.") }
                return@launch
            }
            val json = try {
                JSONObject(text)
            } catch (e: Exception) {
                withContext(Dispatchers.Main) { onResult(false, "That file is not a Dunder backup.") }
                return@launch
            }
            val success = repo.importSnapshot(json)
            withContext(Dispatchers.Main) {
                onResult(
                    success,
                    if (success) "Backup restored." else "Import failed: the file could not be applied.",
                )
            }
        }
    }

    /**
     * Exports a snapshot that includes connector secrets, with the `secrets` section encrypted
     * under a user-supplied passphrase via SecretVault (SEC-16). The passphrase is never stored,
     * never logged, and never written to disk. A wrong passphrase cannot be recovered.
     */
    fun exportDataToUriWithSecrets(
        context: Context,
        uri: Uri,
        passphrase: String,
        onResult: (Boolean, String?) -> Unit,
    ) {
        viewModelScope.launch(Dispatchers.IO) {
            try {
                val snapshot = repo.exportSnapshot(includeSecrets = true)
                val secrets = snapshot.optJSONObject("secrets")
                if (secrets != null && secrets.length() > 0) {
                    val encrypted = JSONObject()
                    val names = secrets.keys().asSequence().toList()
                    for (name in names) {
                        val enc = secrets.optString(name)
                        if (enc.isNotBlank()) {
                            val ciphertext = SecretVault.encrypt(enc, passphrase)
                            if (ciphertext == null) {
                                withContext(Dispatchers.Main) {
                                    onResult(false, "Encryption failed.")
                                }
                                return@launch
                            }
                            encrypted.put(name, ciphertext)
                        }
                    }
                    snapshot.put("secrets", encrypted)
                }
                val jsonText = snapshot.toString(2)
                context.contentResolver.openOutputStream(uri).use { outputStream ->
                    outputStream?.write(jsonText.toByteArray())
                }
                withContext(Dispatchers.Main) {
                    onResult(true, "Secrets exported (encrypted).")
                }
            } catch (e: Exception) {
                withContext(Dispatchers.Main) {
                    onResult(false, "Export failed: ${e.message?.take(200)}")
                }
            }
        }
    }

    /**
     * Imports a snapshot and applies an encrypted `secrets` section using the user-supplied
     * passphrase (SEC-16). Validates the passphrase before importing anything, so a wrong
     * passphrase fails closed and no data is partially applied.
     */
    fun importDataFromUriWithSecrets(
        context: Context,
        uri: Uri,
        passphrase: String,
        onResult: (Boolean, String) -> Unit,
    ) {
        viewModelScope.launch {
            val text = withContext(Dispatchers.IO) {
                runCatching {
                    context.contentResolver.openInputStream(uri)?.bufferedReader()?.use { it.readText() }
                }.getOrNull()
            }
            if (text.isNullOrBlank()) {
                withContext(Dispatchers.Main) { onResult(false, "Could not read that file.") }
                return@launch
            }
            val root = try {
                JSONObject(text)
            } catch (e: Exception) {
                withContext(Dispatchers.Main) { onResult(false, "That file is not a Dunder backup.") }
                return@launch
            }
            val secrets = root.optJSONObject("secrets")
            if (secrets != null && secrets.length() > 0) {
                // Validate the passphrase up front by decrypting the first entry. Fails closed:
                // a wrong passphrase means we never touch the database.
                val names = secrets.keys().asSequence().toList()
                val first = names.firstOrNull { secrets.optString(it).isNotBlank() }
                if (first != null) {
                    if (SecretVault.decrypt(secrets.optString(first), passphrase) == null) {
                        withContext(Dispatchers.Main) {
                            onResult(false, "Wrong passphrase — nothing was imported.")
                        }
                        return@launch
                    }
                }
            }
            val success = repo.importSnapshot(root)
            if (secrets != null && secrets.length() > 0) {
                val secretOk = repo.restoreSecrets(secrets, passphrase)
                if (!secretOk) {
                    withContext(Dispatchers.Main) {
                        onResult(false, "Secrets could not be applied (corrupt section).")
                    }
                    return@launch
                }
            }
            withContext(Dispatchers.Main) {
                onResult(
                    success,
                    if (success) "Backup restored." else "Import failed: the file could not be applied.",
                )
            }
        }
    }

    fun autoBackups(): List<File> = repo.autoBackups()

    fun writeAutoBackupNow(onResult: (File?) -> Unit) {
        viewModelScope.launch {
            val file = repo.writeAutoBackup(force = true)
            withContext(Dispatchers.Main) { onResult(file) }
        }
    }

    fun restoreAutoBackup(file: File, onResult: (Boolean, String) -> Unit) {
        viewModelScope.launch {
            val ok = repo.restoreAutoBackup(file)
            withContext(Dispatchers.Main) {
                onResult(ok, if (ok) "Restored ${file.name}" else "Could not restore that backup.")
            }
        }
    }

    fun createProject(name: String, description: String, leadAgentId: String) {
        viewModelScope.launch { repo.createProject(name, description, leadAgentId) }
    }

    fun setProjectLead(projectId: String, agentId: String) {
        viewModelScope.launch {
            val project = repo.project(projectId) ?: return@launch
            repo.updateProject(project.copy(leadAgentId = agentId))
        }
    }

    fun renameProject(projectId: String, name: String, description: String) {
        viewModelScope.launch {
            val project = repo.project(projectId) ?: return@launch
            repo.updateProject(project.copy(name = name, description = description))
        }
    }

    fun deleteProject(projectId: String) {
        viewModelScope.launch { repo.deleteProject(projectId) }
    }

    fun ensureProject() {
        viewModelScope.launch { repo.ensureProject() }
    }

    fun tasksForProject(projectId: String) = repo.tasksForProject(projectId)

    fun buildsForProject(projectId: String) = repo.buildsForProject(projectId)

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
            val uri = Share.saveToDownloads(context, file, "agoria-${build.name}-${build.id.take(8)}.zip", "application/zip")
            withContext(Dispatchers.Main) {
                onResult(uri)
            }
        }
    }

    fun checkForUpdates(onResult: (com.agoria.app.util.UpdateManager.CheckResult) -> Unit) {
        app.updateManager.checkForUpdates { result ->
            when (result) {
                is com.agoria.app.util.UpdateManager.CheckResult.Available ->
                    onResult(result)
                else -> onResult(result)
            }
        }
    }

    fun downloadAndInstallUpdate(
        release: com.agoria.app.util.UpdateManager.ReleaseInfo,
        onProgress: (Int) -> Unit,
        onComplete: (com.agoria.app.util.UpdateManager.UpdateResult) -> Unit,
    ) {
        app.updateManager.downloadAndInstall(release, onProgress, onComplete)
    }

    suspend fun executeConnector(
        connectorId: String,
        agentId: String?,
        action: String,
        args: org.json.JSONObject,
    ): com.agoria.app.core.ConnectorResult {
        return app.runtime.connectorExecutor.execute(connectorId, agentId, action, args)
    }

    // Skills
    val skills = repo.skills.stateIn(
        viewModelScope,
        SharingStarted.WhileSubscribed(5_000),
        emptyList(),
    )

    val enabledSkills = repo.enabledSkills.stateIn(
        viewModelScope,
        SharingStarted.WhileSubscribed(5_000),
        emptyList(),
    )

    suspend fun agentSkillConfigs(agentId: String): List<AgentSkillEntity> {
        return repo.agentSkillConfigs(agentId)
    }

    fun syncSkillCatalog() {
        viewModelScope.launch { repo.syncSkillCatalog() }
    }

    fun installSkill(agentId: String, skillId: String) {
        viewModelScope.launch { repo.installSkill(agentId, skillId) }
    }

    fun uninstallSkill(agentId: String, skillId: String) {
        viewModelScope.launch { repo.uninstallSkill(agentId, skillId) }
    }

    fun updateSkillConfig(agentId: String, skillId: String, config: JSONObject) {
        viewModelScope.launch { repo.updateSkillConfig(agentId, skillId, config) }
    }

    suspend fun getSkillConfig(agentId: String, skillId: String): JSONObject? {
        return repo.getSkillConfig(agentId, skillId)
    }

    suspend fun executeSkill(
        agentId: String,
        skillId: String,
        actionId: String,
        args: JSONObject,
    ): SkillExecutor.SkillResult {
        return app.runtime.skillExecutor.execute(agentId, skillId, actionId, args)
    }

}