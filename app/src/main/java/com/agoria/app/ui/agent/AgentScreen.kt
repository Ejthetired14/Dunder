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
import androidx.compose.material.icons.filled.ExpandMore
import androidx.compose.material.icons.filled.Pause
import androidx.compose.material.icons.filled.PlayArrow
import androidx.compose.material.icons.filled.Download
import androidx.compose.material.icons.filled.Share
import androidx.compose.material3.AlertDialog
import androidx.compose.material3.Button
import androidx.compose.material3.Card
import androidx.compose.material3.CardDefaults
import androidx.compose.material3.ExperimentalMaterial3Api
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.CircularProgressIndicator
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
import com.agoria.app.core.BuildRunner
import com.agoria.app.data.AgentEntity
import com.agoria.app.data.BuildEntity
import com.agoria.app.data.DiaryEntryEntity
import com.agoria.app.data.MemoryEntity
import com.agoria.app.data.MessageEntity
import com.agoria.app.data.TaskEntity
import com.agoria.app.ui.AgoriaViewModel
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
                onSaveBuild = { build ->
                    val ok = Share.saveToDownloads(context, File(build.zipPath), File(build.zipPath).name)
                    android.widget.Toast.makeText(
                        context,
                        if (ok) "Saved to Downloads" else "Failed to save",
                        android.widget.Toast.LENGTH_SHORT,
                    ).show()
                },
            )
        }
    }
}

