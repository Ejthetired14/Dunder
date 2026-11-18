package com.agoria.app.ui.agent

import androidx.compose.foundation.background
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.PaddingValues
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.heightIn
import androidx.compose.foundation.layout.imePadding
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.items
import androidx.compose.foundation.lazy.rememberLazyListState
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.foundation.verticalScroll
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.automirrored.filled.ArrowBack
import androidx.compose.material.icons.automirrored.filled.Send
import androidx.compose.material.icons.filled.Add
import androidx.compose.material.icons.filled.Delete
import androidx.compose.material.icons.filled.Edit
import androidx.compose.material.icons.filled.Pause
import androidx.compose.material.icons.filled.PlayArrow
import androidx.compose.material.icons.filled.Share
import androidx.compose.material3.AlertDialog
import androidx.compose.material3.Button
import androidx.compose.material3.Card
import androidx.compose.material3.CardDefaults
import androidx.compose.material3.ExperimentalMaterial3Api
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.LinearProgressIndicator
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedTextField
import androidx.compose.material3.Scaffold
import androidx.compose.material3.Surface
import androidx.compose.material3.Switch
import androidx.compose.material3.Tab
import androidx.compose.material3.TabRow
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.material3.TopAppBar
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.text.font.FontFamily
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import com.agoria.app.core.BuildProgress
import com.agoria.app.data.AgentEntity
import com.agoria.app.data.BuildEntity
import com.agoria.app.data.DiaryEntryEntity
import com.agoria.app.data.MemoryEntity
import com.agoria.app.data.MessageEntity
import com.agoria.app.data.TaskEntity
import com.agoria.app.ui.AgoriaViewModel
import com.agoria.app.ui.AgentScreenState
import com.agoria.app.ui.components.AgentAvatar
import com.agoria.app.ui.components.EmptyState
import com.agoria.app.ui.components.Pill
import com.agoria.app.ui.components.StatusPill
import com.agoria.app.util.Share
import com.agoria.app.util.Time
import java.io.File

@OptIn(ExperimentalMaterial3Api::class)
@Composable
fun AgentScreen(
    viewModel: AgoriaViewModel,
    agentId: String,
    onBack: () -> Unit,
) {
    val context = LocalContext.current
    val agents by viewModel.agents.collectAsStateWithLifecycle()
    val agent = agents.firstOrNull { it.id == agentId }

    Scaffold(
        topBar = {
            TopAppBar(
                title = { Text(agent?.name ?: "Agent") },
                navigationIcon = {
                    IconButton(onClick = onBack) {
                        Icon(Icons.AutoMirrored.Filled.ArrowBack, contentDescription = "Back")
                    }
                },
                actions = {
                    if (agent != null) {
                        IconButton(
                            onClick = {
                                if (agent.autonomy) viewModel.pauseAgent(agent.id) else viewModel.resumeAgent(agent.id)
                            },
                        ) {
                            Icon(
                                imageVector = if (agent.autonomy) Icons.Default.Pause else Icons.Default.PlayArrow,
                                contentDescription = if (agent.autonomy) "Pause" else "Resume",
                            )
                        }
                    }
                },
            )
        },
    ) { padding ->
        if (agent == null) {
            Box(Modifier.fillMaxSize().padding(padding)) {
                EmptyState(
                    title = "Agent not found",
                    body = "This agent may have been deleted.",
                )
            }
        } else {
            AgentContent(
                viewModel = viewModel,
                agent = agent,
                onShareBuild = { build -> Share.shareZip(context, File(build.zipPath)) },
            )
        }
    }
}

