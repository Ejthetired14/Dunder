package com.agoria.app.ui.office

import androidx.compose.foundation.background
import androidx.compose.foundation.gestures.detectTapGestures
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
import androidx.compose.foundation.layout.statusBarsPadding
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.lazy.grid.GridCells
import androidx.compose.foundation.lazy.grid.LazyVerticalGrid
import androidx.compose.foundation.lazy.grid.items
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.Public
import androidx.compose.material.icons.filled.MyLocation
import androidx.compose.material.icons.automirrored.filled.List
import androidx.compose.material.icons.filled.Close
import androidx.compose.material.icons.filled.Map
import androidx.compose.material.icons.filled.AutoAwesome
import androidx.compose.material3.Card
import androidx.compose.material3.CardDefaults
import androidx.compose.material3.ExperimentalMaterial3Api
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Scaffold
import androidx.compose.material3.Surface
import androidx.compose.material3.Text
import androidx.compose.material3.TopAppBar
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.saveable.rememberSaveable
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.input.pointer.pointerInput
import androidx.compose.ui.text.font.FontStyle
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import com.agoria.app.data.AgentEntity
import com.agoria.app.ui.AgoriaViewModel
import com.agoria.app.ui.components.AgentAvatar
import com.agoria.app.ui.components.Pill
import com.agoria.app.ui.components.StatusPill
import com.agoria.app.ui.components.statusLabel
import com.agoria.app.ui.office.world.Activity
import com.agoria.app.ui.office.world.CameraState
import com.agoria.app.ui.office.world.OfficeSim
import com.agoria.app.ui.office.world.OfficeWorld
import com.agoria.app.ui.office.world.Pal
import com.agoria.app.ui.office.world.PixelBody
import com.agoria.app.ui.office.world.PixelButton
import com.agoria.app.ui.office.world.PixelIconButton
import com.agoria.app.ui.office.world.PixelLabel
import com.agoria.app.ui.office.world.PixelPanel
import com.agoria.app.ui.office.world.statusText
import com.agoria.app.util.Time
import kotlin.math.roundToInt

/**
 * The office is a pixel-art world by default. The old card grid stays available as a list view
 * (also the accessible fallback, since a canvas cannot be read by TalkBack).
 */
@Composable
fun OfficeScreen(
    viewModel: AgoriaViewModel,
    onOpenAgent: (String) -> Unit,
    onOpenBrowsers: () -> Unit = {},
    onOpenApprovals: () -> Unit = {},
) {
    // Hoist OfficeSim to OfficeScreen level so it survives listMode toggle and navigation
    val sim = remember { OfficeSim() }
    val camera = remember { CameraState() }

    var listMode by rememberSaveable { mutableStateOf(false) }
    if (listMode) {
        OfficeListScreen(
            viewModel = viewModel,
            onOpenAgent = onOpenAgent,
            onOpenBrowsers = onOpenBrowsers,
            onShowMap = { listMode = false },
        )
    } else {
        OfficeWorldScreen(
            sim = sim,
            camera = camera,
            viewModel = viewModel,
            onOpenAgent = onOpenAgent,
            onOpenBrowsers = onOpenBrowsers,
            onOpenApprovals = onOpenApprovals,
            onShowList = { listMode = true },
        )
    }
}

