package com.agoria.app.ui.home

import android.Manifest
import android.content.pm.PackageManager
import android.os.Build
import androidx.activity.compose.rememberLauncherForActivityResult
import androidx.activity.result.contract.ActivityResultContracts
import androidx.compose.foundation.background
import androidx.compose.foundation.border
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.ColumnScope
import androidx.compose.foundation.layout.PaddingValues
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.LazyRow
import androidx.compose.foundation.lazy.items
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.AutoAwesome
import androidx.compose.material.icons.filled.FactCheck
import androidx.compose.material3.Icon
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.semantics.Role
import androidx.compose.ui.semantics.clearAndSetSemantics
import androidx.compose.ui.semantics.contentDescription
import androidx.compose.ui.semantics.onClick
import androidx.compose.ui.semantics.role
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import androidx.core.content.ContextCompat
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import com.agoria.app.data.AgentEntity
import com.agoria.app.data.DiaryEntryEntity
import com.agoria.app.ui.AgoriaViewModel
import com.agoria.app.ui.components.AgentAvatar
import com.agoria.app.ui.components.AgentCard
import com.agoria.app.ui.components.OfficeThumbnail
import com.agoria.app.ui.components.PixelCard
import com.agoria.app.ui.components.PixelScaffold
import com.agoria.app.ui.components.PixelPill
import com.agoria.app.ui.components.SectionHeader
import com.agoria.app.ui.office.world.Pal
import com.agoria.app.ui.office.world.PixelButton
import com.agoria.app.ui.office.world.PixelDialog
import com.agoria.app.ui.office.world.PixelLabel
import com.agoria.app.ui.office.world.PixelPanel
import com.agoria.app.ui.office.world.PixelSwitch
import com.agoria.app.ui.office.world.PixelTextField
import com.agoria.app.util.Time