@Composable
private fun AgentContent(
    viewModel: AgoriaViewModel,
    agent: AgentEntity,
    onShareBuild: (BuildEntity) -> Unit,
) {
    val state = viewModel.agentScreenState(agent.id).collectAsStateWithLifecycle(
        AgentScreenState(null, emptyList(), emptyList(), emptyList(), emptyList(), emptyList())
    ).value

    val messages = state.messages
    val diary = state.diary
    val tasks = state.tasks
    val builds = state.builds
    val memories = state.memories

    var tab by remember { mutableStateOf(0) }
    var input by remember { mutableStateOf("") }
    var showEdit by remember { mutableStateOf(false) }
    var showMemory by remember { mutableStateOf(false) }
    var showDelete by remember { mutableStateOf(false) }
    val listState = rememberLazyListState()

    LaunchedEffect(messages.size) {
        if (messages.isNotEmpty()) {
            listState.scrollToItem(messages.size - 1)
        }
    }

    Column(
        modifier = Modifier
            .fillMaxSize()
            .imePadding(),
    ) {
        AgentHeader(
            agent = agent,
            onToggle = {
                if (agent.autonomy) viewModel.pauseAgent(agent.id) else viewModel.resumeAgent(agent.id)
            },
            onEdit = { showEdit = true },
        )

        TabRow(selectedTabIndex = tab) {
            Tab(selected = tab == 0, onClick = { tab = 0 }, text = { Text("Chat") })
            Tab(selected = tab == 1, onClick = { tab = 1 }, text = { Text("Diary") })
            Tab(selected = tab == 2, onClick = { tab = 2 }, text = { Text("Tasks") })
            Tab(selected = tab == 3, onClick = { tab = 3 }, text = { Text("Memory") })
            Tab(selected = tab == 4, onClick = { tab = 4 }, text = { Text("Builds") })
        }

        when (tab) {
            0 -> ChatTab(
                viewModel = viewModel,
                agent = agent,
                input = input,
                onInput = { input = it },
                onSend = {
                    if (input.isNotBlank()) {
                        viewModel.sendMessage(agent.id, input.trim())
                        input = ""
                    }
                },
                listState = listState,
            )
            1 -> DiaryTab(viewModel = viewModel, agentId = agent.id, diary = diary)
            2 -> TasksTab(tasks = tasks)
            3 -> MemoryTab(memories = memories, onAdd = { showMemory = true })
            else -> BuildsTab(viewModel = viewModel, agentId = agent.id, onShare = onShareBuild)
        }
    }

    if (showEdit) {
        EditAgentDialog(
            agent = agent,
            onDismiss = { showEdit = false },
            onSave = { updated ->
                viewModel.updateAgent(updated)
                showEdit = false
            },
            onDelete = {
                viewModel.deleteAgent(agent.id)
                showEdit = false
            },
        )
    }

    if (showMemory) {
        AddMemoryDialog(
            onDismiss = { showMemory = false },
            onAdd = { kind, content, importance ->
                viewModel.addMemory(agent.id, kind, content, importance)
                showMemory = false
            },
        )
    }

    if (showDelete) {
        AlertDialog(
            onDismissRequest = { showDelete = false },
            title = { Text("Delete this agent?") },
            text = { Text("This removes the agent, its diary, messages, tasks, and builds from this device.") },
            confirmButton = {
                TextButton(onClick = {
                    viewModel.deleteAgent(agent.id)
                    showDelete = false
                }) { Text("Delete") }
            },
            dismissButton = {
                TextButton(onClick = { showDelete = false }) { Text("Cancel") }
            },
        )
    }
}

@Composable
private fun AgentHeader(
    agent: AgentEntity,
    onToggle: () -> Unit,
    onEdit: () -> Unit,
) {
    Surface(
        modifier = Modifier.fillMaxWidth(),
        color = MaterialTheme.colorScheme.surfaceVariant,
    ) {
        Column(Modifier.padding(16.dp)) {
            Row(verticalAlignment = Alignment.CenterVertically) {
                AgentAvatar(agent, 54.dp)
                Spacer(Modifier.width(12.dp))
                Column(Modifier.weight(1f)) {
                    Row(verticalAlignment = Alignment.CenterVertically) {
                        Text(agent.name, style = MaterialTheme.typography.titleLarge)
                        Spacer(Modifier.width(8.dp))
                        StatusPill(agent.status)
                    }
                    Text(
                        text = agent.role,
                        style = MaterialTheme.typography.labelMedium,
                        color = MaterialTheme.colorScheme.onSurfaceVariant,
                    )
                }
                TextButton(onClick = onEdit) {
                    Icon(Icons.Default.Edit, contentDescription = "Edit")
                    Spacer(Modifier.width(6.dp))
                    Text("Edit")
                }
            }
            Spacer(Modifier.height(8.dp))
            Text(
                text = "Goal: ${agent.goal}",
                style = MaterialTheme.typography.bodySmall,
                color = MaterialTheme.colorScheme.onSurfaceVariant,
            )
            Spacer(Modifier.height(6.dp))
            Row(verticalAlignment = Alignment.CenterVertically) {
                Switch(checked = agent.autonomy, onCheckedChange = { onToggle() })
                Spacer(Modifier.width(8.dp))
                Text(
                    text = if (agent.autonomy) "Autonomy on" else "Autonomy paused",
                    style = MaterialTheme.typography.labelMedium,
                )
            }
        }
    }
}