@Composable
private fun OfficeWorldScreen(
    sim: OfficeSim,
    camera: CameraState,
    viewModel: AgoriaViewModel,
    onOpenAgent: (String) -> Unit,
    onOpenBrowsers: () -> Unit,
    onOpenApprovals: () -> Unit,
    onShowList: () -> Unit,
) {
    val agents by viewModel.agents.collectAsStateWithLifecycle()
    val diary by viewModel.diary.collectAsStateWithLifecycle()
    val pending by viewModel.pendingCount.collectAsStateWithLifecycle()

    var selectedId by remember { mutableStateOf<String?>(null) }
    var debug by remember { mutableStateOf(false) }
    var debugTap by remember { mutableStateOf<Offset?>(null) }

    LaunchedEffect(agents) { sim.sync(agents) }
    LaunchedEffect(selectedId) { sim.holdId = selectedId }

    val selectedAgent = agents.firstOrNull { it.id == selectedId }
    val actor = sim.actors.firstOrNull { it.agentId == selectedId }
    // Reading sim.revision here makes the card refresh when the agent changes activity.
    val statusLine = remember(sim.revision, selectedId) { actor?.statusText() }
    val latestDiary = diary.firstOrNull { it.agentId == selectedId }?.content

    Box(
        modifier = Modifier
            .fillMaxSize()
            .background(Pal.ink),
    ) {
        OfficeWorld(
            sim = sim,
            camera = camera,
            selectedId = selectedId,
            debug = debug,
            onSelect = { selectedId = it },
            onDeskTap = onOpenApprovals,
            onDebugTap = { debugTap = it },
            modifier = Modifier.fillMaxSize(),
        )

        // Top HUD
        Row(
            modifier = Modifier
                .fillMaxWidth()
                .statusBarsPadding()
                .padding(10.dp),
            horizontalArrangement = Arrangement.SpaceBetween,
            verticalAlignment = Alignment.Top,
        ) {
            Column {
                PixelPanel(
                    modifier = Modifier.pointerInput(Unit) {
                        detectTapGestures(onLongPress = {
                            debug = !debug
                            debugTap = null
                        })
                    },
                ) {
                    Column(Modifier.padding(horizontal = 12.dp, vertical = 8.dp)) {
                        Text("THE OFFICE", style = PixelLabel)
                        Text(
                            text = "${agents.size} agents · demo mode",
                            style = PixelBody.copy(fontSize = 11.sp, lineHeight = 14.sp),
                        )
                    }
                }
                if (pending > 0) {
                    Spacer(Modifier.height(8.dp))
                    PixelButton(text = "! $pending to approve", onClick = onOpenApprovals)
                }
            }
            Row(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                PixelIconButton(Icons.Default.Public, "Agent browsers", onOpenBrowsers)
                PixelIconButton(Icons.Default.MyLocation, "Reset view", { camera.reset() })
                PixelIconButton(Icons.AutoMirrored.Filled.List, "List view", onShowList)
            }
        }

        if (debug) {
            PixelPanel(
                modifier = Modifier
                    .align(Alignment.CenterStart)
                    .padding(start = 10.dp),
            ) {
                val tap = debugTap
                Column(Modifier.padding(horizontal = 10.dp, vertical = 6.dp)) {
                    Text("DEBUG", style = PixelLabel)
                    Text(
                        text = if (tap == null) "tap the map" else "x=${tap.x.roundToInt()}  y=${tap.y.roundToInt()}",
                        style = PixelBody.copy(fontSize = 12.sp),
                    )
                }
            }
        }

        // Selected agent card
        if (selectedAgent != null) {
            Box(
                modifier = Modifier
                    .align(Alignment.BottomCenter)
                    .fillMaxWidth()
                    .padding(12.dp),
            ) {
                PixelPanel(modifier = Modifier.fillMaxWidth()) {
                    Column(Modifier.padding(12.dp)) {
                        Row(verticalAlignment = Alignment.CenterVertically) {
                            AgentAvatar(selectedAgent, 44.dp)
                            Spacer(Modifier.width(10.dp))
                            Column(Modifier.weight(1f)) {
                                Text(selectedAgent.name.uppercase(), style = PixelLabel)
                                Text(selectedAgent.role, style = PixelBody.copy(fontSize = 12.sp))
                            }
                            PixelIconButton(Icons.Default.Close, "Close", { selectedId = null })
                        }
                        Spacer(Modifier.height(8.dp))
                        Text(
                            text = statusLine ?: statusLabel(selectedAgent.status),
                            style = PixelBody.copy(fontWeight = FontWeight.SemiBold),
                        )
                        if (!latestDiary.isNullOrBlank()) {
                            Spacer(Modifier.height(4.dp))
                            Text(
                                text = "“${latestDiary.take(140)}”",
                                style = PixelBody.copy(
                                    fontStyle = FontStyle.Italic,
                                    color = Color(0xFF4A4338),
                                ),
                                maxLines = 3,
                                overflow = TextOverflow.Ellipsis,
                            )
                        }
                        Spacer(Modifier.height(10.dp))
                        Row(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                            PixelButton(
                                text = "Diary & chat",
                                onClick = { onOpenAgent(selectedAgent.id) },
                            )
                            if (actor?.activity == Activity.APPROVAL) {
                                PixelButton(
                                    text = "Approvals",
                                    onClick = onOpenApprovals,
                                    fill = Pal.cream,
                                )
                            }
                        }
                    }
                }
            }
        }
    }
}