@Composable
fun HomeScreen(
    viewModel: AgoriaViewModel,
    onOpenAgent: (String) -> Unit,
    onOpenSettings: () -> Unit,
    onOpenApprovals: () -> Unit,
    onOpenDiary: () -> Unit,
    onOpenOffice: () -> Unit,
    onBack: () -> Unit,
) {
    val context = LocalContext.current
    val state by viewModel.homeScreenState.collectAsStateWithLifecycle()
    var showAdd by remember { mutableStateOf(false) }

    val agents = state.agents
    val diary = state.diary
    val pending = state.pendingApprovals
    val settings = state.settings
    val runtime = state.runtimeState

    val notificationPermission = rememberLauncherForActivityResult(
        ActivityResultContracts.RequestPermission(),
    ) { }

    val autonomyEnabled = settings?.autonomyEnabled ?: false
    val officeName = settings?.officeName ?: "Dunder"
    val userName = settings?.userName ?: "Boss"
    val latestDiary = diary.take(5)

    PixelScaffold(
        title = "Home",
        subtitle = "Welcome back, $userName · $officeName",
        onBack = onBack,
    ) { padding ->
        LazyColumn(
            modifier = Modifier
                .fillMaxSize()
                .padding(padding),
            contentPadding = PaddingValues(16.dp),
            verticalArrangement = Arrangement.spacedBy(16.dp),
        ) {
            item {
                OfficeHeroCard(
                    officeName = officeName,
                    userName = userName,
                    autonomyEnabled = autonomyEnabled,
                    running = runtime.running,
                    currentAgent = runtime.currentAgentName,
                    onToggle = { enabled ->
                        viewModel.setAutonomy(enabled)
                        if (enabled && Build.VERSION.SDK_INT >= 33 &&
                            ContextCompat.checkSelfPermission(
                                context,
                                Manifest.permission.POST_NOTIFICATIONS,
                            ) != PackageManager.PERMISSION_GRANTED
                        ) {
                            notificationPermission.launch(Manifest.permission.POST_NOTIFICATIONS)
                        }
                    },
                )
            }

            if (pending.isNotEmpty()) {
                item {
                    PixelCard(
                        modifier = Modifier
                            .fillMaxWidth()
                            .clickable(onClick = onOpenApprovals)
                            .clearAndSetSemantics {
                                contentDescription =
                                    "${pending.size} approval${if (pending.size == 1) "" else "s"} waiting. " +
                                        "Next: ${pending.first().title}"
                                role = Role.Button
                                onClick(label = "Review approvals") { onOpenApprovals(); true }
                            },
                        fill = Pal.amber,
                    ) {
                        Row(
                            modifier = Modifier
                                .fillMaxWidth()
                                .padding(14.dp),
                            verticalAlignment = Alignment.CenterVertically,
                        ) {
                            Icon(
                                Icons.Default.FactCheck,
                                contentDescription = null,
                                tint = Pal.ink,
                            )
                            Spacer(Modifier.width(12.dp))
                            Column(Modifier.weight(1f)) {
                                Text(
                                    "${pending.size} approval${if (pending.size == 1) "" else "s"} waiting",
                                    style = MaterialTheme.typography.titleMedium,
                                    color = Pal.ink,
                                )
                                Text(
                                    text = pending.first().title,
                                    style = MaterialTheme.typography.bodySmall,
                                    color = Pal.ink,
                                    maxLines = 1,
                                    overflow = TextOverflow.Ellipsis,
                                )
                            }
                            Text("REVIEW", style = PixelLabel)
                        }
                    }
                }
            }

            item {
                RuntimeCard(
                    running = runtime.running,
                    ticking = runtime.ticking,
                    currentAgent = runtime.currentAgentName,
                    lastSummary = runtime.lastSummary,
                    lastLog = runtime.log.lastOrNull()?.message,
                    onRunNow = { viewModel.runNow() },
                    onNudge = { viewModel.nudgeAll() },
                )
            }

            item {
                SectionHeader(
                    title = "The agents",
                    actionLabel = "Office view",
                    onAction = onOpenOffice,
                )
            }
            item {
                PixelButton("Hire agent", { showAdd = true }, small = true)
            }
            item {
                if (agents.isEmpty()) {
                    PixelCard(modifier = Modifier.fillMaxWidth()) {
                        Column(Modifier.padding(16.dp)) {
                            Text("The office is still being prepared.", style = MaterialTheme.typography.titleMedium)
                            Text(
                                "The first agents will appear as soon as the local database is seeded.",
                                style = MaterialTheme.typography.bodySmall,
                                color = Pal.navy,
                            )
                        }
                    }
                } else {
                    LazyRow(
                        horizontalArrangement = Arrangement.spacedBy(12.dp),
                        contentPadding = PaddingValues(vertical = 2.dp),
                    ) {
                        items(agents, key = { it.id }) { agent ->
                            AgentCard(
                                agent = agent,
                                latestDiary = diary.firstOrNull { it.agentId == agent.id }?.content,
                                onClickAction = { onOpenAgent(agent.id) },
                                modifier = Modifier.width(280.dp),
                            )
                        }
                    }
                }
            }

            item {
                SectionHeader(
                    title = "Live diary",
                    actionLabel = "Read all",
                    onAction = onOpenDiary,
                )
            }
            if (latestDiary.isEmpty()) {
                item {
                    Text(
                        text = "No thoughts yet. The agents will start writing when autonomy is on.",
                        style = MaterialTheme.typography.bodySmall,
                        color = Pal.tan,
                        modifier = Modifier.padding(vertical = 8.dp),
                    )
                }
            } else {
                items(latestDiary, key = { it.id }) { entry ->
                    val agent = agents.firstOrNull { it.id == entry.agentId }
                    DiaryRow(entry = entry, agent = agent, onClickAction = { onOpenAgent(entry.agentId) })
                }
            }

            item {
                Spacer(Modifier.height(8.dp))
                Text(
                    text = "Free models are listed in Settings. The app ships with keyless providers enabled; add keys for reliability.",
                    style = MaterialTheme.typography.labelSmall,
                    color = Pal.wood,
                )
            }
        }
    }

    if (showAdd) {
        AddAgentDialog(
            onDismiss = { showAdd = false },
            onCreate = { name, role, persona, goal ->
                viewModel.addAgent(name, role, persona, goal)
                showAdd = false
            },
        )
    }
}

