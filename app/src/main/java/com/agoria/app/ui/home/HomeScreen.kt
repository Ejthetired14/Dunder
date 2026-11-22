package com.agoria.app.ui.home

import android.Manifest
import android.content.pm.PackageManager
import android.os.Build
import androidx.activity.compose.rememberLauncherForActivityResult
import androidx.activity.result.contract.ActivityResultContracts
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
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.LazyRow
import androidx.compose.foundation.lazy.items
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.Add
import androidx.compose.material.icons.filled.AutoAwesome
import androidx.compose.material.icons.filled.Bolt
import androidx.compose.material.icons.filled.EditNote
import androidx.compose.material.icons.filled.FactCheck
import androidx.compose.material.icons.filled.PlayArrow
import androidx.compose.material.icons.filled.Settings
import androidx.compose.material3.AlertDialog
import androidx.compose.material3.Button
import androidx.compose.material3.Card
import androidx.compose.material3.CardDefaults
import androidx.compose.material3.ExperimentalMaterial3Api
import androidx.compose.material3.Icon
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedTextField
import androidx.compose.material3.Surface
import androidx.compose.material3.Switch
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.graphics.Brush
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.text.font.FontStyle
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import androidx.core.content.ContextCompat
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import com.agoria.app.data.AgentEntity
import com.agoria.app.data.DiaryEntryEntity
import com.agoria.app.ui.AgoriaViewModel
import com.agoria.app.ui.HomeScreenState
import com.agoria.app.ui.components.AgentAvatar
import com.agoria.app.ui.components.AgentCard
import com.agoria.app.ui.components.OfficeThumbnail
import com.agoria.app.ui.components.Pill
import com.agoria.app.ui.components.PixelScaffold
import com.agoria.app.ui.components.SectionHeader
import com.agoria.app.util.Time

