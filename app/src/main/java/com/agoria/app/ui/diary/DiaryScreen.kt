package com.agoria.app.ui.diary

import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.PaddingValues
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.LazyRow
import androidx.compose.foundation.lazy.items
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.Search
import androidx.compose.material3.ExperimentalMaterial3Api
import androidx.compose.material3.FilterChip
import androidx.compose.material3.Icon
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedTextField
import androidx.compose.material3.Scaffold
import androidx.compose.material3.Surface
import androidx.compose.material3.Text
import androidx.compose.material3.TopAppBar
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import com.agoria.app.ui.AgoriaViewModel
import com.agoria.app.ui.components.AgentAvatar
import com.agoria.app.ui.components.EmptyState
import com.agoria.app.ui.components.Pill
import com.agoria.app.util.Time

@OptIn(ExperimentalMaterial3Api::class)
@Composable
fun DiaryScreen(
    viewModel: AgoriaViewModel,
    onOpenAgent: (String) -> Unit,
) {
    val agents by viewModel.agents.collectAsStateWithLifecycle()
    val diary by viewModel.diary.collectAsStateWithLifecycle()
    var selectedAgent by remember { mutableStateOf<String?>(null) }
    var query by remember { mutableStateOf("") }

    val filtered = diary.filter { entry ->
        (selectedAgent == null || entry.agentId == selectedAgent) &&
            (query.isBlank() || entry.content.contains(query, ignoreCase = true) || entry.kind.contains(query, ignoreCase = true))
    }

    Scaffold(
        topBar = {
            TopAppBar(
                title = {
                    Column {
                        Text("Diary", style = MaterialTheme.typography.titleLarge)
                        Text(
                            text = "${diary.size} entries · private thoughts, plans, and lessons",
                            style = MaterialTheme.typography.labelSmall,
                            color = MaterialTheme.colorScheme.onSurfaceVariant,
                        )
                    }
                },
            )
        },
    ) { padding ->
        Column(
            modifier = Modifier
                .fillMaxSize()
                .padding(padding),
        ) {
            OutlinedTextField(
                value = query,
                onValueChange = { query = it },
                modifier = Modifier
                    .fillMaxWidth()
                    .padding(horizontal = 16.dp, vertical = 8.dp),
                placeholder = { Text("Search thoughts, plans, decisions...") },
                leadingIcon = { Icon(Search, contentDescription = null) },
                singleLine = true,
            )

            LazyRow(
                contentPadding = PaddingValues(horizontal = 16.dp, vertical = 4.dp),
                horizontalArrangement = Arrangement.spacedBy(8.dp),
            ) {
                item {
                    FilterChip(
                        selected = selectedAgent == null,
                        onClick = { selectedAgent = null },
                        label = { Text("Everyone") },
                    )
                }
                items(agents, key = { it.id }) { agent ->
                    FilterChip(
                        selected = selectedAgent == agent.id,
                        onClick = { selectedAgent = agent.id },
                        label = { Text(agent.name) },
                    )
                }
            }

            if (filtered.isEmpty()) {
                EmptyState(
                    title = "No diary entries",
                    body = if (diary.isEmpty()) {
                        "Start autonomy and the agents will begin writing. Their private thoughts will appear here."
                    } else {
                        "Try another agent or a different search."
                    },
                )
            } else {
                LazyColumn(
                    modifier = Modifier.weight(1f),
                    contentPadding = PaddingValues(16.dp),
                    verticalArrangement = Arrangement.spacedBy(10.dp),
                ) {
                    filtered.forEachIndexed { index, entry ->
                        val day = Time.dayLabel(entry.createdAt)
                        val previousDay = if (index == 0) null else Time.dayLabel(filtered[index - 1].createdAt)
                        if (day != previousDay) {
                            item(key = "day-$day-$index") {
                                Text(
                                    text = day,
                                    style = MaterialTheme.typography.labelMedium,
                                    color = MaterialTheme.colorScheme.primary,
                                    modifier = Modifier.padding(top = 8.dp, bottom = 2.dp),
                                )
                            }
                        }
                        item(key = entry.id) {
                            val agent = agents.firstOrNull { it.id == entry.agentId }
                            DiaryEntryCard(
                                entry = entry,
                                agentName = agent?.name ?: "Office",
                                avatarAgent = agent,
                                onClick = { onOpenAgent(entry.agentId) },
                            )
                        }
                    }
                }
            }
        }
    }
}

@OptIn(ExperimentalMaterial3Api::class)
@Composable
private fun DiaryEntryCard(
    entry: com.agoria.app.data.DiaryEntryEntity,
    agentName: String,
    avatarAgent: com.agoria.app.data.AgentEntity?,
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
            if (avatarAgent != null) {
                AgentAvatar(avatarAgent, 38.dp, showStatus = false)
            }
            Spacer(Modifier.width(10.dp))
            Column(Modifier.weight(1f)) {
                Row(verticalAlignment = Alignment.CenterVertically) {
                    Text(agentName, style = MaterialTheme.typography.labelLarge)
                    Spacer(Modifier.width(6.dp))
                    Pill(text = entry.kind)
                    Spacer(Modifier.weight(1f))
                    Text(
                        text = Time.clock(entry.createdAt),
                        style = MaterialTheme.typography.labelSmall,
                        color = MaterialTheme.colorScheme.onSurfaceVariant,
                    )
                }
                Spacer(Modifier.height(4.dp))
                Text(
                    text = entry.content,
                    style = MaterialTheme.typography.bodyMedium,
                    maxLines = 12,
                    overflow = TextOverflow.Ellipsis,
                )
                Spacer(Modifier.height(6.dp))
                Text(
                    text = "★".repeat(entry.importance.coerceIn(1, 5)) + " importance",
                    style = MaterialTheme.typography.labelSmall,
                    color = MaterialTheme.colorScheme.primary,
                )
            }
        }
    }
}