@Composable
private fun ChatTab(
    viewModel: AgoriaViewModel,
    agent: AgentEntity,
    input: String,
    onInput: (String) -> Unit,
    onSend: () -> Unit,
    listState: androidx.compose.foundation.lazy.LazyListState,
) {
    val agentId = agent.id
    val pagedMessages = viewModel.pagedMessages(agentId)
    val messagesPagination = viewModel.messagesPagination(agentId)

    // Load initial data (latest messages)
    LaunchedEffect(Unit) {
        viewModel.loadPagedMessages(agentId, refresh = true)
    }

    // Load more when scrolled to top
    LaunchedEffect(listState.firstVisibleItemIndex) {
        if (listState.firstVisibleItemIndex == 0 && messagesPagination.hasMore && !messagesPagination.isLoading) {
            viewModel.loadPagedMessages(agentId)
        }
    }

    Column(Modifier.fillMaxSize()) {
        LazyColumn(
            modifier = Modifier.weight(1f),
            state = listState,
            contentPadding = PaddingValues(16.dp),
            verticalArrangement = Arrangement.spacedBy(8.dp),
            reverseLayout = true, // Show latest messages at bottom
        ) {
            // Load more indicator at top (older messages)
            if (messagesPagination.hasMore) {
                item {
                    if (messagesPagination.isLoading) {
                        Row(
                            modifier = Modifier.fillMaxWidth(),
                            horizontalArrangement = Arrangement.Center,
                        ) {
                            androidx.compose.material3.CircularProgressIndicator(
                                modifier = Modifier.padding(16.dp)
                            )
                        }
                    } else {
                        TextButton(
                            onClick = { viewModel.loadPagedMessages(agentId) },
                            modifier = Modifier.fillMaxWidth().padding(16.dp),
                        ) {
                            Text("Load older messages")
                        }
                    }
                }
            }

            if (pagedMessages.isEmpty() && !messagesPagination.isLoading) {
                item {
                    EmptyState(
                        title = "Say hello",
                        body = "Ask ${agent.name} what they are thinking, or tell them what you want the office to build. Actions that touch the outside world will ask for approval first.",
                    )
                }
            } else {
                items(pagedMessages.reversed(), key = { it.id }) { message ->
                    MessageRow(message = message, agent = agent)
                }
            }
            if (agent.status == "thinking") {
                item {
                    Surface(
                        shape = RoundedCornerShape(16.dp),
                        color = MaterialTheme.colorScheme.surfaceVariant,
                    ) {
                        Text(
                            text = "${agent.name} is thinking...",
                            style = MaterialTheme.typography.bodySmall,
                            color = MaterialTheme.colorScheme.onSurfaceVariant,
                            modifier = Modifier.padding(12.dp),
                        )
                    }
                }
            }
        }

        Surface(
            modifier = Modifier.fillMaxWidth(),
            color = MaterialTheme.colorScheme.surface,
            shadowElevation = 4.dp,
        ) {
            Row(
                modifier = Modifier
                    .fillMaxWidth()
                    .padding(12.dp),
                verticalAlignment = Alignment.Bottom,
            ) {
                OutlinedTextField(
                    value = input,
                    onValueChange = onInput,
                    modifier = Modifier.weight(1f),
                    placeholder = { Text("Message ${agent.name}...") },
                    maxLines = 4,
                )
                Spacer(Modifier.width(8.dp))
                IconButton(
                    onClick = onSend,
                    enabled = input.isNotBlank(),
                ) {
                    Icon(Icons.AutoMirrored.Filled.Send, contentDescription = "Send")
                }
            }
        }
    }
}