@Composable
private fun OfficeHeroCard(
    officeName: String,
    userName: String,
    autonomyEnabled: Boolean,
    running: Boolean,
    currentAgent: String?,
    onToggle: (Boolean) -> Unit,
) {
    PixelCard(modifier = Modifier.fillMaxWidth(), fill = Pal.navy) {
        Column(Modifier.fillMaxWidth()) {
            OfficeThumbnail(modifier = Modifier.fillMaxWidth().height(120.dp))
            Column(Modifier.padding(14.dp)) {
                PixelPill(
                    text = if (running) "Autonomy live" else "Autonomy resting",
                    fill = if (running) Pal.amber.copy(alpha = 0.14f) else Pal.tan.copy(alpha = 0.14f),
                    textColor = if (running) Pal.amber else Pal.tan,
                )
                Spacer(Modifier.height(8.dp))
                Text(
                    text = "Welcome back, $userName.",
                    style = MaterialTheme.typography.titleLarge,
                    color = Pal.cream,
                )
                Text(
                    text = if (running && currentAgent != null) {
                        "$currentAgent is thinking right now."
                    } else {
                        "$officeName is waiting for its next idea."
                    },
                    style = MaterialTheme.typography.bodyMedium,
                    color = Pal.tan,
                )
                Spacer(Modifier.height(10.dp))
                Row(verticalAlignment = Alignment.CenterVertically) {
                    PixelSwitch(checked = autonomyEnabled, onChange = onToggle)
                    Spacer(Modifier.width(10.dp))
                    Text(
                        text = if (autonomyEnabled) "Agents are free to act" else "Agents are paused",
                        style = MaterialTheme.typography.labelMedium,
                        color = Pal.cream,
                    )
                }
            }
        }
    }
}

@Composable
private fun RuntimeCard(
    running: Boolean,
    ticking: Boolean,
    currentAgent: String?,
    lastSummary: String?,
    lastLog: String?,
    onRunNow: () -> Unit,
    onNudge: () -> Unit,
) {
    PixelCard(modifier = Modifier.fillMaxWidth()) {
        Column(Modifier.padding(16.dp)) {
            Row(verticalAlignment = Alignment.CenterVertically) {
                Icon(
                    Icons.Default.AutoAwesome,
                    contentDescription = null,
                    tint = Pal.teal,
                )
                Spacer(Modifier.width(8.dp))
                Text(
                    text = if (ticking) "The office is thinking" else if (running) "The office is listening" else "The office is paused",
                    style = MaterialTheme.typography.titleMedium,
                    modifier = Modifier.weight(1f),
                )
                if (running) {
                    PixelPill(text = "Live", fill = Pal.amber.copy(alpha = 0.14f), textColor = Pal.amber)
                }
            }
            Spacer(Modifier.height(8.dp))
            Text(
                text = when {
                    currentAgent != null -> "$currentAgent is in the middle of a cycle."
                    lastSummary != null -> lastSummary
                    else -> "Start autonomy to let the agents write, plan, and propose builds."
                },
                style = MaterialTheme.typography.bodySmall,
                color = Pal.navy,
                maxLines = 2,
                overflow = TextOverflow.Ellipsis,
            )
            if (lastLog != null) {
                Spacer(Modifier.height(4.dp))
                Text(
                    text = lastLog,
                    style = MaterialTheme.typography.labelSmall,
                    color = Pal.wood,
                    maxLines = 1,
                    overflow = TextOverflow.Ellipsis,
                )
            }
            Spacer(Modifier.height(12.dp))
            Row(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                PixelButton("Run one cycle", onRunNow, small = true)
                PixelButton("Nudge everyone", onNudge, fill = Pal.cream, small = true)
            }
        }
    }
}

