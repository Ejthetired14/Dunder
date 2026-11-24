package com.agoria.app.ui.agent

import androidx.compose.animation.animateContentSize
import androidx.compose.foundation.background
import androidx.compose.foundation.border
import androidx.compose.foundation.clickable
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
import androidx.compose.foundation.layout.widthIn
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.LazyListState
import androidx.compose.foundation.lazy.items
import androidx.compose.foundation.lazy.rememberLazyListState
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.text.selection.SelectionContainer
import androidx.compose.foundation.verticalScroll
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.automirrored.filled.Send
import androidx.compose.material.icons.filled.Build
import androidx.compose.material.icons.filled.ContentCopy
import androidx.compose.material.icons.filled.Pause
import androidx.compose.material.icons.filled.PlayArrow
import androidx.compose.material3.ExperimentalMaterial3Api
import androidx.compose.material3.Icon
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.LocalClipboardManager
import androidx.compose.ui.text.AnnotatedString
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import com.agoria.app.data.AgentEntity
import com.agoria.app.data.DiaryEntryEntity
import com.agoria.app.data.MemoryEntity
import com.agoria.app.data.MessageEntity
import com.agoria.app.data.TaskEntity
import com.agoria.app.ui.AgentScreenState
import com.agoria.app.ui.AgoriaViewModel
import com.agoria.app.ui.PaginationState
import com.agoria.app.ui.components.AgentAvatar
import com.agoria.app.ui.components.EmptyState
import com.agoria.app.ui.components.PixelCard
import com.agoria.app.ui.components.PixelScaffold
import com.agoria.app.ui.components.StatusPill
import com.agoria.app.ui.office.world.OfficeSound
import com.agoria.app.ui.office.world.Pal
import com.agoria.app.ui.office.world.PixelButton
import com.agoria.app.ui.office.world.PixelDialog
import com.agoria.app.ui.office.world.PixelIconButton
import com.agoria.app.ui.office.world.PixelLabel
import com.agoria.app.ui.office.world.PixelPanel
import com.agoria.app.ui.office.world.PixelSlider
import com.agoria.app.ui.office.world.PixelSwitch
import com.agoria.app.ui.office.world.PixelTextField
import com.agoria.app.util.Time

@OptIn(ExperimentalMaterial3Api::class)
@Composable
fun AgentScreen(
    viewModel: AgoriaViewModel,
    agentId: String,
    onBack: () -> Unit,
) {
    val agents by viewModel.agents.collectAsStateWithLifecycle()
    val agent = agents.firstOrNull { it.id == agentId }

    PixelScaffold(
        title = agent?.name ?: "Agent",
        subtitle = agent?.role,
        onBack = onBack,
        actions = {
            if (agent != null) {
                PixelIconButton(
                    icon = if (agent.autonomy) Icons.Default.Pause else Icons.Default.PlayArrow,
                    description = if (agent.autonomy) "Pause" else "Resume",
                    onClick = {
                        if (agent.autonomy) viewModel.pauseAgent(agent.id) else viewModel.resumeAgent(agent.id)
                    },
                )
            }
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
                onBack = onBack,
                padding = padding,
            )
        }
    }
}

@Composable
private fun AgentContent(
    viewModel: AgoriaViewModel,
    agent: AgentEntity,
    onBack: () -> Unit,
    padding: PaddingValues,
) {
    val state = viewModel.agentScreenState(agent.id).collectAsStateWithLifecycle(
        AgentScreenState(null, emptyList(), emptyList(), emptyList(), emptyList(), emptyList())
    ).value

    val newestMessageId = state.messages.lastOrNull()?.id
    val diary = state.diary
    val tasks = state.tasks
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
            .padding(padding)
            .imePadding(),
    ) {
        AgentHeader(
            agent = agent,
            onToggle = {
                if (agent.autonomy) viewModel.pauseAgent(agent.id) else viewModel.resumeAgent(agent.id)
            },
            onEdit = { showEdit = true },
            onDelete = { showDelete = true },
        )

        PixelTabs(
            labels = listOf("Chat", "Diary", "Tasks", "Memory"),
            selected = tab,
            onSelect = { tab = it },
        )

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
        PixelDialog(onDismiss = { showDelete = false }, title = "Delete this agent?") {
            Text(
                text = "This removes the agent, its diary, messages, tasks, and builds from this device.",
                style = MaterialTheme.typography.bodyMedium,
            )
            Row(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                PixelButton(
                    text = "Delete",
                    onClick = {
                        viewModel.deleteAgent(agent.id)
                        showDelete = false
                        onBack()
                    },
                    small = true,
                    fill = Pal.red,
                )
                PixelButton(
                    text = "Cancel",
                    onClick = { showDelete = false },
                    small = true,
                    fill = Pal.tan,
                )
            }
        }
    }
}

