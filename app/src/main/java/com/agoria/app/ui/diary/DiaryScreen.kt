package com.agoria.app.ui.diary

import androidx.compose.foundation.clickable
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
import androidx.compose.ui.semantics.Role
import androidx.compose.ui.semantics.clearAndSetSemantics
import androidx.compose.ui.semantics.contentDescription
import androidx.compose.ui.semantics.onClick
import androidx.compose.ui.semantics.role
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import com.agoria.app.ui.AgoriaViewModel
import com.agoria.app.ui.components.AgentAvatar
import com.agoria.app.ui.components.EmptyState
import com.agoria.app.ui.components.Pill
import com.agoria.app.ui.components.PixelScaffold
import com.agoria.app.ui.office.world.Pal
import com.agoria.app.ui.office.world.PixelButton
import com.agoria.app.ui.office.world.PixelPanel
import com.agoria.app.ui.office.world.PixelTextField
import com.agoria.app.util.Time

@Composable
fun DiaryScreen(
    viewModel: AgoriaViewModel,
    onOpenAgent: (String) -> Unit,
    onBack: () -> Unit,
) {
    val agents by viewModel.agents.collectAsStateWithLifecycle()
    val diary by viewModel.diary.collectAsStateWithLifecycle()
    var selectedAgent by remember { mutableStateOf<String?>(null) }
    var query by remember { mutableStateOf("") }

    val filtered = diary.filter { entry ->
        (selectedAgent == null || entry.agentId == selectedAgent) &&
            (query.isBlank() || entry.content.contains(query, ignoreCase = true) || entry.kind.contains(query, ignoreCase = true))
    }

    PixelScaffold(
        title = "Diary",
        subtitle = "${diary.size} entries · private thoughts, plans, and lessons",
        onBack = onBack,
    ) { padding ->
        Column(
            modifier = Modifier
                .fillMaxSize()
                .padding(padding),
        ) {
            PixelTextField(
                value = query,
                onValueChange = { query = it },
                modifier = Modifier
                    .fillMaxWidth()
                    .padding(horizontal = 16.dp, vertical = 8.dp),
                label = "Search",
                hint = "Search thoughts, plans, decisions...",
                singleLine = true,
                labelColor = Pal.navy,
                hintColor = Pal.tan,
            )

            LazyRow(
                contentPadding = PaddingValues(horizontal = 16.dp, vertical = 4.dp),
                horizontalArrangement = Arrangement.spacedBy(8.dp),
            ) {
                item {
                    val selected = selectedAgent == null
                    PixelButton(
                        text = "Everyone",
                        onClick = { selectedAgent = null },
                        small = true,
                        fill = if (selected) Pal.amber else Pal.tan,
                    )
                }
                items(agents, key = { it.id }) { agent ->
                    val selected = selectedAgent == agent.id
                    PixelButton(
                        text = agent.name,
                        onClick = { selectedAgent = agent.id },
                        small = true,
                        fill = if (selected) Pal.amber else Pal.tan,
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
                                    color = Pal.amber,
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
                                onClickAction = { onOpenAgent(entry.agentId) },
                            )
                        }
                    }
                }
            }
        }
    }
}

@Composable
private fun DiaryEntryCard(
    entry: com.agoria.app.data.DiaryEntryEntity,
    agentName: String,
    avatarAgent: com.agoria.app.data.AgentEntity?,
    onClickAction: () -> Unit,
) {
    val open = onClickAction
    val description = buildString {
        append(agentName).append(", ").append(entry.kind).append(", ")
        append(Time.clock(entry.createdAt)).append(". ")
        append(entry.content.take(160)).append(". ")
        append(entry.importance).append(" importance")
    }
    PixelPanel(
        modifier = Modifier
            .fillMaxWidth()
            .clickable(onClick = onClickAction)
            .clearAndSetSemantics {
                contentDescription = description
                role = Role.Button
                onClick(label = "Open diary entry") { open(); true }
            },
        fill = Pal.cream,
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
                        color = Pal.tan,
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
                    color = Pal.amber,
                )
            }
        }
    }
}
