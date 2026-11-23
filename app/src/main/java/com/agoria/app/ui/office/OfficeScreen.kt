package com.agoria.app.ui.office

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
import androidx.compose.foundation.lazy.grid.GridCells
import androidx.compose.foundation.lazy.grid.LazyVerticalGrid
import androidx.compose.foundation.lazy.grid.items
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.AutoAwesome
import androidx.compose.material3.ExperimentalMaterial3Api
import androidx.compose.material3.Icon
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Scaffold
import androidx.compose.material3.Text
import androidx.compose.material3.TopAppBar
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.text.font.FontStyle
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import com.agoria.app.data.AgentEntity
import com.agoria.app.ui.AgoriaViewModel
import com.agoria.app.ui.components.AgentAvatar
import com.agoria.app.ui.components.Pill
import com.agoria.app.ui.components.StatusPill
import com.agoria.app.util.Time
import com.agoria.app.ui.office.world.Pal
import com.agoria.app.ui.office.world.PixelCard
import com.agoria.app.ui.office.world.PixelPanel
import com.agoria.app.ui.office.world.PixelPill
import com.agoria.app.ui.components.PixelScaffold

@OptIn(ExperimentalMaterial3Api::class)
@Composable
fun OfficeScreen(
    viewModel: AgoriaViewModel,
    onOpenAgent: (String) -> Unit,
) {
    val agents by viewModel.agents.collectAsStateWithLifecycle()
    val diary by viewModel.diary.collectAsStateWithLifecycle()
    val latest = diary.firstOrNull()

    PixelScaffold(
        title = "The office",
        subtitle = "${agents.size} agents · tap a desk to talk",
        onBack = {},
    ) { padding ->
        Column(
            modifier = Modifier
                .fillMaxSize()
                .padding(padding),
        ) {
            if (latest != null) {
                val agent = agents.firstOrNull { it.id == latest.agentId }
                PixelCard(
                    modifier = Modifier
                        .fillMaxWidth()
                        .padding(horizontal = 16.dp, vertical = 8.dp),
                    fill = Pal.teal.copy(alpha = 0.15f),
                ) {
                    Row(
                        modifier = Modifier.padding(14.dp),
                        verticalAlignment = Alignment.Top,
                    ) {
                        Icon(
                            AutoAwesome,
                            contentDescription = null,
                            tint = Pal.teal,
                        )
                        Spacer(Modifier.width(10.dp))
                        Column(Modifier.weight(1f)) {
                            Text(
                                text = "${agent?.name ?: "Office"} is thinking",
                                style = MaterialTheme.typography.labelLarge,
                                color = Pal.ink,
                            )
                            Text(
                                text = latest.content,
                                style = MaterialTheme.typography.bodySmall,
                                color = MaterialTheme.colorScheme.onSurfaceVariant,
                                maxLines = 3,
                                overflow = TextOverflow.Ellipsis,
                            )
                        }
                        Spacer(Modifier.width(8.dp))
                        Text(
                            text = Time.relativeTime(latest.createdAt),
                            style = MaterialTheme.typography.labelSmall,
                            color = MaterialTheme.colorScheme.onSurfaceVariant,
                        )
                    }
                }
            }

            LazyVerticalGrid(
                columns = GridCells.Fixed(2),
                modifier = Modifier.weight(1f),
                contentPadding = PaddingValues(16.dp),
                horizontalArrangement = Arrangement.spacedBy(12.dp),
                verticalArrangement = Arrangement.spacedBy(12.dp),
            ) {
                items(agents, key = { it.id }) { agent ->
                    AgentDesk(
                        agent = agent,
                        latestDiary = diary.firstOrNull { it.agentId == agent.id }?.content,
                        onClick = { onOpenAgent(agent.id) },
                    )
                }
            }
        }
    }
}

@Composable
private fun AgentDesk(
    agent: AgentEntity,
    latestDiary: String?,
    onClick: () -> Unit,
) {
    PixelPanel(
        onClick = onClick,
        modifier = Modifier.fillMaxWidth(),
        fill = Pal.cream,
    ) {
        Column(Modifier.padding(14.dp)) {
            Row(verticalAlignment = Alignment.CenterVertically) {
                AgentAvatar(agent, 46.dp)
                Spacer(Modifier.width(10.dp))
                Column(Modifier.weight(1f)) {
                    Text(agent.name, style = MaterialTheme.typography.titleMedium)
                    Text(
                        agent.role,
                        style = MaterialTheme.typography.labelSmall,
                        color = MaterialTheme.colorScheme.onSurfaceVariant,
                    )
                }
            }
            Spacer(Modifier.height(10.dp))
            StatusPill(agent.status)
            Spacer(Modifier.height(8.dp))
            Text(
                text = latestDiary ?: agent.goal,
                style = MaterialTheme.typography.bodySmall,
                color = MaterialTheme.colorScheme.onSurfaceVariant,
                fontStyle = FontStyle.Italic,
                maxLines = 3,
                overflow = TextOverflow.Ellipsis,
            )
            Spacer(Modifier.height(8.dp))
            Row(verticalAlignment = Alignment.CenterVertically) {
                PixelPill(text = "Talk")
                if (agent.status == "waiting_approval") {
                    Spacer(Modifier.width(6.dp))
                    PixelPill(text = "Approval", fill = MaterialTheme.colorScheme.tertiary.copy(alpha = 0.14f), textColor = MaterialTheme.colorScheme.tertiary)
                }
            }
        }
    }
}