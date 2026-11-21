package com.agoria.app.ui.agent

import androidx.compose.foundation.background
import androidx.compose.foundation.gestures.isScrollInProgress
import androidx.compose.foundation.gestures.NestedScrollConnection
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
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.animation.animateContentSize
import androidx.compose.animation.animateFloatAsState
import androidx.compose.animation.core.spring
import androidx.compose.animation.enterExit.fadeIn
import androidx.compose.animation.enterExit.fadeOut
import androidx.compose.animation.enterExit.shrinkVertically
import androidx.compose.animation.enterExit.expandVertically
import androidx.compose.animation.AnimatedVisibility
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.items
import androidx.compose.foundation.lazy.rememberLazyListState
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.foundation.text.selection.SelectionContainer
import androidx.compose.foundation.verticalScroll
import androidx.compose.ui.geometry.Offset
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.automirrored.filled.ArrowBack
import androidx.compose.material.icons.automirrored.filled.Send
import androidx.compose.material.icons.filled.Add
import androidx.compose.material.icons.filled.ContentCopy
import androidx.compose.material.icons.filled.Delete
import androidx.compose.material.icons.filled.Download
import androidx.compose.material.icons.filled.Edit
import androidx.compose.material.icons.filled.MoreVert
import androidx.compose.material.icons.filled.Pause
import androidx.compose.material.icons.filled.PlayArrow
import androidx.compose.material.icons.filled.Share
import androidx.compose.material3.AlertDialog
import androidx.compose.material3.Button
import androidx.compose.material3.FilledTonalButton
import androidx.compose.material3.FilterChip
import androidx.compose.material3.Card
import androidx.compose.material3.CardDefaults
import androidx.compose.material3.CircularProgressIndicator
import androidx.compose.material3.DropdownMenu
import androidx.compose.material3.DropdownMenuItem
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
import androidx.compose.foundation.BorderStroke
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.platform.LocalClipboardManager
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.text.AnnotatedString
import androidx.compose.ui.text.font.FontFamily
import androidx.compose.ui.text.input.PasswordVisualTransformation
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import com.agoria.app.core.BuildProgress
import com.agoria.app.data.AgentEntity
import com.agoria.app.data.BuildEntity
import com.agoria.app.data.DiaryEntryEntity
import com.agoria.app.data.MemoryEntity
import com.agoria.app.data.MessageEntity
import com.agoria.app.data.TaskEntity
import com.agoria.app.ui.AgoriaViewModel
import com.agoria.app.ui.AgentScreenState
import com.agoria.app.ui.PaginationState
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

    if (agent == null) {
        Scaffold(
            topBar = {
                TopAppBar(
                    title = { Text("Agent") },
                    navigationIcon = {
                        IconButton(onClick = onBack) {
                            Icon(Icons.AutoMirrored.Filled.ArrowBack, contentDescription = "Back")
                        }
                    },
                )
            },
        ) { padding ->
            Box(Modifier.fillMaxSize().padding(padding)) {
                EmptyState(
                    title = "Agent not found",
                    body = "This agent may have been deleted.",
                )
            }
        }
    } else {
        AgentContent(
            viewModel = viewModel,
            agent = agent,
            onBack = onBack,
        )
    }
}