@Composable
private fun MessageRow(message: MessageEntity, agent: AgentEntity) {
    when (message.role) {
        "user" -> {
            Row(
                modifier = Modifier.fillMaxWidth(),
                horizontalArrangement = Arrangement.End,
            ) {
                Surface(
                    shape = RoundedCornerShape(18.dp, 18.dp, 4.dp, 18.dp),
                    color = MaterialTheme.colorScheme.primary,
                ) {
                    Text(
                        text = message.content,
                        style = MaterialTheme.typography.bodyMedium,
                        color = MaterialTheme.colorScheme.onPrimary,
                        modifier = Modifier.padding(horizontal = 14.dp, vertical = 10.dp),
                    )
                }
            }
        }
        "agent" -> {
            Row(
                modifier = Modifier.fillMaxWidth(),
                verticalAlignment = Alignment.Top,
            ) {
                AgentAvatar(agent, 30.dp, showStatus = false)
                Spacer(Modifier.width(8.dp))
                Surface(
                    shape = RoundedCornerShape(18.dp, 18.dp, 18.dp, 4.dp),
                    color = MaterialTheme.colorScheme.surfaceVariant,
                    modifier = Modifier.weight(1f, fill = false),
                ) {
                    Column(Modifier.padding(horizontal = 14.dp, vertical = 10.dp)) {
                        Text(
                            text = message.content,
                            style = MaterialTheme.typography.bodyMedium,
                        )
                        Text(
                            text = Time.clock(message.createdAt),
                            style = MaterialTheme.typography.labelSmall,
                            color = MaterialTheme.colorScheme.outline,
                        )
                    }
                }
            }
        }
        "system" -> {
            Box(Modifier.fillMaxWidth(), contentAlignment = Alignment.Center) {
                Text(
                    text = message.content,
                    style = MaterialTheme.typography.labelSmall,
                    color = MaterialTheme.colorScheme.onSurfaceVariant,
                    modifier = Modifier.padding(horizontal = 24.dp, vertical = 4.dp),
                )
            }
        }
        "tool" -> {
            Surface(
                modifier = Modifier.fillMaxWidth(),
                shape = RoundedCornerShape(12.dp),
                color = MaterialTheme.colorScheme.secondaryContainer,
            ) {
                Column(Modifier.padding(12.dp)) {
                    Text(
                        text = "CONNECTOR RESULT",
                        style = MaterialTheme.typography.labelSmall,
                        color = MaterialTheme.colorScheme.onSecondaryContainer,
                    )
                    Text(
                        text = message.content,
                        style = MaterialTheme.typography.bodySmall,
                        fontFamily = FontFamily.Monospace,
                        color = MaterialTheme.colorScheme.onSecondaryContainer,
                    )
                }
            }
        }
        else -> {
            Text(
                text = message.content,
                style = MaterialTheme.typography.bodySmall,
                color = MaterialTheme.colorScheme.onSurfaceVariant,
            )
        }
    }
}