@Composable
private fun DiaryRow(
    entry: DiaryEntryEntity,
    agent: AgentEntity?,
    onClickAction: () -> Unit,
) {
    val open = onClickAction
    val description = buildString {
        append(agent?.name ?: "Office").append(", ").append(entry.kind).append(", ")
        append(Time.relativeTime(entry.createdAt)).append(". ")
        append(entry.content.take(160))
    }
    PixelCard(
        modifier = Modifier
            .fillMaxWidth()
            .clickable(onClick = onClick)
            .clearAndSetSemantics {
                contentDescription = description
                role = Role.Button
                onClick(label = "Open diary entry") { open(); true }
            },
    ) {
        Row(
            modifier = Modifier.padding(14.dp),
            verticalAlignment = Alignment.Top,
        ) {
            if (agent != null) {
                AgentAvatar(agent, 34.dp, showStatus = false)
            } else {
                Box(
                    Modifier
                        .size(34.dp)
                        .background(Pal.navy)
                        .border(2.dp, Pal.ink),
                )
            }
            Spacer(Modifier.width(10.dp))
            Column(Modifier.weight(1f)) {
                Row(verticalAlignment = Alignment.CenterVertically) {
                    Text(
                        text = agent?.name ?: "Office",
                        style = MaterialTheme.typography.labelLarge,
                    )
                    Spacer(Modifier.width(6.dp))
                    PixelPill(
                        text = entry.kind,
                        fill = Pal.amber.copy(alpha = 0.14f),
                        textColor = Pal.amber,
                    )
                    Spacer(Modifier.weight(1f))
                    Text(
                        text = Time.relativeTime(entry.createdAt),
                        style = MaterialTheme.typography.labelSmall,
                        color = Pal.navy,
                    )
                }
                Spacer(Modifier.height(2.dp))
                Text(
                    text = entry.content,
                    style = MaterialTheme.typography.bodySmall,
                    color = Pal.navy,
                    maxLines = 3,
                    overflow = TextOverflow.Ellipsis,
                )
            }
        }
    }
}

@Composable
private fun AddAgentDialog(
    onDismiss: () -> Unit,
    onCreate: (name: String, role: String, persona: String, goal: String) -> Unit,
) {
    var name by remember { mutableStateOf("") }
    var role by remember { mutableStateOf("Generalist") }
    var persona by remember { mutableStateOf("") }
    var goal by remember { mutableStateOf("") }

    // Field labels are navy: the dialog is cream and the PixelTextField default label colour is amber (rule 8).
    PixelDialog(onDismiss = onDismiss, title = "Hire an agent") {
        PixelTextField(
            value = name,
            onValueChange = { name = it },
            modifier = Modifier.fillMaxWidth(),
            label = "Name",
            labelColor = Pal.navy,
        )
        PixelTextField(
            value = role,
            onValueChange = { role = it },
            modifier = Modifier.fillMaxWidth(),
            label = "Role",
            labelColor = Pal.navy,
        )
        PixelTextField(
            value = persona,
            onValueChange = { persona = it },
            modifier = Modifier.fillMaxWidth(),
            label = "Persona (optional)",
            singleLine = false,
            labelColor = Pal.navy,
        )
        PixelTextField(
            value = goal,
            onValueChange = { goal = it },
            modifier = Modifier.fillMaxWidth(),
            label = "Goal (optional)",
            singleLine = false,
            labelColor = Pal.navy,
        )
        Row(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
            PixelButton("Create", { if (name.isNotBlank()) onCreate(name, role, persona, goal) })
            PixelButton("Cancel", onDismiss, fill = Pal.cream)
        }
    }
}