/** Square tab strip (replaces the Material TabRow): selected tab amber with ink text, others teal with cream text. */
@Composable
private fun PixelTabs(
    labels: List<String>,
    selected: Int,
    onSelect: (Int) -> Unit,
) {
    Row(
        modifier = Modifier
            .fillMaxWidth()
            .padding(horizontal = 12.dp, vertical = 6.dp),
        horizontalArrangement = Arrangement.spacedBy(6.dp),
    ) {
        labels.forEachIndexed { index, label ->
            val on = index == selected
            Box(
                modifier = Modifier
                    .weight(1f)
                    .heightIn(min = 40.dp)
                    .background(if (on) Pal.amber else Pal.teal)
                    .border(2.dp, Pal.ink)
                    .clickable {
                        if (!on) OfficeSound.play(OfficeSound.Fx.CLICK)
                        onSelect(index)
                    },
                contentAlignment = Alignment.Center,
            ) {
                Text(
                    text = label.uppercase(),
                    style = PixelLabel.copy(fontSize = 11.sp, color = if (on) Pal.ink else Pal.cream),
                )
            }
        }
    }
}

@Composable
private fun AgentHeader(
    agent: AgentEntity,
    onToggle: () -> Unit,
    onEdit: () -> Unit,
    onDelete: () -> Unit,
) {
    PixelCard(
        modifier = Modifier
            .fillMaxWidth()
            .padding(horizontal = 12.dp, vertical = 8.dp),
        fill = MaterialTheme.colorScheme.surfaceVariant,
    ) {
        Column(Modifier.padding(12.dp)) {
            Row(verticalAlignment = Alignment.CenterVertically) {
                AgentAvatar(agent, 40.dp)
                Spacer(Modifier.width(10.dp))
                // Name and role are already in the top bar, so only the status goes here.
                StatusPill(agent.status)
            }
            var goalOpen by remember(agent.id) { mutableStateOf(false) }
            Spacer(Modifier.height(8.dp))
            // Long goals take two lines; tap to read all of it, tap again to fold it back.
            Text(
                text = "Goal: ${agent.goal}",
                style = MaterialTheme.typography.bodySmall,
                color = Pal.tan,
                maxLines = if (goalOpen) Int.MAX_VALUE else 2,
                overflow = TextOverflow.Ellipsis,
                modifier = Modifier
                    .fillMaxWidth()
                    .animateContentSize()
                    .clickable { goalOpen = !goalOpen },
            )
            Spacer(Modifier.height(8.dp))
            Row(
                verticalAlignment = Alignment.CenterVertically,
                horizontalArrangement = Arrangement.spacedBy(8.dp),
            ) {
                PixelSwitch(checked = agent.autonomy, onChange = { onToggle() })
                Text(
                    text = if (agent.autonomy) "Autonomy on" else "Autonomy paused",
                    style = MaterialTheme.typography.labelMedium,
                    modifier = Modifier.weight(1f),
                )
                PixelButton("Edit", onEdit, small = true)
                PixelButton("Delete", onDelete, small = true, fill = Pal.red)
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
    listState: LazyListState,
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
            verticalArrangement = Arrangement.spacedBy(10.dp),
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
                    PixelPanel(fill = MaterialTheme.colorScheme.surfaceVariant) {
                        Text(
                            text = "${agent.name} is thinking...",
                            style = MaterialTheme.typography.bodySmall,
                            color = Pal.tan,
                            modifier = Modifier.padding(12.dp),
                        )
                    }
                }
            }

            // Older messages live above the newest ones, so this control sits at the top.
            if (messagesPagination.hasMore) {
                item {
                    if (messagesPagination.isLoading) {
                        // No spinner: Material's round progress indicator breaks the pixel look.
                        Box(
                            modifier = Modifier
                                .fillMaxWidth()
                                .padding(16.dp),
                            contentAlignment = Alignment.Center,
                        ) {
                            Text("LOADING...", style = PixelLabel.copy(color = Pal.amber))
                        }
                    } else {
                        PixelButton(
                            text = "Load older messages",
                            onClick = { viewModel.loadPagedMessages(agentId) },
                            modifier = Modifier
                                .fillMaxWidth()
                                .padding(vertical = 8.dp),
                            fill = Pal.tan,
                            small = true,
                        )
                    }
                }
            }
        }

        Row(
            modifier = Modifier
                .fillMaxWidth()
                .background(Pal.ink)
                .padding(12.dp),
            verticalAlignment = Alignment.Bottom,
            horizontalArrangement = Arrangement.spacedBy(8.dp),
        ) {
            PixelTextField(
                value = input,
                onValueChange = onInput,
                label = "Message ${agent.name}",
                modifier = Modifier.weight(1f),
                onDone = onSend,
            )
            PixelIconButton(
                icon = Icons.AutoMirrored.Filled.Send,
                description = "Send",
                onClick = onSend,
            )
        }
    }
}

