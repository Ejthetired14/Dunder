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
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.animation.animateContentSize
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.items
import androidx.compose.foundation.lazy.rememberLazyListState
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.verticalScroll
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.automirrored.filled.ArrowBack
import androidx.compose.material.icons.automirrored.filled.Send
import androidx.compose.material.icons.filled.Add
import androidx.compose.material.icons.filled.Delete
import androidx.compose.material.icons.filled.ContentCopy
import androidx.compose.material.icons.filled.Download
import androidx.compose.material.icons.filled.Edit
import androidx.compose.material.icons.filled.Pause
import androidx.compose.material.icons.filled.PlayArrow
import androidx.compose.material.icons.filled.Share
import android.content.ClipData
import android.content.ClipboardManager
import android.content.Context
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
import androidx.compose.ui.window.Dialog
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
import com.agoria.app.ui.office.world.Pal
import com.agoria.app.ui.office.world.PixelButton
import com.agoria.app.ui.office.world.PixelCard
import com.agoria.app.ui.office.world.PixelLabel
import com.agoria.app.ui.office.world.PixelPanel
import com.agoria.app.ui.office.world.PixelSwitch
import com.agoria.app.ui.office.world.PixelTextField
import com.agoria.app.ui.office.world.PixelPill
import com.agoria.app.ui.components.PixelScaffold