@Composable
private fun AgentContent(
    viewModel: AgoriaViewModel,
    agent: AgentEntity,
    onShareBuild: (BuildEntity) -> Unit,
    onSaveBuild: (BuildEntity) -> Unit,
) {
    val state = viewModel.agentScreenState(agent.id).collectAsStateWithLifecycle(
        AgoriaViewModel.AgentScreenState(emptyList(), emptyList(), emptyList(), emptyList(), emptyList())
    ).value
    val messages = state.messages
    val diary = state.diary
    val tasks = state.tasks
    val memories = state.memories
    val buildProgress by viewModel.buildProgress.collectAsStateWithLifecycle(null)
    val buildsPager = viewModel.getBuildsPager(agent.id)

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
                agent = agent,
                messages = messages,
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
            1 -> DiaryTab(diary = diary)
            2 -> TasksTab(tasks = tasks)
            3 -> MemoryTab(memories = memories, onAdd = { showMemory = true })
            else -> BuildsTab(
    builds = buildsPager.builds,
    onShare = onShareBuild,
    onSave = onSaveBuild,
    buildProgress = buildProgress,
    hasMore = buildsPager.hasMore,
    isLoading = buildsPager.isLoading,
    onLoadMore = { viewModel.loadMoreBuilds(agent.id) },
)
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
    agent: AgentEntity,
    messages: List<MessageEntity>,
    input: String,
    onInput: (String) -> Unit,
    onSend: () -> Unit,
    listState: androidx.compose.foundation.lazy.LazyListState,
) {
    Column(Modifier.fillMaxSize()) {
        LazyColumn(
            modifier = Modifier.weight(1f),
            state = listState,
            contentPadding = PaddingValues(16.dp),
            verticalArrangement = Arrangement.spacedBy(8.dp),
        ) {
            if (messages.isEmpty()) {
                item {
                    EmptyState(
                        title = "Say hello",
                        body = "Ask ${agent.name} what they are thinking, or tell them what you want the office to build. Actions that touch the outside world will ask for approval first.",
                    )
                }
            } else {
                items(messages, key = { it.id }) { message ->
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
private fun DiaryTab(diary: List<DiaryEntryEntity>) {
    if (diary.isEmpty()) {
        EmptyState(
            title = "No diary yet",
            body = "Start autonomy or send a message. Every cycle produces a private thought here.",
        )
    } else {
        LazyColumn(
            modifier = Modifier.fillMaxSize(),
            contentPadding = PaddingValues(16.dp),
            verticalArrangement = Arrangement.spacedBy(10.dp),
        ) {
            items(diary, key = { it.id }) { entry ->
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
            modifier = Modifier.fillMaxSize(),
            contentPadding = PaddingValues(16.dp),
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
            LazyColumn(
                modifier = Modifier
                    .weight(1f)
                    .padding(16.dp),
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
    builds: List<BuildEntity>,
    onShare: (BuildEntity) -> Unit,
    onSave: (BuildEntity) -> Unit,
    buildProgress: com.agoria.app.core.BuildRunner.BuildProgress?,
    hasMore: Boolean,
    isLoading: Boolean,
    onLoadMore: () -> Unit,
) {
    Column(Modifier.fillMaxSize()) {
        if (buildProgress != null) {
            Surface(
                modifier = Modifier
                    .fillMaxWidth()
                    .padding(16.dp),
                shape = MaterialTheme.shapes.medium,
                color = MaterialTheme.colorScheme.primaryContainer,
            ) {
                Column(Modifier.padding(16.dp)) {
                    Row(verticalAlignment = Alignment.CenterVertically) {
                        androidx.compose.material3.CircularProgressIndicator(
                            modifier = Modifier.size(20.dp),
                            color = MaterialTheme.colorScheme.onPrimaryContainer,
                        )
                        Spacer(Modifier.width(12.dp))
                        Text(
                            text = when (buildProgress) {
                                is com.agoria.app.core.BuildRunner.BuildProgress.Generating -> buildProgress.stage
                                is com.agoria.app.core.BuildRunner.BuildProgress.WritingFiles -> "Writing files... ${buildProgress.written}/${buildProgress.total}"
                                is com.agoria.app.core.BuildRunner.BuildProgress.Zipping -> "Creating ZIP... ${(buildProgress.progress * 100).roundToInt()}%"
                                com.agoria.app.core.BuildRunner.BuildProgress.Done -> "Build complete!"
                                is com.agoria.app.core.BuildRunner.BuildProgress.Error -> "Error: ${buildProgress.message}"
                            },
                            style = MaterialTheme.typography.bodyMedium,
                            color = MaterialTheme.colorScheme.onPrimaryContainer,
                        )
                    }
                    if (buildProgress is com.agoria.app.core.BuildRunner.BuildProgress.WritingFiles) {
                        androidx.compose.material3.LinearProgressIndicator(
                            modifier = Modifier.fillMaxWidth().padding(top = 8.dp),
                            progress = buildProgress.total.toFloat().takeIf { it > 0 }?.let { buildProgress.written / it } ?: 0f,
                            color = MaterialTheme.colorScheme.onPrimaryContainer,
                        )
                    }
                }
            }
        }
        if (builds.isEmpty()) {
            EmptyState(
                title = "No builds yet",
                body = "When an agent proposes a build and you approve it, the generated project appears here as a downloadable ZIP.",
            )
        } else {
            LazyColumn(
                modifier = Modifier
                    .weight(1f)
                    .padding(16.dp),
                verticalArrangement = Arrangement.spacedBy(10.dp),
            ) {
                items(builds, key = { it.id }) { build ->
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
                                OutlinedButton(onClick = { onSave(build) }) {
                                    Icon(Icons.Default.Download, contentDescription = null)
                                    Spacer(Modifier.width(6.dp))
                                    Text("Save to Device")
                                }
                            }
                        }
                    }
                }
                if (hasMore) {
                    item {
                        Column(
                            modifier = Modifier
                                .fillMaxWidth()
                                .padding(16.dp),
                            horizontalAlignment = Alignment.CenterHorizontally,
                        ) {
                            if (isLoading) {
                                CircularProgressIndicator(
                                    modifier = Modifier.size(24.dp),
                                    color = MaterialTheme.colorScheme.primary,
                                )
                            } else {
                                OutlinedButton(
                                    onClick = onLoadMore,
                                    modifier = Modifier.padding(top = 8.dp, bottom = 16.dp),
                                ) {
                                    Icon(Icons.Default.ExpandMore, contentDescription = null)
                                    Spacer(Modifier.width(6.dp))
                                    Text("Load more")
                                }
                            }
                        }
                    }
                }
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