// ---------------------------------------------------------------------------------------------
// List view: the previous card grid, unchanged apart from a button to go back to the map.
// ---------------------------------------------------------------------------------------------

@OptIn(ExperimentalMaterial3Api::class)
@Composable
private fun OfficeListScreen(
    viewModel: AgoriaViewModel,
    onOpenAgent: (String) -> Unit,
    onOpenBrowsers: () -> Unit,
    onShowMap: () -> Unit,
) {
    val agents by viewModel.agents.collectAsStateWithLifecycle()
    val diary by viewModel.diary.collectAsStateWithLifecycle()
    val latest = diary.firstOrNull()

    Scaffold(
        topBar = {
            TopAppBar(
                title = {
                    Column {
                        Text("The office", style = MaterialTheme.typography.titleLarge)
                        Text(
                            text = "${agents.size} agents · tap a desk to talk",
                            style = MaterialTheme.typography.labelSmall,
                            color = MaterialTheme.colorScheme.onSurfaceVariant,
                        )
                    }
                },
                actions = {
                    IconButton(onClick = onShowMap) {
                        Icon(
                            Icons.Default.Map,
                            contentDescription = "Map view",
                        )
                    }
                    IconButton(onClick = onOpenBrowsers) {
                        Icon(
                            Icons.Default.Public,
                            contentDescription = "Agent browsers",
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
            if (latest != null) {
                val agent = agents.firstOrNull { it.id == latest.agentId }
                Card(
                    modifier = Modifier
                        .fillMaxWidth()
                        .padding(horizontal = 16.dp, vertical = 8.dp),
                    shape = MaterialTheme.shapes.large,
                    colors = CardDefaults.cardColors(
                        containerColor = MaterialTheme.colorScheme.primaryContainer,
                    ),
                ) {
                    Row(
                        modifier = Modifier.padding(14.dp),
                        verticalAlignment = Alignment.Top,
                    ) {
                        Icon(
                            Icons.Default.AutoAwesome,
                            contentDescription = null,
                            tint = MaterialTheme.colorScheme.onPrimaryContainer,
                        )
                        Spacer(Modifier.width(10.dp))
                        Column(Modifier.weight(1f)) {
                            Text(
                                text = "${agent?.name ?: "Office"} is thinking",
                                style = MaterialTheme.typography.labelLarge,
                                color = MaterialTheme.colorScheme.onPrimaryContainer,
                            )
                            Text(
                                text = latest.content,
                                style = MaterialTheme.typography.bodySmall,
                                color = MaterialTheme.colorScheme.onPrimaryContainer,
                                maxLines = 3,
                                overflow = TextOverflow.Ellipsis,
                            )
                        }
                        Spacer(Modifier.width(8.dp))
                        Text(
                            text = Time.relativeTime(latest.createdAt),
                            style = MaterialTheme.typography.labelSmall,
                            color = MaterialTheme.colorScheme.onPrimaryContainer,
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

@OptIn(ExperimentalMaterial3Api::class)
@Composable
private fun AgentDesk(
    agent: AgentEntity,
    latestDiary: String?,
    onClick: () -> Unit,
) {
    Surface(
        onClick = onClick,
        modifier = Modifier.fillMaxWidth(),
        shape = RoundedCornerShape(22.dp),
        color = MaterialTheme.colorScheme.surface,
        shadowElevation = 2.dp,
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
                Pill(text = "Talk")
                if (agent.status == "waiting_approval") {
                    Spacer(Modifier.width(6.dp))
                    Pill(text = "Approval", color = MaterialTheme.colorScheme.tertiary)
                }
            }
        }
    }
}