@Composable
private fun DiaryTab(viewModel: AgoriaViewModel, agentId: String, diary: List<DiaryEntryEntity>) {
    val pagedDiary by viewModel.pagedDiary.collectAsStateWithLifecycle()
    val diaryPagination by viewModel.diaryPagination.collectAsStateWithLifecycle()

    // Load initial data
    LaunchedEffect(Unit) {
        viewModel.loadPagedDiary(agentId, refresh = true)
    }

    if (pagedDiary.isEmpty() && !diaryPagination.isLoading) {
        EmptyState(
            title = "No diary yet",
            body = "Start autonomy or send a message. Every cycle produces a private thought here.",
        )
    } else {
        LazyColumn(
            modifier = Modifier
                .fillMaxSize()
                .padding(16.dp),
            contentPadding = PaddingValues(vertical = 8.dp),
            verticalArrangement = Arrangement.spacedBy(10.dp),
        ) {
            items(pagedDiary, key = { it.id }) { entry ->
                Surface(
                    shape = MaterialTheme.shapes.medium,
                    color = MaterialTheme.colorScheme.surfaceVariant,
                    modifier = Modifier.fillMaxWidth(),
                ) {
                    Column(Modifier.padding(14.dp)) {
                        Row(verticalAlignment = Alignment.CenterVertically) {
                            Pill(text = entry.kind)
                            Spacer(Modifier.weight(1f))
                            Text(
                                text = Time.dateTime(entry.createdAt),
                                style = MaterialTheme.typography.labelSmall,
                                color = MaterialTheme.colorScheme.onSurfaceVariant,
                            )
                        }
                        Spacer(Modifier.height(6.dp))
                        Text(entry.content, style = MaterialTheme.typography.bodyMedium)
                        Spacer(Modifier.height(4.dp))
                        Text(
                            text = "★".repeat(entry.importance.coerceIn(1, 5)),
                            style = MaterialTheme.typography.labelSmall,
                            color = MaterialTheme.colorScheme.primary,
                        )
                    }
                }
            }

            // Load more indicator
            if (diaryPagination.hasMore) {
                item {
                    if (diaryPagination.isLoading) {
                        Row(
                            modifier = Modifier.fillMaxWidth(),
                            horizontalArrangement = Arrangement.Center,
                        ) {
                            androidx.compose.material3.CircularProgressIndicator(
                                modifier = Modifier.padding(16.dp)
                            )
                        }
                    } else {
                        TextButton(
                            onClick = { viewModel.loadPagedDiary(agentId) },
                            modifier = Modifier.fillMaxWidth().padding(16.dp),
                        ) {
                            Text("Load more")
                        }
                    }
                }
            }
        }
    }
}

@Composable
private fun TasksTab(tasks: List<TaskEntity>) {
    if (tasks.isEmpty()) {
        EmptyState(
            title = "No tasks yet",
            body = "Agents can create tasks when they want to track a plan. They will show up here.",
        )
    } else {
        LazyColumn(
            modifier = Modifier
                .fillMaxSize()
                .padding(16.dp),
            contentPadding = PaddingValues(vertical = 8.dp),
            verticalArrangement = Arrangement.spacedBy(10.dp),
        ) {
            items(tasks, key = { it.id }) { task ->
                Surface(
                    shape = MaterialTheme.shapes.medium,
                    color = MaterialTheme.colorScheme.surfaceVariant,
                    modifier = Modifier.fillMaxWidth(),
                ) {
                    Column(Modifier.padding(14.dp)) {
                        Row(verticalAlignment = Alignment.CenterVertically) {
                            Text(task.title, style = MaterialTheme.typography.titleMedium, modifier = Modifier.weight(1f))
                            Pill(text = task.status)
                        }
                        if (task.description.isNotBlank()) {
                            Spacer(Modifier.height(4.dp))
                            Text(
                                text = task.description,
                                style = MaterialTheme.typography.bodySmall,
                                color = MaterialTheme.colorScheme.onSurfaceVariant,
                                maxLines = 4,
                                overflow = TextOverflow.Ellipsis,
                            )
                        }
                        Spacer(Modifier.height(8.dp))
                        LinearProgressIndicator(
                            progress = { (task.progress.coerceIn(0, 100)) / 100f },
                            modifier = Modifier.fillMaxWidth(),
                        )
                        Text(
                            text = "${task.progress}% · updated ${Time.relativeTime(task.updatedAt)}",
                            style = MaterialTheme.typography.labelSmall,
                            color = MaterialTheme.colorScheme.onSurfaceVariant,
                        )
                    }
                }
            }
        }
    }
}