@OptIn(ExperimentalMaterial3Api::class)
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
                    Card(
                        onClick = onOpenApprovals,
                        modifier = Modifier.fillMaxWidth(),
                        colors = CardDefaults.cardColors(
                            containerColor = MaterialTheme.colorScheme.errorContainer,
                        ),
                    ) {
                        Row(
                            modifier = Modifier
                                .fillMaxWidth()
                                .padding(16.dp),
                            verticalAlignment = Alignment.CenterVertically,
                        ) {
                            Icon(
                                Icons.Default.FactCheck,
                                contentDescription = null,
                                tint = MaterialTheme.colorScheme.onErrorContainer,
                            )
                            Spacer(Modifier.width(12.dp))
                            Column(Modifier.weight(1f)) {
                                Text(
                                    "${pending.size} approval${if (pending.size == 1) "" else "s"} waiting",
                                    style = MaterialTheme.typography.titleMedium,
                                    color = MaterialTheme.colorScheme.onErrorContainer,
                                )
                                Text(
                                    text = pending.first().title,
                                    style = MaterialTheme.typography.bodySmall,
                                    color = MaterialTheme.colorScheme.onErrorContainer,
                                    maxLines = 1,
                                    overflow = TextOverflow.Ellipsis,
                                )
                            }
                            Text(
                                "Review",
                                style = MaterialTheme.typography.labelLarge,
                                color = MaterialTheme.colorScheme.onErrorContainer,
                            )
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
                if (agents.isEmpty()) {
                    Surface(
                        modifier = Modifier.fillMaxWidth(),
                        shape = MaterialTheme.shapes.large,
                        color = MaterialTheme.colorScheme.surfaceVariant,
                    ) {
                        Column(Modifier.padding(16.dp)) {
                            Text("The office is still being prepared.", style = MaterialTheme.typography.titleMedium)
                            Text(
                                "The first agents will appear as soon as the local database is seeded.",
                                style = MaterialTheme.typography.bodySmall,
                                color = MaterialTheme.colorScheme.onSurfaceVariant,
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
                                onClick = { onOpenAgent(agent.id) },
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
                        color = MaterialTheme.colorScheme.onSurfaceVariant,
                        modifier = Modifier.padding(vertical = 8.dp),
                    )
                }
            } else {
                items(latestDiary, key = { it.id }) { entry ->
                    val agent = agents.firstOrNull { it.id == entry.agentId }
                    DiaryRow(entry = entry, agent = agent, onClick = { onOpenAgent(entry.agentId) })
                }
            }

            item {
                Spacer(Modifier.height(8.dp))
                Text(
                    text = "Free models are listed in Settings. The app ships with keyless providers enabled; add keys for reliability.",
                    style = MaterialTheme.typography.labelSmall,
                    color = MaterialTheme.colorScheme.onSurfaceVariant,
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
    Box(
        modifier = Modifier
            .fillMaxWidth()
            .height(220.dp)
            .clip(RoundedCornerShape(28.dp)),
    ) {
        OfficeThumbnail(modifier = Modifier.fillMaxSize())
        Box(
            modifier = Modifier
                .fillMaxSize()
                .background(
                    Brush.verticalGradient(
                        listOf(
                            Color.Transparent,
                            Color(0xCC0F1115),
                            Color(0xF20F1115),
                        ),
                    ),
                ),
        )
        Column(
            modifier = Modifier
                .align(Alignment.BottomStart)
                .padding(18.dp),
        ) {
            Pill(
                text = if (running) "Autonomy live" else "Autonomy resting",
                color = if (running) MaterialTheme.colorScheme.secondary else MaterialTheme.colorScheme.outline,
            )
            Spacer(Modifier.height(8.dp))
            Text(
                text = "Welcome back, $userName.",
                style = MaterialTheme.typography.headlineSmall,
                color = Color.White,
            )
            Text(
                text = if (running && currentAgent != null) {
                    "$currentAgent is thinking right now."
                } else {
                    "$officeName is waiting for its next idea."
                },
                style = MaterialTheme.typography.bodyMedium,
                color = Color(0xFFD7DCE7),
            )
            Spacer(Modifier.height(10.dp))
            Row(verticalAlignment = Alignment.CenterVertically) {
                Switch(checked = autonomyEnabled, onCheckedChange = onToggle)
                Spacer(Modifier.width(8.dp))
                Text(
                    text = if (autonomyEnabled) "Agents are free to act" else "Agents are paused",
                    style = MaterialTheme.typography.labelMedium,
                    color = Color(0xFFE9ECF2),
                )
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
    Card(
        modifier = Modifier.fillMaxWidth(),
        shape = MaterialTheme.shapes.large,
        colors = CardDefaults.cardColors(
            containerColor = MaterialTheme.colorScheme.surfaceVariant,
        ),
    ) {
        Column(Modifier.padding(16.dp)) {
            Row(verticalAlignment = Alignment.CenterVertically) {
                Icon(
                    Icons.Default.AutoAwesome,
                    contentDescription = null,
                    tint = MaterialTheme.colorScheme.primary,
                )
                Spacer(Modifier.width(8.dp))
                Text(
                    text = if (ticking) "The office is thinking" else if (running) "The office is listening" else "The office is paused",
                    style = MaterialTheme.typography.titleMedium,
                    modifier = Modifier.weight(1f),
                )
                if (running) {
                    Pill(text = "Live", color = MaterialTheme.colorScheme.secondary)
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
                color = MaterialTheme.colorScheme.onSurfaceVariant,
                maxLines = 2,
                overflow = TextOverflow.Ellipsis,
            )
            if (lastLog != null) {
                Spacer(Modifier.height(4.dp))
                Text(
                    text = lastLog,
                    style = MaterialTheme.typography.labelSmall,
                    color = MaterialTheme.colorScheme.outline,
                    maxLines = 1,
                    overflow = TextOverflow.Ellipsis,
                )
            }
            Spacer(Modifier.height(12.dp))
            Row(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                Button(onClick = onRunNow) {
                    Icon(Icons.Default.PlayArrow, contentDescription = null)
                    Spacer(Modifier.width(6.dp))
                    Text("Run one cycle")
                }
                TextButton(onClick = onNudge) {
                    Icon(Icons.Default.Bolt, contentDescription = null)
                    Spacer(Modifier.width(6.dp))
                    Text("Nudge everyone")
                }
            }
        }
    }
}

@OptIn(ExperimentalMaterial3Api::class)
@Composable
private fun DiaryRow(
    entry: DiaryEntryEntity,
    agent: AgentEntity?,
    onClick: () -> Unit,
) {
    Surface(
        onClick = onClick,
        modifier = Modifier.fillMaxWidth(),
        shape = MaterialTheme.shapes.medium,
        color = MaterialTheme.colorScheme.surfaceVariant,
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
                        .clip(RoundedCornerShape(50))
                        .background(MaterialTheme.colorScheme.surfaceContainerHigh),
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
                    Pill(text = entry.kind)
                    Spacer(Modifier.weight(1f))
                    Text(
                        text = Time.relativeTime(entry.createdAt),
                        style = MaterialTheme.typography.labelSmall,
                        color = MaterialTheme.colorScheme.onSurfaceVariant,
                    )
                }
                Spacer(Modifier.height(2.dp))
                Text(
                    text = entry.content,
                    style = MaterialTheme.typography.bodySmall,
                    color = MaterialTheme.colorScheme.onSurfaceVariant,
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

    AlertDialog(
        onDismissRequest = onDismiss,
        title = { Text("Hire an agent") },
        text = {
            Column(verticalArrangement = Arrangement.spacedBy(8.dp)) {
                OutlinedTextField(
                    value = name,
                    onValueChange = { name = it },
                    label = { Text("Name") },
                    singleLine = true,
                )
                OutlinedTextField(
                    value = role,
                    onValueChange = { role = it },
                    label = { Text("Role") },
                    singleLine = true,
                )
                OutlinedTextField(
                    value = persona,
                    onValueChange = { persona = it },
                    label = { Text("Persona (optional)") },
                    minLines = 2,
                )
                OutlinedTextField(
                    value = goal,
                    onValueChange = { goal = it },
                    label = { Text("Goal (optional)") },
                    minLines = 2,
                )
            }
        },
        confirmButton = {
            TextButton(
                onClick = {
                    if (name.isNotBlank()) onCreate(name, role, persona, goal)
                },
            ) { Text("Create") }
        },
        dismissButton = {
            TextButton(onClick = onDismiss) { Text("Cancel") }
        },
    )
}