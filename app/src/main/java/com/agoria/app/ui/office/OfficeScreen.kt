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
import androidx.compose.material.icons.automirrored.filled.List
import androidx.compose.material.icons.filled.AutoAwesome
import androidx.compose.material.icons.filled.Close
import androidx.compose.material.icons.filled.Forum
import androidx.compose.material.icons.filled.Map
import androidx.compose.material.icons.filled.MyLocation
import androidx.compose.material.icons.filled.Public
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
import androidx.compose.runtime.mutableIntStateOf
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.saveable.rememberSaveable
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.input.pointer.PointerEventPass
import androidx.compose.ui.input.pointer.pointerInput
import androidx.compose.ui.text.font.FontStyle
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import androidx.lifecycle.viewmodel.compose.viewModel
import androidx.navigation.NavHostController
import kotlinx.coroutines.delay
import com.agoria.app.data.AgentEntity
import com.agoria.app.ui.AgoriaViewModel
import com.agoria.app.ui.components.AgentAvatar
import com.agoria.app.ui.components.Pill
import com.agoria.app.ui.components.StatusPill
import com.agoria.app.ui.components.statusLabel
import com.agoria.app.ui.office.world.CameraState
import com.agoria.app.ui.office.world.ChatterPanel
import com.agoria.app.ui.office.world.DeskPanel
import com.agoria.app.ui.office.world.HotspotDef
import com.agoria.app.ui.office.world.OfficeSim
import com.agoria.app.ui.office.world.OfficeWorld
import com.agoria.app.ui.office.world.Pal
import com.agoria.app.ui.office.world.PixelBody
import com.agoria.app.ui.office.world.PixelButton
import com.agoria.app.ui.office.world.PixelIconButton
import com.agoria.app.ui.office.world.PixelLabel
import com.agoria.app.ui.office.world.PixelPanel
import com.agoria.app.ui.office.world.buildSignals
import com.agoria.app.ui.office.world.parseChatter
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
    navController: NavHostController,
    onOpenAgent: (String) -> Unit,
    onOpenBrowsers: () -> Unit = {},
    onOpenApprovals: () -> Unit = {},
) {
    // Scope OfficeViewModel to the root nav graph so it survives tab switching
    val rootEntry = navController.getBackStackEntry("home")
    val officeViewModel: OfficeViewModel = viewModel(rootEntry)
    val sim = officeViewModel.sim
    val camera = officeViewModel.camera

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
            onNavigate = { route -> navController.navigate(route) },
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
    onNavigate: (String) -> Unit,
    onShowList: () -> Unit,
) {
    val agents by viewModel.agents.collectAsStateWithLifecycle()
    val diary by viewModel.diary.collectAsStateWithLifecycle()
    val pending by viewModel.pendingCount.collectAsStateWithLifecycle()
    val pendingApprovals by viewModel.pendingApprovals.collectAsStateWithLifecycle()
    val pendingRequests by viewModel.pendingConnectorRequests.collectAsStateWithLifecycle()
    val logs by viewModel.connectorLogs.collectAsStateWithLifecycle()
    val builds by viewModel.builds.collectAsStateWithLifecycle()
    val settings by viewModel.settings.collectAsStateWithLifecycle()

    var selectedId by remember { mutableStateOf<String?>(null) }
    var hotspot by remember { mutableStateOf<HotspotDef?>(null) }
    var deskOpen by remember { mutableStateOf(false) }
    var chatterOpen by remember { mutableStateOf(false) }
    var debug by remember { mutableStateOf(false) }
    var debugTap by remember { mutableStateOf<Offset?>(null) }

    // Auto-hide top HUD. Every new touch bumps the tick, which restarts the countdown.
    val hudVisible = remember { mutableStateOf(true) }
    var hudActivityTick by remember { mutableIntStateOf(0) }
    val hudAutoHideDelay = 3000L

    // Live data: agents get bodies, then what they are really doing decides where they stand.
    // The tick re-checks every few seconds so "recently used" connectors age out on their own.
    var tick by remember { mutableIntStateOf(0) }
    LaunchedEffect(Unit) {
        while (true) {
            delay(4000)
            tick++
        }
    }
    LaunchedEffect(agents, pendingApprovals, pendingRequests, logs, builds, tick) {
        sim.sync(agents)
        sim.applySignals(buildSignals(agents, pendingApprovals, pendingRequests, logs, builds, Time.now()))
    }
    LaunchedEffect(selectedId) { sim.holdId = selectedId }

    // What agents say to each other. New messages are acted out in the world as they arrive.
    val chatter = remember(diary, agents) { parseChatter(diary, agents) }
    LaunchedEffect(chatter) { sim.onChatter(chatter, Time.now()) }

    val selectedAgent = agents.firstOrNull { it.id == selectedId }
    val actor = sim.actors.firstOrNull { it.agentId == selectedId }
    // Reading sim.revision here makes the card refresh when the agent changes activity.
    val statusLine = remember(sim.revision, selectedId) { actor?.statusText() }
    val latestDiary = diary.firstOrNull { it.agentId == selectedId }?.content

    LaunchedEffect(hudActivityTick) {
        hudVisible.value = true
        delay(hudAutoHideDelay)
        hudVisible.value = false
    }

    // The map and its agents consume taps, so listen on the Initial pass without consuming.
    val hudTouchDetector = Modifier
        .fillMaxSize()
        .pointerInput(Unit) {
            awaitPointerEventScope {
                var wasPressed = false
                while (true) {
                    val event = awaitPointerEvent(PointerEventPass.Initial)
                    val pressed = event.changes.any { it.pressed }
                    if (pressed && !wasPressed) hudActivityTick++
                    wasPressed = pressed
                }
            }
        }

    Box(
        modifier = Modifier
            .fillMaxSize()
            .background(Pal.ink)
            .then(hudTouchDetector),
    ) {
        OfficeWorld(
            sim = sim,
            camera = camera,
            selectedId = selectedId,
            selectedHotspotId = hotspot?.id,
            deskAlert = pending > 0,
            debug = debug,
            onSelect = {
                selectedId = it
                if (it != null) {
                    hotspot = null
                    deskOpen = false
                    chatterOpen = false
                }
            },
            onHotspot = { hs ->
                if (hs == null) {
                    hotspot = null
                } else if (hs.id == "desk") {
                    hotspot = null
                    selectedId = null
                    chatterOpen = false
                    deskOpen = true
                } else {
                    hotspot = hs
                    selectedId = null
                    deskOpen = false
                    chatterOpen = false
                }
            },
            onDebugTap = { debugTap = it },
            modifier = Modifier.fillMaxSize(),
        )

        // Top HUD (auto-hide)
        if (hudVisible.value) {
            Row(
                modifier = Modifier
                    .fillMaxWidth()
                    .statusBarsPadding()
                    .padding(10.dp),
                horizontalArrangement = Arrangement.SpaceBetween,
                verticalAlignment = Alignment.Top,
            ) {
                Column(modifier = Modifier.weight(1f, fill = false)) {
                    PixelPanel(
                        modifier = Modifier
                            .pointerInput(Unit) {
                                detectTapGestures(onLongPress = {
                                    debug = !debug
                                    debugTap = null
                                })
                            },
                    ) {
                        Column(Modifier.padding(horizontal = 12.dp, vertical = 8.dp)) {
                            Text("THE OFFICE", style = PixelLabel)
                            Text(
                                text = "${agents.size} agents · live",
                                style = PixelBody.copy(fontSize = 11.sp, lineHeight = 14.sp),
                            )
                        }
                    }
                    if (pending > 0) {
                        Spacer(Modifier.height(8.dp))
                        PixelButton(
                            text = "! $pending to approve",
                            onClick = {
                                selectedId = null
                                hotspot = null
                                deskOpen = true
                            },
                        )
                    }
                }
                Row(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                    PixelIconButton(
                        Icons.Default.Forum,
                        "Office chatter",
                        {
                            chatterOpen = !chatterOpen
                            if (chatterOpen) {
                                selectedId = null
                                hotspot = null
                                deskOpen = false
                            }
                        },
                    )
                    PixelIconButton(Icons.Default.Public, "Agent browsers", onOpenBrowsers)
                    PixelIconButton(Icons.Default.MyLocation, "Reset view", { camera.reset() })
                    PixelIconButton(Icons.AutoMirrored.Filled.List, "List view", onShowList)
                }
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

        // Bottom card: your desk, a selected agent, or a selected room. Only one at a time.
        Box(
            modifier = Modifier
                .align(Alignment.BottomCenter)
                .fillMaxWidth()
                .padding(12.dp),
        ) {
            when {
                deskOpen -> DeskPanel(
                    approvals = pendingApprovals,
                    requests = pendingRequests,
                    agents = agents,
                    settings = settings,
                    onApprove = { viewModel.approve(it) },
                    onDeny = { viewModel.deny(it) },
                    onSetAuto = { on, types ->
                        viewModel.updateSettings {
                            it.copy(autoApproveEnabled = on, autoApproveTypes = types.joinToString(","))
                        }
                    },
                    onOpenApprovals = onOpenApprovals,
                    onClose = { deskOpen = false },
                )

                chatterOpen -> ChatterPanel(
                    lines = chatter,
                    onPick = { id ->
                        chatterOpen = false
                        selectedId = id
                    },
                    onClose = { chatterOpen = false },
                )

                selectedAgent != null -> PixelPanel(modifier = Modifier.fillMaxWidth()) {
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
                        val talk = chatter.filter {
                            it.speakerId == selectedAgent.id || it.listenerId == selectedAgent.id
                        }.take(2)
                        talk.forEach { line ->
                            Spacer(Modifier.height(4.dp))
                            Text(
                                text = "${line.speakerName} → ${line.listenerName}: ${line.text}",
                                style = PixelBody.copy(fontSize = 12.sp, lineHeight = 16.sp),
                                maxLines = 2,
                                overflow = TextOverflow.Ellipsis,
                            )
                        }
                        Spacer(Modifier.height(10.dp))
                        Row(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                            PixelButton(
                                text = "Diary & chat",
                                onClick = { onOpenAgent(selectedAgent.id) },
                            )
                            if (pendingApprovals.any { it.agentId == selectedAgent.id } ||
                                pendingRequests.any { it.agentId == selectedAgent.id }
                            ) {
                                PixelButton(
                                    text = "Approvals",
                                    onClick = { deskOpen = true; selectedId = null },
                                    fill = Pal.cream,
                                )
                            }
                        }
                    }
                }

                hotspot != null -> {
                    val hs = hotspot
                    if (hs != null) {
                        PixelPanel(modifier = Modifier.fillMaxWidth()) {
                            Column(Modifier.padding(12.dp)) {
                                Row(verticalAlignment = Alignment.CenterVertically) {
                                    Column(Modifier.weight(1f)) {
                                        Text(hs.label, style = PixelLabel)
                                        Text(hs.blurb, style = PixelBody.copy(fontSize = 12.sp))
                                    }
                                    PixelIconButton(Icons.Default.Close, "Close", { hotspot = null })
                                }
                                Spacer(Modifier.height(10.dp))
                                PixelButton(text = "Open", onClick = { onNavigate(hs.route) })
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