@Composable
private fun CopyMessageButton(text: String) {
    val clipboard = LocalClipboardManager.current
    Box(
        modifier = Modifier
            .size(28.dp)
            .clickable { clipboard.setText(AnnotatedString(text.replace("\u0000", ""))) },
        contentAlignment = Alignment.Center,
    ) {
        Icon(
            imageVector = Icons.Default.ContentCopy,
            contentDescription = "Copy message",
            tint = Pal.wood,
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
                PixelPanel(
                    modifier = Modifier.widthIn(max = 300.dp),
                    fill = Pal.amber,
                ) {
                    SelectionContainer {
                        Text(
                            text = message.content,
                            style = MaterialTheme.typography.bodyMedium,
                            color = Pal.ink,
                            modifier = Modifier.padding(horizontal = 12.dp, vertical = 10.dp),
                        )
                    }
                }
            }
        }
        "agent" -> {
            Row(
                modifier = Modifier.fillMaxWidth(),
                horizontalArrangement = Arrangement.Start,
            ) {
                PixelPanel(
                    modifier = Modifier.widthIn(max = 320.dp),
                    fill = MaterialTheme.colorScheme.surfaceVariant,
                ) {
                    Row(
                        modifier = Modifier.padding(12.dp),
                        verticalAlignment = Alignment.Top,
                        horizontalArrangement = Arrangement.spacedBy(8.dp),
                    ) {
                        AgentAvatar(agent, 32.dp)
                        Column(Modifier.weight(1f, fill = false)) {
                            Text(
                                text = message.content,
                                style = MaterialTheme.typography.bodyMedium,
                                color = Pal.cream,
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
                    color = Pal.tan,
                    modifier = Modifier.padding(horizontal = 24.dp, vertical = 4.dp),
                )
            }
        }
        "tool" -> {
            // Opaque teal (not a translucent tint) so the panel's hard shadow does not show through.
            PixelPanel(
                modifier = Modifier.fillMaxWidth(),
                fill = Pal.teal,
            ) {
                Row(
                    modifier = Modifier
                        .fillMaxWidth()
                        .padding(12.dp),
                    horizontalArrangement = Arrangement.spacedBy(8.dp),
                    verticalAlignment = Alignment.Top,
                ) {
                    Icon(
                        imageVector = Icons.Default.Build,
                        contentDescription = "Tool",
                        tint = Pal.cream,
                    )
                    Text(
                        text = message.content,
                        style = MaterialTheme.typography.bodySmall,
                        color = Pal.cream,
                    )
                }
            }
        }
    }
}

@Composable
private fun DiaryTab(viewModel: AgoriaViewModel, agentId: String, diary: List<DiaryEntryEntity>) {
    val scrollState = rememberScrollState()
    Column(
        modifier = Modifier
            .fillMaxSize()
            .verticalScroll(scrollState)
            .padding(16.dp),
        verticalArrangement = Arrangement.spacedBy(10.dp),
    ) {
        if (diary.isEmpty()) {
            EmptyState(
                title = "Diary is empty",
                body = "${viewModel.agents.collectAsStateWithLifecycle().value.firstOrNull { it.id == agentId }?.name ?: "Agent"} hasn't written anything yet.",
            )
        } else {
            diary.forEach { entry ->
                DiaryEntryCard(entry)
            }
        }
    }
}

@Composable
private fun TasksTab(tasks: List<TaskEntity>) {
    Column(
        modifier = Modifier
            .fillMaxSize()
            .padding(16.dp),
        verticalArrangement = Arrangement.spacedBy(10.dp),
    ) {
        if (tasks.isEmpty()) {
            EmptyState(
                title = "No tasks",
                body = "Tasks created by agents will appear here.",
            )
        } else {
            tasks.forEach { task ->
                TaskCard(task)
            }
        }
    }
}

@Composable
private fun MemoryTab(memories: List<MemoryEntity>, onAdd: () -> Unit) {
    Column(
        modifier = Modifier
            .fillMaxSize()
            .padding(16.dp),
        verticalArrangement = Arrangement.spacedBy(10.dp),
    ) {
        Row(
            modifier = Modifier.fillMaxWidth(),
            horizontalArrangement = Arrangement.SpaceBetween,
            verticalAlignment = Alignment.CenterVertically,
        ) {
            Text("Memories", style = MaterialTheme.typography.titleMedium)
            PixelButton("Add", onAdd, small = true)
        }
        if (memories.isEmpty()) {
            EmptyState(
                title = "No memories",
                body = "Memories help agents remember important facts across conversations.",
            )
        } else {
            memories.forEach { memory ->
                MemoryCard(memory)
            }
        }
    }
}

@Composable
private fun EditAgentDialog(
    agent: AgentEntity,
    onDismiss: () -> Unit,
    onSave: (AgentEntity) -> Unit,
) {
    var name by remember { mutableStateOf(agent.name) }
    var role by remember { mutableStateOf(agent.role) }
    var persona by remember { mutableStateOf(agent.persona) }
    var goal by remember { mutableStateOf(agent.goal) }

    PixelDialog(onDismiss = onDismiss, title = "Edit agent") {
        Column(
            modifier = Modifier
                .heightIn(max = 380.dp)
                .verticalScroll(rememberScrollState()),
            verticalArrangement = Arrangement.spacedBy(10.dp),
        ) {
            PixelTextField(
                value = name,
                onValueChange = { name = it },
                label = "Name",
                labelColor = Pal.navy,
                modifier = Modifier.fillMaxWidth(),
            )
            PixelTextField(
                value = role,
                onValueChange = { role = it },
                label = "Role",
                labelColor = Pal.navy,
                modifier = Modifier.fillMaxWidth(),
            )
            PixelTextField(
                value = persona,
                onValueChange = { persona = it },
                label = "Persona",
                singleLine = false,
                labelColor = Pal.navy,
                modifier = Modifier.fillMaxWidth(),
            )
            PixelTextField(
                value = goal,
                onValueChange = { goal = it },
                label = "Goal",
                singleLine = false,
                labelColor = Pal.navy,
                modifier = Modifier.fillMaxWidth(),
            )
        }
        Row(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
            PixelButton(
                text = "Save",
                onClick = { onSave(agent.copy(name = name, role = role, persona = persona, goal = goal)) },
                small = true,
            )
            PixelButton(
                text = "Cancel",
                onClick = onDismiss,
                small = true,
                fill = Pal.tan,
            )
        }
    }
}

@Composable
private fun AddMemoryDialog(
    onDismiss: () -> Unit,
    onAdd: (String, String, Int) -> Unit,
) {
    var kind by remember { mutableStateOf("fact") }
    var content by remember { mutableStateOf("") }
    var importance by remember { mutableStateOf(3f) }

    PixelDialog(onDismiss = onDismiss, title = "Add a memory") {
        PixelTextField(
            value = kind,
            onValueChange = { kind = it },
            label = "Kind (fact, value, plan...)",
            labelColor = Pal.navy,
            modifier = Modifier.fillMaxWidth(),
        )
        PixelTextField(
            value = content,
            onValueChange = { content = it },
            label = "What should the agent remember?",
            singleLine = false,
            labelColor = Pal.navy,
            modifier = Modifier.fillMaxWidth(),
        )
        Text("Importance: ${importance.toInt()}", style = MaterialTheme.typography.labelLarge)
        PixelSlider(
            value = importance,
            onValueChange = { importance = it },
            valueRange = 1f..5f,
            steps = 3,
        )
        Row(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
            PixelButton(
                text = "Add",
                onClick = {
                    if (content.isNotBlank()) onAdd(kind, content, importance.toInt())
                },
                small = true,
            )
            PixelButton(
                text = "Cancel",
                onClick = onDismiss,
                small = true,
                fill = Pal.tan,
            )
        }
    }
}

@Composable
private fun DiaryEntryCard(entry: DiaryEntryEntity) {
    PixelCard(
        modifier = Modifier.fillMaxWidth(),
        fill = MaterialTheme.colorScheme.surfaceVariant,
    ) {
        Column(Modifier.padding(12.dp)) {
            Row(
                modifier = Modifier.fillMaxWidth(),
                horizontalArrangement = Arrangement.SpaceBetween,
            ) {
                Text(
                    text = entry.kind.uppercase(),
                    style = MaterialTheme.typography.labelSmall,
                    color = Pal.amber,
                )
                Text(
                    text = Time.relativeTime(entry.createdAt),
                    style = MaterialTheme.typography.labelSmall,
                    color = Pal.tan,
                )
            }
            Text(
                text = entry.content,
                style = MaterialTheme.typography.bodyMedium,
                color = Pal.cream,
            )
        }
    }
}

@Composable
private fun TaskCard(task: TaskEntity) {
    PixelCard(
        modifier = Modifier.fillMaxWidth(),
        fill = MaterialTheme.colorScheme.surfaceVariant,
    ) {
        Column(Modifier.padding(12.dp)) {
            Row(
                modifier = Modifier.fillMaxWidth(),
                horizontalArrangement = Arrangement.SpaceBetween,
            ) {
                Text(
                    text = task.title,
                    style = MaterialTheme.typography.titleSmall,
                    maxLines = 1,
                    overflow = TextOverflow.Ellipsis,
                    modifier = Modifier.weight(1f),
                )
                Text(
                    text = Time.relativeTime(task.createdAt),
                    style = MaterialTheme.typography.labelSmall,
                    color = Pal.tan,
                )
            }
            if (task.description.isNotBlank()) {
                Text(
                    text = task.description,
                    style = MaterialTheme.typography.bodySmall,
                    color = Pal.tan,
                )
            }
            Spacer(Modifier.height(6.dp))
            Row(
                modifier = Modifier.fillMaxWidth(),
                horizontalArrangement = Arrangement.SpaceBetween,
                verticalAlignment = Alignment.CenterVertically,
            ) {
                StatusPill(task.status)
                Text(
                    text = "${task.progress}% complete",
                    style = MaterialTheme.typography.labelSmall,
                    color = Pal.amber,
                )
            }
        }
    }
}

@Composable
private fun MemoryCard(memory: MemoryEntity) {
    PixelCard(
        modifier = Modifier.fillMaxWidth(),
        fill = MaterialTheme.colorScheme.surfaceVariant,
    ) {
        Column(Modifier.padding(12.dp)) {
            Row(
                modifier = Modifier.fillMaxWidth(),
                horizontalArrangement = Arrangement.SpaceBetween,
            ) {
                Text(
                    text = memory.kind.uppercase(),
                    style = MaterialTheme.typography.labelSmall,
                    color = Pal.amber,
                )
                Text(
                    text = "Importance: ${memory.importance}",
                    style = MaterialTheme.typography.labelSmall,
                    color = Pal.tan,
                )
            }
            Text(
                text = memory.content,
                style = MaterialTheme.typography.bodyMedium,
                color = Pal.cream,
            )
        }
    }
}
