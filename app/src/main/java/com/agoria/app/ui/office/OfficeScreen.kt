package com.agoria.app.ui.office

import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.AutoAwesome
import androidx.compose.material3.Card
import androidx.compose.material3.CardDefaults
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
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import com.agoria.app.data.DiaryEntryEntity
import com.agoria.app.ui.AgoriaViewModel
import com.agoria.app.util.Time

@OptIn(ExperimentalMaterial3Api::class)
@Composable
fun OfficeScreen(
    viewModel: AgoriaViewModel,
    onOpenAgent: (String) -> Unit,
) {
    val agents by viewModel.agents.collectAsStateWithLifecycle()
    val diary by viewModel.diary.collectAsStateWithLifecycle()
    val settings by viewModel.settings.collectAsStateWithLifecycle()
    val latest = diary.firstOrNull()
    val autonomyEnabled = settings?.autonomyEnabled ?: false

    Scaffold(
        topBar = {
            TopAppBar(
                title = {
                    Column {
                        Text("The office", style = MaterialTheme.typography.titleLarge)
                        Text(
                            text = buildString {
                                append("${agents.size} agents")
                                append(" · ")
                                append(if (autonomyEnabled) "live" else "paused")
                                append(" · tap someone to talk")
                            },
                            style = MaterialTheme.typography.labelSmall,
                            color = MaterialTheme.colorScheme.onSurfaceVariant,
                        )
                    }
                },
            )
        },
    ) { padding ->
        Box(
            modifier = Modifier
                .fillMaxSize()
                .padding(padding),
        ) {
            OfficeScene(
                agents = agents,
                autonomyEnabled = autonomyEnabled,
                onOpenAgent = onOpenAgent,
                modifier = Modifier.fillMaxSize(),
            )

            if (latest != null) {
                LatestThoughtCard(
                    latest = latest,
                    agents = agents,
                    modifier = Modifier
                        .align(Alignment.BottomCenter)
                        .fillMaxWidth()
                        .padding(12.dp),
                )
            }
        }
    }
}

@Composable
private fun LatestThoughtCard(
    latest: DiaryEntryEntity,
    agents: List<com.agoria.app.data.AgentEntity>,
    modifier: Modifier = Modifier,
) {
    val agent = agents.firstOrNull { it.id == latest.agentId }
    Card(
        modifier = modifier,
        shape = RoundedCornerShape(18.dp),
        colors = CardDefaults.cardColors(
            containerColor = MaterialTheme.colorScheme.surfaceVariant.copy(alpha = 0.94f),
        ),
        elevation = CardDefaults.cardElevation(defaultElevation = 6.dp),
    ) {
        Row(
            modifier = Modifier.padding(12.dp),
            verticalAlignment = Alignment.Top,
        ) {
            Icon(
                Icons.Default.AutoAwesome,
                contentDescription = null,
                tint = MaterialTheme.colorScheme.primary,
            )
            Spacer(Modifier.width(10.dp))
            Column(Modifier.weight(1f)) {
                Text(
                    text = "${agent?.name ?: "Office"} is thinking",
                    style = MaterialTheme.typography.labelLarge,
                )
                Text(
                    text = latest.content,
                    style = MaterialTheme.typography.bodySmall,
                    color = MaterialTheme.colorScheme.onSurfaceVariant,
                    maxLines = 2,
                    overflow = TextOverflow.Ellipsis,
                )
            }
            Text(
                text = Time.relativeTime(latest.createdAt),
                style = MaterialTheme.typography.labelSmall,
                color = MaterialTheme.colorScheme.onSurfaceVariant,
            )
        }
    }
}