@Composable
private fun AgentContent(
    viewModel: AgoriaViewModel,
    agent: AgentEntity,
    onBack: () -> Unit,
) {
    val state = viewModel.agentScreenState(agent.id).collectAsStateWithLifecycle(
        AgentScreenState(null, emptyList(), emptyList(), emptyList(), emptyList(), emptyList())
    ).value

    val newestMessageId = state.messages.lastOrNull()?.id
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

    Column(
        modifier = Modifier
            .fillMaxSize()
            .imePadding()
            .nestedScroll(rememberHideHeaderNestedScrollConnection().first),
    ) {
        // Collapsing header that hides on scroll down, shows on scroll up
        val headerVisible = rememberHideHeaderNestedScrollConnection().second

        AnimatedVisibility(
            visible = headerVisible.value,
            enter = fadeIn(animationSpec = spring(dampingRatio = Spring.DampingRatioMediumBouncy)) + expandVertically(animationSpec = spring(dampingRatio = Spring.DampingRatioMediumBouncy)),
            exit = fadeOut(animationSpec = spring(dampingRatio = Spring.DampingRatioMediumBouncy)) + shrinkVertically(animationSpec = spring(dampingRatio = Spring.DampingRatioMediumBouncy)),
        ) {
            AgentHeader(
                agent = agent,
                onToggle = {
                    if (agent.autonomy) viewModel.pauseAgent(agent.id) else viewModel.resumeAgent(agent.id)
                },
                onEdit = { showEdit = true },
                onDelete = { showDelete = true },
            )
        }

        TabRow(selectedTabIndex = tab) {
            Tab(selected = tab == 0, onClick = { tab = 0 }, text = { Text("Chat") })
            Tab(selected = tab == 1, onClick = { tab = 1 }, text = { Text("Diary") })
            Tab(selected = tab == 2, onClick = { tab = 2 }, text = { Text("Tasks") })
            Tab(selected = tab == 3, onClick = { tab = 3 }, text = { Text("Memory") })
        }

        when (tab) {
            0 -> ChatTab(
                viewModel = viewModel,
                agent = agent,
                newestMessageId = newestMessageId,
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
            else -> MemoryTab(memories = memories, onAdd = { showMemory = true })
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
                onBack()
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
                    onBack()
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
    onDelete: () -> Unit,
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
                var menu by remember { mutableStateOf(false) }
                TextButton(onClick = onEdit) {
                    Icon(Icons.Default.Edit, contentDescription = "Edit")
                    Spacer(Modifier.width(6.dp))
                    Text("Edit")
                }
                Box {
                    IconButton(onClick = { menu = true }) {
                        Icon(Icons.Default.MoreVert, contentDescription = "More")
                    }
                    DropdownMenu(expanded = menu, onDismissRequest = { menu = false }) {
                        DropdownMenuItem(
                            text = { Text("Edit agent") },
                            onClick = {
                                menu = false
                                onEdit()
                            },
                        )
                        DropdownMenuItem(
                            text = { Text("Delete agent") },
                            onClick = {
                                menu = false
                                onDelete()
                            },
                        )
                    }
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
    newestMessageId: String?,
    input: String,
    onInput: (String) -> Unit,
    onSend: () -> Unit,
    listState: androidx.compose.foundation.lazy.LazyListState,
) {
    val agentId = agent.id
    // Both of these are observed state now. Reading a snapshot once per composition meant new
    // messages never appeared until the screen was reopened.
    val pagedMessagesByAgent by viewModel.pagedMessagesByAgent.collectAsStateWithLifecycle()
    val pagedMessages = pagedMessagesByAgent[agentId].orEmpty()
    val paginationByAgent by viewModel.messagesPaginationByAgent.collectAsStateWithLifecycle()
    val messagesPagination = paginationByAgent[agentId] ?: PaginationState()

    // Load the newest page on entry and again whenever a message is added, then stay on the tail.
    LaunchedEffect(newestMessageId) {
        viewModel.loadPagedMessages(agentId, refresh = true)
        if (newestMessageId != null) {
            listState.scrollToItem(0)
        }
    }

    Column(Modifier.fillMaxSize()) {
        LazyColumn(
            modifier = Modifier.weight(1f),
            state = listState,
            contentPadding = PaddingValues(16.dp),
            verticalArrangement = Arrangement.spacedBy(8.dp),
            // Newest first in a reversed list: index 0 sits at the bottom, next to the composer.
            reverseLayout = true,
        ) {
            if (pagedMessages.isEmpty() && !messagesPagination.isLoading) {
                item {
                    EmptyState(
                        title = "Say hello",
                        body = "Ask ${agent.name} what they are thinking, or tell them what you want the office to build. Actions that touch the outside world will ask for approval first.",
                    )
                }
            } else {
                items(pagedMessages, key = { it.id }) { message ->
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

            // Older messages live above the newest ones, so this control sits at the top.
            if (messagesPagination.hasMore) {
                item {
                    if (messagesPagination.isLoading) {
                        Row(
                            modifier = Modifier.fillMaxWidth(),
                            horizontalArrangement = Arrangement.Center,
                        ) {
                            androidx.compose.material3.CircularProgressIndicator(
                                modifier = Modifier.padding(16.dp),
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
private fun CopyMessageButton(text: String) {
    val clipboard = LocalClipboardManager.current
    IconButton(
        onClick = { clipboard.setText(AnnotatedString(text.replace("\u0000", ""))) },
        modifier = Modifier.size(28.dp),
    ) {
        Icon(
            imageVector = Icons.Default.ContentCopy,
            contentDescription = "Copy message",
            tint = MaterialTheme.colorScheme.outline,
            modifier = Modifier.size(15.dp),
        )
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
                    SelectionContainer {
                        Text(
                            text = message.content,
                            style = MaterialTheme.typography.bodyMedium,
                            color = MaterialTheme.colorScheme.onPrimary,
                            modifier = Modifier.padding(horizontal = 14.dp, vertical = 10.dp),
                        )
                    }
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
                        SelectionContainer {
                            Text(
                                text = message.content,
                                style = MaterialTheme.typography.bodyMedium,
                            )
                        }
                        Row(
                            verticalAlignment = Alignment.CenterVertically,
                            horizontalArrangement = Arrangement.spacedBy(2.dp),
                        ) {
                            Text(
                                text = Time.clock(message.createdAt),
                                style = MaterialTheme.typography.labelSmall,
                                color = MaterialTheme.colorScheme.outline,
                            )
                            CopyMessageButton(message.content)
                        }
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
                Button(onClick = onAdd) { Text("Add") }
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
    var token by remember { mutableStateOf(agent.apiToken) }
    var granted by remember {
        mutableStateOf(
            agent.allowedConnectors.split(',').map { it.trim() }.filter { it.isNotBlank() }.toSet(),
        )
    }

    AlertDialog(
        onDismissRequest = onDismiss,
        title = { Text(agent.name) },
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
                    maxLines = 6,
                    modifier = Modifier
                        .fillMaxWidth()
                        .heightIn(max = 180.dp),
                )
                OutlinedTextField(
                    value = goal,
                    onValueChange = { goal = it },
                    label = { Text("Goal") },
                    minLines = 2,
                    // Without a ceiling a long goal grows past the dialog and pushes the buttons
                    // off screen, so the field scrolls internally instead of consuming the sheet.
                    maxLines = 6,
                    modifier = Modifier
                        .fillMaxWidth()
                        .heightIn(max = 180.dp),
                )
                Text("Can use", style = MaterialTheme.typography.labelLarge)
                Text(
                    text = "Leave every chip off to allow all connectors. Picking any chip limits this agent to those.",
                    style = MaterialTheme.typography.labelSmall,
                    color = MaterialTheme.colorScheme.onSurfaceVariant,
                )
                Column {
                    com.agoria.app.core.ConnectorCatalog.all.chunked(2).forEach { pair ->
                        Row(horizontalArrangement = Arrangement.spacedBy(6.dp)) {
                            pair.forEach { def ->
                                FilterChip(
                                    selected = def.id in granted,
                                    onClick = {
                                        granted = if (def.id in granted) granted - def.id else granted + def.id
                                    },
                                    label = { Text(def.name, maxLines = 1, overflow = TextOverflow.Ellipsis) },
                                )
                            }
                        }
                    }
                }
                OutlinedTextField(
                    value = token,
                    onValueChange = { token = it },
                    label = { Text("Agent token (optional)") },
                    supportingText = { Text("Used by the HTTP connector when its Authentication is set to agent.") },
                    singleLine = true,
                    visualTransformation = PasswordVisualTransformation(),
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
                                allowedConnectors = granted.sorted().joinToString(","),
                                apiToken = token.trim(),
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

@Composable
private fun rememberHideHeaderNestedScrollConnection(): Pair<NestedScrollConnection, androidx.compose.runtime.State<Boolean>> {
    val accumulatedScroll = remember { mutableStateOf(0f) }
    val headerVisible = remember { mutableStateOf(true) }
    val connection = remember {
        NestedScrollConnection(
            onPostScroll = { dy, available ->
                accumulatedScroll.value = (accumulatedScroll.value + dy).coerceAtLeast(0f)
                
                // Hide header when scrolling down past 80px, show when scrolling up near top
                if (accumulatedScroll.value > 80.dp.toPx()) {
                    headerVisible.value = false
                } else if (accumulatedScroll.value < 20.dp.toPx()) {
                    headerVisible.value = true
                }
                Offset.Zero
            },
        )
    }
    return connection to headerVisible
}