@Composable
private fun MemoryTab(memories: List<MemoryEntity>, onAdd: () -> Unit) {
    Column(Modifier.fillMaxSize()) {
        if (memories.isEmpty()) {
            EmptyState(
                title = "No long-term memory",
                body = "Agents store facts, values, and plans here. Add one yourself if you want them to remember something.",
            )
        } else {
            Column(
                modifier = Modifier.fillMaxSize(),
                verticalArrangement = Arrangement.spacedBy(8.dp),
            ) {
                LazyColumn(
                    modifier = Modifier
                        .weight(1f)
                        .padding(16.dp),
                    contentPadding = PaddingValues(vertical = 8.dp),
                    verticalArrangement = Arrangement.spacedBy(8.dp),
                ) {
                    items(memories, key = { it.id }) { memory ->
                        Surface(
                            shape = MaterialTheme.shapes.small,
                            color = MaterialTheme.colorScheme.surfaceVariant,
                            modifier = Modifier.fillMaxWidth(),
                        ) {
                            Column(Modifier.padding(12.dp)) {
                                Row(verticalAlignment = Alignment.CenterVertically) {
                                    Pill(text = memory.kind)
                                    Spacer(Modifier.weight(1f))
                                    Text(
                                        text = Time.relativeTime(memory.createdAt),
                                        style = MaterialTheme.typography.labelSmall,
                                        color = MaterialTheme.colorScheme.onSurfaceVariant,
                                    )
                                }
                                Spacer(Modifier.height(4.dp))
                                Text(memory.content, style = MaterialTheme.typography.bodyMedium)
                            }
                        }
                    }
                }
            }
        }
        Surface(color = MaterialTheme.colorScheme.surface, shadowElevation = 4.dp) {
            Row(
                modifier = Modifier
                    .fillMaxWidth()
                    .padding(12.dp),
                horizontalArrangement = Arrangement.End,
            ) {
                Button(onClick = onAdd) {
                    Icon(Icons.Default.Add, contentDescription = null)
                    Spacer(Modifier.width(6.dp))
                    Text("Add memory")
                }
            }
        }
    }
}