@Composable
fun AgentScreen(
    viewModel: AgoriaViewModel,
    agentId: String,
    onBack: () -> Unit,
) {
    val context = LocalContext.current
    val agents by viewModel.agents.collectAsStateWithLifecycle()
    val agent = agents.firstOrNull { it.id == agentId }

    PixelScaffold(
        title = agent?.name ?: "Agent",
        onBack = onBack,
        actions = {
            if (agent != null) {
                PixelIconButton(
                    icon = if (agent.autonomy) Pause else PlayArrow,
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
            listState.scrollToItem(0)
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

        // Custom tab row with PixelPill
        Row(
            modifier = Modifier
                .fillMaxWidth()
                .padding(horizontal = 12.dp, vertical = 8.dp),
            horizontalArrangement = Arrangement.spacedBy(8.dp),
        ) {
            PixelPill(
                text = "Chat",
                modifier = Modifier.weight(1f),
                fill = if (tab == 0) Pal.amber else Pal.cream,
                textColor = Pal.ink,
            ).also { modifier ->
                Box(
                    modifier = modifier
                        .fillMaxWidth()
                        .clickable { tab = 0 },
                    contentAlignment = Alignment.Center,
                ) {
                    Text("CHAT", style = PixelLabel.copy(fontSize = 10.sp))
                }
            }
            PixelPill(
                text = "Diary",
                modifier = Modifier.weight(1f),
                fill = if (tab == 1) Pal.amber else Pal.cream,
                textColor = Pal.ink,
            ).also { modifier ->
                Box(
                    modifier = modifier
                        .fillMaxWidth()
                        .clickable { tab = 1 },
                    contentAlignment = Alignment.Center,
                ) {
                    Text("DIARY", style = PixelLabel.copy(fontSize = 10.sp))
                }
            }
            PixelPill(
                text = "Tasks",
                modifier = Modifier.weight(1f),
                fill = if (tab == 2) Pal.amber else Pal.cream,
                textColor = Pal.ink,
            ).also { modifier ->
                Box(
                    modifier = modifier
                        .fillMaxWidth()
                        .clickable { tab = 2 },
                    contentAlignment = Alignment.Center,
                ) {
                    Text("TASKS", style = PixelLabel.copy(fontSize = 10.sp))
                }
            }
            PixelPill(
                text = "Memory",
                modifier = Modifier.weight(1f),
                fill = if (tab == 3) Pal.amber else Pal.cream,
                textColor = Pal.ink,
            ).also { modifier ->
                Box(
                    modifier = modifier
                        .fillMaxWidth()
                        .clickable { tab = 3 },
                    contentAlignment = Alignment.Center,
                ) {
                    Text("MEMORY", style = PixelLabel.copy(fontSize = 10.sp))
                }
            }
            PixelPill(
                text = "Builds",
                modifier = Modifier.weight(1f),
                fill = if (tab == 4) Pal.amber else Pal.cream,
                textColor = Pal.ink,
            ).also { modifier ->
                Box(
                    modifier = modifier
                        .fillMaxWidth()
                        .clickable { tab = 4 },
                    contentAlignment = Alignment.Center,
                ) {
                    Text("BUILDS", style = PixelLabel.copy(fontSize = 10.sp))
                }
            }
        }

        when (tab) {
            0 -> ChatTab(
                viewModel = viewModel,
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
        DeleteAgentDialog(
            agent = agent,
            onDismiss = { showDelete = false },
            onDelete = {
                viewModel.deleteAgent(agent.id)
                showDelete = false
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
    PixelCard(modifier = Modifier.fillMaxWidth(), fill = Pal.navy) {
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
                PixelButton("EDIT", onEdit, fill = Pal.cream, small = true)
            }
            Spacer(Modifier.height(8.dp))
            Text(
                text = "Goal: ${agent.goal}",
                style = MaterialTheme.typography.bodySmall,
                color = MaterialTheme.colorScheme.onSurfaceVariant,
            )
            Spacer(Modifier.height(6.dp))
            Row(verticalAlignment = Alignment.CenterVertically) {
                PixelSwitch(checked = agent.autonomy, onChange = { onToggle() })
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
            reverseLayout = true,
        ) {
            if (messages.isEmpty()) {
                item {
                    EmptyState(
                        title = "Say hello",
                        body = "Ask ${agent.name} what they are thinking, or tell them what you want the office to build. Actions that touch the outside world will ask for approval first.",
                    )
                }
            } else {
                items(messages.reversed(), key = { it.id }) { message ->
                    MessageRow(message = message, agent = agent)
                }
            }
            if (agent.status == "thinking") {
                item {
                    PixelPanel(
                        modifier = Modifier.fillMaxWidth(),
                        fill = Pal.tan.copy(alpha = 0.3f),
                    ) {
                        Text(
                            text = "${agent.name} is thinking...",
                            style = MaterialTheme.typography.bodySmall,
                            color = Pal.ink,
                            modifier = Modifier.padding(12.dp),
                        )
                    }
                }
            }
        }

        PixelPanel(modifier = Modifier.fillMaxWidth(), fill = Pal.cream) {
            Row(
                modifier = Modifier
                    .fillMaxWidth()
                    .padding(12.dp),
                verticalAlignment = Alignment.Bottom,
            ) {
                PixelTextField(
                    value = input,
                    onValueChange = onInput,
                    modifier = Modifier.weight(1f),
                    label = "Message ${agent.name}...",
                    singleLine = false,
                )
                Spacer(Modifier.width(8.dp))
                PixelIconButton(
                    icon = Send,
                    description = "Send",
                    onClick = onSend,
                    modifier = Modifier.padding(bottom = 4.dp),
                )
            }
        }
    }
}

@Composable
private fun MessageRow(message: MessageEntity, agent: AgentEntity) {
    val context = LocalContext.current
    val copyToClipboard = { text: String ->
        val clipboard = context.getSystemService(Context.CLIPBOARD_SERVICE) as ClipboardManager
        clipboard.primaryClip = ClipData.newPlainText("Agent Message", text)
    }

    when (message.role) {
        "user" -> {
            Row(
                modifier = Modifier.fillMaxWidth(),
                horizontalArrangement = Arrangement.End,
            ) {
                PixelPanel(
                    modifier = Modifier.widthIn(max = 280.dp),
                    fill = Pal.amber,
                ) {
                    Text(
                        text = message.content,
                        style = MaterialTheme.typography.bodyMedium,
                        color = Pal.ink,
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
                PixelPanel(
                    modifier = Modifier
                        .weight(1f, fill = false)
                        .combinedClickable(
                            onLongClick = { copyToClipboard(message.content) },
                            onClick = {}
                        ),
                    fill = Pal.cream,
                ) {
                    Column(Modifier.padding(horizontal = 14.dp, vertical = 10.dp)) {
                        Row(
                            modifier = Modifier.fillMaxWidth(),
                            horizontalArrangement = Arrangement.SpaceBetween,
                        ) {
                            Text(
                                text = message.content,
                                style = MaterialTheme.typography.bodyMedium,
                            )
                            PixelIconButton(
                                icon = ContentCopy,
                                description = "Copy message",
                                onClick = { copyToClipboard(message.content) },
                            )
                        }
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
                    modifier = Modifier
                        .padding(horizontal = 24.dp, vertical = 4.dp)
                        .fillMaxWidth()
                        .combinedClickable(
                            onLongClick = { copyToClipboard(message.content) },
                            onClick = {}
                        ),
                )
            }
        }
        "tool" -> {
            PixelPanel(
                modifier = Modifier
                    .fillMaxWidth()
                    .combinedClickable(
                        onLongClick = { copyToClipboard(message.content) },
                        onClick = {}
                    ),
                fill = Pal.tan.copy(alpha = 0.3f),
            ) {
                Column(Modifier.padding(12.dp)) {
                    Row(
                        modifier = Modifier.fillMaxWidth(),
                        horizontalArrangement = Arrangement.SpaceBetween,
                    ) {
                        Text(
                            text = "CONNECTOR RESULT",
                            style = MaterialTheme.typography.labelSmall,
                            color = Pal.ink,
                        )
                        PixelIconButton(
                            icon = ContentCopy,
                            description = "Copy result",
                            onClick = { copyToClipboard(message.content) },
                        )
                    }
                    Text(
                        text = message.content,
                        style = MaterialTheme.typography.bodySmall,
                        fontFamily = FontFamily.Monospace,
                        color = Pal.ink,
                    )
                }
            }
        }
        else -> {
            Text(
                text = message.content,
                style = MaterialTheme.typography.bodySmall,
                color = MaterialTheme.colorScheme.onSurfaceVariant,
                modifier = Modifier
                    .fillMaxWidth()
                    .combinedClickable(
                        onLongClick = { copyToClipboard(message.content) },
                        onClick = {}
                    ),
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
            modifier = Modifier
                .fillMaxSize()
                .padding(16.dp),
            contentPadding = PaddingValues(vertical = 8.dp),
            verticalArrangement = Arrangement.spacedBy(10.dp),
        ) {
            items(diary, key = { it.id }) { entry ->
                PixelCard(modifier = Modifier.fillMaxWidth()) {
                    Column(Modifier.padding(14.dp)) {
                        Row(verticalAlignment = Alignment.CenterVertically) {
                            PixelPill(text = entry.kind)
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
            modifier = Modifier
                .fillMaxSize()
                .padding(16.dp),
            contentPadding = PaddingValues(vertical = 8.dp),
            verticalArrangement = Arrangement.spacedBy(10.dp),
        ) {
            items(tasks, key = { it.id }) { task ->
                PixelCard(modifier = Modifier.fillMaxWidth()) {
                    Column(Modifier.padding(14.dp)) {
                        Row(verticalAlignment = Alignment.CenterVertically) {
                            Text(task.title, style = MaterialTheme.typography.titleMedium, modifier = Modifier.weight(1f))
                            PixelPill(text = task.status)
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
                        // Use a simple pixel progress bar
                        PixelProgressBar(progress = task.progress.coerceIn(0, 100) / 100f)
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
private fun PixelProgressBar(progress: Float) {
    PixelPanel(modifier = Modifier.fillMaxWidth().height(8.dp), fill = Pal.navy.copy(alpha = 0.2f)) {
        Box(
            modifier = Modifier
                .fillMaxHeight()
                .width(fraction = progress)
                .background(Pal.amber)
        )
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
                        PixelCard(modifier = Modifier.fillMaxWidth()) {
                            Column(Modifier.padding(12.dp)) {
                                Row(verticalAlignment = Alignment.CenterVertically) {
                                    PixelPill(text = memory.kind)
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
                PixelPanel(fill = Pal.cream) {
                    Row(
                        modifier = Modifier
                            .fillMaxWidth()
                            .padding(12.dp),
                        horizontalArrangement = Arrangement.End,
                    ) {
                        PixelButton("ADD MEMORY", onAdd)
                    }
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

    LaunchedEffect(Unit) {
        viewModel.loadPagedBuilds(agentId, refresh = true)
    }

    Column(Modifier.fillMaxSize()) {
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
                    PixelCard(modifier = Modifier.fillMaxWidth()) {
                        Column(Modifier.padding(14.dp)) {
                            Row(verticalAlignment = Alignment.CenterVertically) {
                                Text(build.name, style = MaterialTheme.typography.titleMedium, modifier = Modifier.weight(1f))
                                PixelPill(text = "${build.fileCount} files")
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
                                PixelButton("SHARE ZIP", { onShare(build) }, small = true)
                                PixelButton(
                                    text = if (savingBuildId == build.id) "SAVING..." else "SAVE TO DEVICE",
                                    onClick = {
                                        savingBuildId = build.id
                                        viewModel.saveBuildToDownloads(context, build) { uri ->
                                            savingBuildId = null
                                        }
                                    },
                                    enabled = savingBuildId != build.id,
                                    fill = Pal.cream,
                                    small = true,
                                )
                            }
                        }
                    }
                }

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
                            PixelButton("LOAD MORE", { viewModel.loadPagedBuilds(agentId) }, fill = Pal.cream, small = true)
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
    PixelPanel(
        modifier = Modifier
            .fillMaxWidth()
            .padding(16.dp)
            .animateContentSize(),
        fill = color.copy(alpha = 0.1f),
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
                PixelProgressBar(progress = progress)
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

    Dialog(onDismissRequest = onDismiss) {
        PixelPanel(modifier = Modifier.fillMaxWidth()) {
            Column(
                modifier = Modifier.padding(16.dp),
                verticalArrangement = Arrangement.spacedBy(10.dp),
            ) {
                Text("EDIT ${agent.name.uppercase()}", style = PixelLabel)
                PixelTextField(
                    value = name,
                    onValueChange = { name = it },
                    modifier = Modifier.fillMaxWidth(),
                    label = "Name",
                )
                PixelTextField(
                    value = role,
                    onValueChange = { role = it },
                    modifier = Modifier.fillMaxWidth(),
                    label = "Role",
                )
                PixelTextField(
                    value = persona,
                    onValueChange = { persona = it },
                    modifier = Modifier.fillMaxWidth(),
                    label = "Persona",
                    singleLine = false,
                )
                PixelTextField(
                    value = goal,
                    onValueChange = { goal = it },
                    modifier = Modifier.fillMaxWidth(),
                    label = "Goal",
                    singleLine = false,
                )
                PixelButton(
                    text = if (confirmDelete) "TAP AGAIN TO DELETE" else "DELETE AGENT",
                    onClick = { confirmDelete = !confirmDelete },
                    fill = Pal.cream,
                )
                if (confirmDelete) {
                    Text(
                        text = "This permanently removes the agent and all of its data on this device.",
                        style = MaterialTheme.typography.labelSmall,
                        color = Pal.red,
                    )
                }
                Row(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                    PixelButton(
                        text = if (confirmDelete) "DELETE" else "SAVE",
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
                    )
                    PixelButton("CANCEL", onDismiss, fill = Pal.cream)
                }
            }
        }
    }
}

@Composable
private fun AddMemoryDialog(
    onDismiss: () -> Unit,
    onAdd: (kind: String, content: String, importance: Int) -> Unit,
) {
    var kind by remember { mutableStateOf("fact") }
    var content by remember { mutableStateOf("") }
    var importance by remember { mutableStateOf(3f) }

    Dialog(onDismissRequest = onDismiss) {
        PixelPanel(modifier = Modifier.fillMaxWidth()) {
            Column(
                modifier = Modifier.padding(16.dp),
                verticalArrangement = Arrangement.spacedBy(10.dp),
            ) {
                Text("ADD A MEMORY", style = PixelLabel)
                PixelTextField(
                    value = kind,
                    onValueChange = { kind = it },
                    modifier = Modifier.fillMaxWidth(),
                    label = "Kind (fact, value, plan...)",
                )
                PixelTextField(
                    value = content,
                    onValueChange = { content = it },
                    modifier = Modifier.fillMaxWidth(),
                    label = "What should the agent remember?",
                    singleLine = false,
                )
                Text("Importance: ${importance.toInt()}", style = PixelLabel)
                androidx.compose.material3.Slider(
                    value = importance,
                    onValueChange = { importance = it },
                    valueRange = 1f..5f,
                    steps = 3,
                )
                Row(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                    PixelButton("ADD", { if (content.isNotBlank()) onAdd(kind, content, importance.toInt()) })
                    PixelButton("CANCEL", onDismiss, fill = Pal.cream)
                }
            }
        }
    }
}

@Composable
private fun DeleteAgentDialog(
    agent: AgentEntity,
    onDismiss: () -> Unit,
    onDelete: () -> Unit,
) {
    Dialog(onDismissRequest = onDismiss) {
        PixelPanel(modifier = Modifier.fillMaxWidth()) {
            Column(
                modifier = Modifier.padding(16.dp),
                verticalArrangement = Arrangement.spacedBy(10.dp),
            ) {
                Text("DELETE ${agent.name.uppercase()}?", style = PixelLabel)
                Text(
                    text = "This removes the agent, its diary, messages, tasks, and builds from this device.",
                    style = PixelLabel.copy(fontSize = 12.sp),
                )
                Row(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                    PixelButton("DELETE", onDelete, fill = Pal.red)
                    PixelButton("CANCEL", onDismiss, fill = Pal.cream)
                }
            }
        }
    }
}

@Composable
private fun PixelIconButton(
    icon: androidx.compose.ui.graphics.vector.ImageVector,
    description: String,
    onClick: () -> Unit,
    modifier: Modifier = Modifier,
) {
    Box(
        modifier = modifier
            .size(44.dp)
            .background(Pal.cream)
            .border(2.dp, Pal.ink)
            .clickable(onClick = onClick),
        contentAlignment = Alignment.Center,
    ) {
        androidx.compose.material3.Icon(
            imageVector = icon,
            contentDescription = description,
            tint = Pal.ink,
            modifier = Modifier.size(22.dp),
        )
    }
}