@Composable
private fun BuildsTab(
    viewModel: AgoriaViewModel,
    agentId: String,
    onShare: (BuildEntity) -> Unit,
) {
    val context = LocalContext.current
    val buildProgress by viewModel.buildProgress.collectAsStateWithLifecycle()
    val pagedBuilds by viewModel.pagedBuilds.collectAsStateWithLifecycle()
    val buildsPagination by viewModel.buildsPagination.collectAsStateWithLifecycle()
    var savingBuildId by remember { mutableStateOf<String?>(null) }

    // Load initial data
    LaunchedEffect(Unit) {
        viewModel.loadPagedBuilds(agentId, refresh = true)
    }

    Column(Modifier.fillMaxSize()) {
        // Progress banner
        buildProgress?.let { progress ->
            when (progress) {
                is BuildProgress.Started -> ProgressBanner(message = "Starting build...")
                is BuildProgress.GeneratingFiles -> ProgressBanner(message = "Generating project files...")
                is BuildProgress.WritingFiles -> ProgressBanner(
                    message = "Writing files (${progress.written}/${progress.total})",
                    progress = progress.written / progress.total.toFloat()
                )
                is BuildProgress.Zipping -> ProgressBanner(message = "Creating ZIP archive...")
                is BuildProgress.Completed -> ProgressBanner(
                    message = "Build complete: ${progress.build.name}",
                    progress = 1f,
                    color = MaterialTheme.colorScheme.secondary
                )
                is BuildProgress.Failed -> ProgressBanner(
                    message = "Build failed: ${progress.error}",
                    color = MaterialTheme.colorScheme.error
                )
            }
        }

        if (pagedBuilds.isEmpty() && !buildsPagination.isLoading) {
            EmptyState(
                title = "No builds yet",
                body = "When an agent proposes a build and you approve it, the generated project appears here as a downloadable ZIP.",
            )
        } else {
            LazyColumn(
                modifier = Modifier
                    .weight(1f)
                    .padding(16.dp),
                contentPadding = PaddingValues(vertical = 8.dp),
                verticalArrangement = Arrangement.spacedBy(10.dp),
            ) {
                items(pagedBuilds, key = { it.id }) { build ->
                    Surface(
                        shape = MaterialTheme.shapes.medium,
                        color = MaterialTheme.colorScheme.surfaceVariant,
                        modifier = Modifier.fillMaxWidth(),
                    ) {
                        Column(Modifier.padding(14.dp)) {
                            Row(verticalAlignment = Alignment.CenterVertically) {
                                Text(build.name, style = MaterialTheme.typography.titleMedium, modifier = Modifier.weight(1f))
                                Pill(text = "${build.fileCount} files")
                            }
                            Spacer(Modifier.height(4.dp))
                            Text(
                                text = build.description,
                                style = MaterialTheme.typography.bodySmall,
                                color = MaterialTheme.colorScheme.onSurfaceVariant,
                                maxLines = 3,
                                overflow = TextOverflow.Ellipsis,
                            )
                            Text(
                                text = "Built ${Time.dateTime(build.createdAt)}",
                                style = MaterialTheme.typography.labelSmall,
                                color = MaterialTheme.colorScheme.outline,
                            )
                            Spacer(Modifier.height(8.dp))
                            Row(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                                Button(onClick = { onShare(build) }) {
                                    Icon(Icons.Default.Share, contentDescription = null)
                                    Spacer(Modifier.width(6.dp))
                                    Text("Share ZIP")
                                }
                                Button(
                                    onClick = {
                                        savingBuildId = build.id
                                        viewModel.saveBuildToDownloads(context, build) { uri ->
                                            savingBuildId = null
                                            // Could show a snackbar here
                                        }
                                    },
                                    enabled = savingBuildId != build.id,
                                ) {
                                    if (savingBuildId == build.id) {
                                        androidx.compose.material3.CircularProgressIndicator(modifier = Modifier.size(16.dp))
                                        Spacer(Modifier.width(6.dp))
                                        Text("Saving...")
                                    } else {
                                        Icon(Icons.Default.Download, contentDescription = null)
                                        Spacer(Modifier.width(6.dp))
                                        Text("Save to Device")
                                    }
                                }
                            }
                        }
                    }
                }

                // Load more indicator
                if (buildsPagination.hasMore) {
                    item {
                        if (buildsPagination.isLoading) {
                            Row(
                                modifier = Modifier.fillMaxWidth(),
                                horizontalArrangement = Arrangement.Center,
                            ) {
                                androidx.compose.material3.CircularProgressIndicator(
                                    modifier = Modifier.padding(16.dp)
                                )
                            }
                        } else {
                            TextButton(
                                onClick = { viewModel.loadPagedBuilds(agentId) },
                                modifier = Modifier.fillMaxWidth().padding(16.dp),
                            ) {
                                Text("Load more")
                            }
                        }
                    }
                }
            }
        }
    }
}

@Composable
private fun ProgressBanner(
    message: String,
    progress: Float = -1f,
    color: androidx.compose.ui.graphics.Color = MaterialTheme.colorScheme.primary,
) {
    Surface(
        modifier = Modifier
            .fillMaxWidth()
            .padding(16.dp)
            .animateContentSize(),
        shape = MaterialTheme.shapes.medium,
        color = color.copy(alpha = 0.1f),
        border = androidx.compose.ui.graphics.drawscope.Stroke(1.dp, color.copy(alpha = 0.3f)),
    ) {
        Column(Modifier.padding(12.dp)) {
            Row(verticalAlignment = Alignment.CenterVertically) {
                if (progress >= 0f) {
                    androidx.compose.material3.CircularProgressIndicator(
                        progress = progress,
                        color = color,
                        modifier = Modifier.size(20.dp),
                    )
                    Spacer(Modifier.width(12.dp))
                } else {
                    androidx.compose.material3.CircularProgressIndicator(
                        color = color,
                        modifier = Modifier.size(20.dp),
                    )
                    Spacer(Modifier.width(12.dp))
                }
                Text(
                    text = message,
                    style = MaterialTheme.typography.bodyMedium,
                    color = color,
                    modifier = Modifier.weight(1f),
                )
            }
            if (progress >= 0f && progress < 1f) {
                Spacer(Modifier.height(8.dp))
                androidx.compose.material3.LinearProgressIndicator(
                    progress = progress,
                    color = color,
                    trackColor = color.copy(alpha = 0.2f),
                    modifier = Modifier.fillMaxWidth(),
                )
            }
        }
    }
}

@Composable
private fun EditAgentDialog(
    agent: AgentEntity,
    onDismiss: () -> Unit,
    onSave: (AgentEntity) -> Unit,
    onDelete: () -> Unit,
) {
    var name by remember { mutableStateOf(agent.name) }
    var role by remember { mutableStateOf(agent.role) }
    var persona by remember { mutableStateOf(agent.persona) }
    var goal by remember { mutableStateOf(agent.goal) }
    var confirmDelete by remember { mutableStateOf(false) }

    AlertDialog(
        onDismissRequest = onDismiss,
        title = { Text("Edit ${agent.name}") },
        text = {
            Column(
                modifier = Modifier
                    .heightIn(max = 460.dp)
                    .verticalScroll(rememberScrollState()),
                verticalArrangement = Arrangement.spacedBy(8.dp),
            ) {
                OutlinedTextField(
                    value = name,
                    onValueChange = { name = it },
                    label = { Text("Name") },
                    singleLine = true,
                    modifier = Modifier.fillMaxWidth(),
                )
                OutlinedTextField(
                    value = role,
                    onValueChange = { role = it },
                    label = { Text("Role") },
                    singleLine = true,
                    modifier = Modifier.fillMaxWidth(),
                )
                OutlinedTextField(
                    value = persona,
                    onValueChange = { persona = it },
                    label = { Text("Persona") },
                    minLines = 3,
                    modifier = Modifier.fillMaxWidth(),
                )
                OutlinedTextField(
                    value = goal,
                    onValueChange = { goal = it },
                    label = { Text("Goal") },
                    minLines = 2,
                    modifier = Modifier.fillMaxWidth(),
                )
                TextButton(onClick = { confirmDelete = !confirmDelete }) {
                    Icon(Icons.Default.Delete, contentDescription = null)
                    Spacer(Modifier.width(6.dp))
                    Text(if (confirmDelete) "Tap again to delete" else "Delete agent")
                }
                if (confirmDelete) {
                    Text(
                        text = "This permanently removes the agent and all of its data on this device.",
                        style = MaterialTheme.typography.labelSmall,
                        color = MaterialTheme.colorScheme.error,
                    )
                }
            }
        },
        confirmButton = {
            TextButton(
                onClick = {
                    if (confirmDelete) {
                        onDelete()
                    } else {
                        onSave(
                            agent.copy(
                                name = name.trim().ifBlank { agent.name },
                                role = role.trim().ifBlank { agent.role },
                                persona = persona.ifBlank { agent.persona },
                                goal = goal.ifBlank { agent.goal },
                            ),
                        )
                    }
                },
            ) { Text(if (confirmDelete) "Delete" else "Save") }
        },
        dismissButton = {
            TextButton(onClick = onDismiss) { Text("Cancel") }
        },
    )
}

@Composable
private fun AddMemoryDialog(
    onDismiss: () -> Unit,
    onAdd: (kind: String, content: String, importance: Int) -> Unit,
) {
    var kind by remember { mutableStateOf("fact") }
    var content by remember { mutableStateOf("") }
    var importance by remember { mutableStateOf(3f) }

    AlertDialog(
        onDismissRequest = onDismiss,
        title = { Text("Add a memory") },
        text = {
            Column(verticalArrangement = Arrangement.spacedBy(8.dp)) {
                OutlinedTextField(
                    value = kind,
                    onValueChange = { kind = it },
                    label = { Text("Kind (fact, value, plan...)") },
                    singleLine = true,
                )
                OutlinedTextField(
                    value = content,
                    onValueChange = { content = it },
                    label = { Text("What should the agent remember?") },
                    minLines = 3,
                )
                Text("Importance: ${importance.toInt()}")
                androidx.compose.material3.Slider(
                    value = importance,
                    onValueChange = { importance = it },
                    valueRange = 1f..5f,
                    steps = 3,
                )
            }
        },
        confirmButton = {
            TextButton(
                onClick = {
                    if (content.isNotBlank()) onAdd(kind, content, importance.toInt())
                },
            ) { Text("Add") }
        },
        dismissButton = { TextButton(onClick = onDismiss) { Text("Cancel") } },
    )
}