package com.agoria.app.ui.office

import android.content.Context
import com.agoria.app.service.BootStatus
import androidx.compose.foundation.background
import androidx.compose.foundation.border
import androidx.compose.foundation.clickable
import androidx.compose.foundation.gestures.detectTapGestures
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.PaddingValues
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.imePadding
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.statusBarsPadding
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.lazy.grid.GridCells
import androidx.compose.foundation.lazy.grid.LazyVerticalGrid
import androidx.compose.foundation.lazy.grid.items
import androidx.compose.foundation.text.BasicTextField
import androidx.compose.foundation.text.KeyboardActions
import androidx.compose.foundation.text.KeyboardOptions
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.automirrored.filled.List
import androidx.compose.material.icons.filled.AutoAwesome
import androidx.compose.material.icons.filled.Close
import androidx.compose.material.icons.filled.Forum
import androidx.compose.material.icons.filled.Menu
import androidx.compose.material.icons.filled.Map
import androidx.compose.material.icons.filled.MyLocation
import androidx.compose.material.icons.filled.Pause
import androidx.compose.material.icons.filled.PlayArrow
import androidx.compose.material.icons.filled.Public
import androidx.compose.material3.ExperimentalMaterial3Api
import androidx.compose.material3.Icon
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Surface
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableIntStateOf
import androidx.compose.runtime.mutableLongStateOf
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.produceState
import androidx.compose.runtime.remember
import androidx.compose.runtime.saveable.rememberSaveable
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.SolidColor
import androidx.compose.ui.graphics.lerp
import androidx.compose.ui.input.pointer.PointerEventPass
import androidx.compose.ui.input.pointer.pointerInput
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.semantics.Role
import androidx.compose.ui.semantics.clearAndSetSemantics
import androidx.compose.ui.semantics.contentDescription
import androidx.compose.ui.semantics.onClick
import androidx.compose.ui.semantics.role
import androidx.compose.ui.semantics.semantics
import androidx.compose.ui.text.font.FontStyle
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.input.ImeAction
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import androidx.lifecycle.viewmodel.compose.viewModel
import androidx.navigation.NavHostController
import kotlinx.coroutines.delay
import kotlinx.coroutines.flow.flowOf
import com.agoria.app.data.MessageEntity
import com.agoria.app.data.AgentEntity
import com.agoria.app.ui.AgoriaViewModel
import com.agoria.app.ui.components.AgentAvatar
import com.agoria.app.ui.components.Pill
import com.agoria.app.ui.components.StatusPill
import com.agoria.app.ui.components.statusLabel
import com.agoria.app.ui.office.world.CameraState
import com.agoria.app.ui.office.world.ChatterPanel
import com.agoria.app.ui.office.world.DeskPanel
import com.agoria.app.ui.office.world.DirectoryPanel
import com.agoria.app.ui.office.world.OfficeSound
import com.agoria.app.ui.office.world.HotspotDef
import com.agoria.app.ui.office.world.OfficeSim
import com.agoria.app.ui.office.world.OfficeWorld
import com.agoria.app.ui.office.world.Pal
import com.agoria.app.ui.office.world.PixelBody
import com.agoria.app.ui.office.world.PixelButton
import com.agoria.app.ui.office.world.PixelIconButton
import com.agoria.app.ui.office.world.PixelLabel
import com.agoria.app.ui.office.world.PixelPanel
import com.agoria.app.ui.components.PixelCard
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
    // Scope OfficeViewModel to the start destination (the office) so it survives tab switching
    val rootEntry = navController.getBackStackEntry("office")
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
    val runtimeState by viewModel.runtimeState.collectAsStateWithLifecycle()
    val autonomyOn = settings?.autonomyEnabled ?: false

    // Day and night: a soft colour wash that follows the phone's clock.
    val tint by produceState(initialValue = officeTint()) {
        while (true) {
            delay(60_000)
            value = officeTint()
        }
    }

    // First-run hints, shown once.
    val context = LocalContext.current
    val prefs = remember { context.getSharedPreferences("dunder_office", Context.MODE_PRIVATE) }
    var hintsSeen by remember { mutableStateOf(prefs.getBoolean("hints_seen", false)) }

    val christmas = remember { isChristmasSeason() }
    var directoryOpen by remember { mutableStateOf(false) }
    LaunchedEffect(Unit) { OfficeSound.init(context) }

    // Sound: an alert when something new needs you.
    var lastPending by remember { mutableIntStateOf(pending) }
    LaunchedEffect(pending) {
        if (pending > lastPending) OfficeSound.play(OfficeSound.Fx.ALERT)
        lastPending = pending
    }

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
    var lastChatAt by remember { mutableLongStateOf(-1L) }
    LaunchedEffect(chatter) {
        sim.onChatter(chatter, Time.now())
        val newest = chatter.firstOrNull()?.at ?: 0L
        if (lastChatAt >= 0L && newest > lastChatAt) OfficeSound.play(OfficeSound.Fx.TALK)
        lastChatAt = newest
    }

    val selectedAgent = agents.firstOrNull { it.id == selectedId }
    val actor = sim.actors.firstOrNull { it.agentId == selectedId }
    // Reading sim.revision here makes the card refresh when the agent changes activity.
    val statusLine = remember(sim.revision, selectedId) { actor?.statusText() }
    val latestDiary = diary.firstOrNull { it.agentId == selectedId }?.content

    fun focusAgent(id: String) {
        sim.actors.firstOrNull { it.agentId == id }?.let { camera.focusOn(it.x, it.y - 24f) }
    }
    fun focusDesk() {
        val d = com.agoria.app.ui.office.world.OfficeLayout.deskHotspot
        camera.focusOn(d.ax.toFloat(), d.y1.toFloat() + 40f)
    }

    // The selected agent's latest reply to you, for the quick-chat box.
    val messageFlow = remember(selectedId) {
        selectedId?.let { viewModel.messagesFor(it) } ?: flowOf(emptyList<MessageEntity>())
    }
    val messages by messageFlow.collectAsStateWithLifecycle(initialValue = emptyList())
    val lastReply = messages.lastOrNull { it.role == "agent" }

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

    // RUN-6: show boot-restart failure in the UI, with a "Turn autonomy on" action.
    val context = LocalContext.current
    var bootFailure by remember { mutableStateOf<BootStatus.Failure?>(null) }
    LaunchedEffect(Unit) {
        bootFailure = BootStatus.lastFailure(context)
    }
    fun startAutonomy() {
        val intent = android.content.Intent(context, com.agoria.app.service.AutonomyService::class.java)
        context.startForegroundService(intent)
        BootStatus.clear(context)
        bootFailure = null
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
            tint = tint,
            christmas = christmas,
            debug = debug,
            onSelect = {
                selectedId = it
                if (it != null) {
                    hotspot = null
                    deskOpen = false
                    chatterOpen = false
                    directoryOpen = false
                }
            },
            onHotspot = { hs ->
                if (hs == null) {
                    hotspot = null
                } else if (hs.id == "desk") {
                    hotspot = null
                    selectedId = null
                    chatterOpen = false
                    directoryOpen = false
                    deskOpen = true
                } else if (hs.id == "board") {
                    hotspot = null
                    selectedId = null
                    chatterOpen = false
                    deskOpen = false
                    directoryOpen = true
                } else {
                    hotspot = hs
                    selectedId = null
                    deskOpen = false
                    chatterOpen = false
                    directoryOpen = false
                }
            },
            onDebugTap = { debugTap = it },
            modifier = Modifier.fillMaxSize(),
        )

        // RUN-6: boot-restart failure banner with a "Turn autonomy on" action.
        bootFailure?.let { failure ->
            Row(
                modifier = Modifier
                    .fillMaxWidth()
                    .statusBarsPadding()
                    .padding(8.dp)
                    .background(Pal.amber)
                ,
            ) {
                Column(Modifier.weight(1f)) {
                    Text(
                        text = "Autonomy did not restart after the last reboot: ${failure.reason}",
                        style = MaterialTheme.typography.bodySmall,
                        color = Pal.ink,
                    )
                }
                PixelButton(
                    text = "Turn autonomy on",
                    onClick = { startAutonomy() },
                    small = true,
                )
            }
        }

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
                                detectTapGestures(
                                    onTap = {
                                        selectedId = null
                                        hotspot = null
                                        deskOpen = false
                                        chatterOpen = false
                                        directoryOpen = !directoryOpen
                                    },
                                    onLongPress = {
                                        debug = !debug
                                        debugTap = null
                                    },
                                )
                            },
                    ) {
                        Row(
                            Modifier.padding(horizontal = 12.dp, vertical = 8.dp),
                            verticalAlignment = Alignment.CenterVertically,
                        ) {
                          Column(Modifier.weight(1f, fill = false)) {
                            Text("THE OFFICE", style = PixelLabel)
                            Text(
                                text = "${agents.size} agents · " + when {
                                    !autonomyOn -> "paused"
                                    runtimeState.ticking -> "thinking"
                                    else -> "open"
                                },
                                style = PixelBody.copy(fontSize = 11.sp, lineHeight = 14.sp),
                            )
                          }
                            Spacer(Modifier.width(8.dp))
                            Icon(
                                Icons.Default.Menu,
                                contentDescription = "Open directory",
                                tint = Pal.ink,
                                modifier = Modifier.size(22.dp),
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
                                chatterOpen = false
                                directoryOpen = false
                                focusDesk()
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
                                directoryOpen = false
                            }
                        },
                    )
                    PixelIconButton(
                        if (autonomyOn) Icons.Default.Pause else Icons.Default.PlayArrow,
                        if (autonomyOn) "Pause the office" else "Start the office",
                        { viewModel.setAutonomy(!autonomyOn) },
                    )
                    PixelIconButton(Icons.Default.MyLocation, "Reset view", { camera.reset() })
                    PixelIconButton(Icons.AutoMirrored.Filled.List, "List view", onShowList)
                }
            }
        }

        if (!hintsSeen) {
            PixelPanel(
                modifier = Modifier
                    .align(Alignment.Center)
                    .fillMaxWidth()
                    .padding(horizontal = 24.dp),
            ) {
                Column(Modifier.padding(16.dp)) {
                    Text("WELCOME TO YOUR OFFICE", style = PixelLabel)
                    Spacer(Modifier.height(8.dp))
                    Text("Tap an agent to see what they are thinking, or to talk to them.", style = PixelBody)
                    Spacer(Modifier.height(4.dp))
                    Text("There is no menu bar. Tap a room, or the notice board (DIRECTORY), to open any page. Zoom in to see labels.", style = PixelBody)
                    Spacer(Modifier.height(4.dp))
                    Text("A yellow ! means someone needs you. Tap your desk to approve.", style = PixelBody)
                    Spacer(Modifier.height(4.dp))
                    Text("Drag to look around, pinch to zoom.", style = PixelBody)
                    Spacer(Modifier.height(12.dp))
                    PixelButton(
                        text = "Got it",
                        onClick = {
                            prefs.edit().putBoolean("hints_seen", true).apply()
                            hintsSeen = true
                        },
                    )
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
                .imePadding()
                .padding(12.dp),
        ) {
            when {
                directoryOpen -> DirectoryPanel(
                    onGo = { route ->
                        directoryOpen = false
                        onNavigate(route)
                    },
                    onListView = {
                        directoryOpen = false
                        onShowList()
                    },
                    onResetView = {
                        directoryOpen = false
                        camera.reset()
                    },
                    soundOn = OfficeSound.enabled,
                    onSound = { OfficeSound.setSoundEnabled(it) },
                    onClose = { directoryOpen = false },
                )

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
                        focusAgent(id)
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
                        if (lastReply != null) {
                            Spacer(Modifier.height(6.dp))
                            Text(
                                text = "${selectedAgent.name} says: ${lastReply.content.take(200)}",
                                style = PixelBody.copy(fontSize = 12.sp, lineHeight = 16.sp),
                                maxLines = 3,
                                overflow = TextOverflow.Ellipsis,
                            )
                        }
                        Spacer(Modifier.height(8.dp))
                        QuickChat(
                            name = selectedAgent.name,
                            onSend = { viewModel.sendMessage(selectedAgent.id, it) },
                        )
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

/** Christmas season in the Philippines: the "Ber months" start in September and run to Three Kings. */
private fun isChristmasSeason(): Boolean {
    val c = java.util.Calendar.getInstance()
    val month = c.get(java.util.Calendar.MONTH)
    return month >= java.util.Calendar.SEPTEMBER ||
        (month == java.util.Calendar.JANUARY && c.get(java.util.Calendar.DAY_OF_MONTH) <= 6)
}

/** One-line message box: talk to an agent without leaving the office. */
@Composable
private fun QuickChat(name: String, onSend: (String) -> Unit) {
    var draft by remember { mutableStateOf("") }
    fun send() {
        val text = draft.trim()
        if (text.isNotEmpty()) {
            onSend(text)
            draft = ""
        }
    }
    Row(verticalAlignment = Alignment.CenterVertically, horizontalArrangement = Arrangement.spacedBy(8.dp)) {
        Box(
            modifier = Modifier
                .weight(1f)
                .background(Color(0x14000000))
                .border(2.dp, Pal.ink)
                .padding(horizontal = 10.dp, vertical = 10.dp),
        ) {
            if (draft.isEmpty()) {
                Text(
                    text = "Say something to ${name}...",
                    style = PixelBody.copy(fontSize = 13.sp, color = Color(0xFF6B6050)),
                    maxLines = 1,
                )
            }
            BasicTextField(
                value = draft,
                onValueChange = { draft = it.take(400) },
                singleLine = true,
                textStyle = PixelBody.copy(fontSize = 14.sp),
                cursorBrush = SolidColor(Pal.ink),
                keyboardOptions = KeyboardOptions(imeAction = ImeAction.Send),
                keyboardActions = KeyboardActions(onSend = { send() }),
                modifier = Modifier.fillMaxWidth(),
            )
        }
        PixelButton(text = "Send", onClick = { send() })
    }
}

/**
 * Colour wash for the time of day: dark blue at night, a hint of orange at dawn and dusk,
 * nothing during the day. Keyframes are (minute of day, colour).
 */
private fun officeTint(): Color {
    val c = java.util.Calendar.getInstance()
    val m = c.get(java.util.Calendar.HOUR_OF_DAY) * 60 + c.get(java.util.Calendar.MINUTE)
    val night = Color(0x8C0B1030)
    val keys = listOf(
        0 to night,
        5 * 60 to night,
        7 * 60 to Color(0x1AFFB27A),
        8 * 60 to Color(0x00FFFFFF),
        17 * 60 to Color(0x00FFFFFF),
        18 * 60 + 30 to Color(0x2EFF9A3C),
        20 * 60 to Color(0x661B1B4B),
        22 * 60 to night,
        24 * 60 to night,
    )
    var i = 0
    while (i < keys.size - 2 && m >= keys[i + 1].first) i++
    val (m0, c0) = keys[i]
    val (m1, c1) = keys[i + 1]
    val t = ((m - m0).toFloat() / (m1 - m0).toFloat()).coerceIn(0f, 1f)
    return lerp(c0, c1, t)
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

    Column(Modifier.fillMaxSize()) {
        // Pixel-style ink top bar with actions
        Column(
            modifier = Modifier
                .fillMaxWidth()
                .background(Pal.ink)
                .padding(top = 16.dp, bottom = 12.dp, start = 16.dp, end = 16.dp),
        ) {
            Row(
                modifier = Modifier.fillMaxWidth(),
                verticalAlignment = Alignment.CenterVertically,
                horizontalArrangement = Arrangement.SpaceBetween,
            ) {
                Column {
                    Text(
                        text = "The office",
                        style = PixelLabel.copy(fontSize = 20.sp, color = Pal.cream),
                    )
                    Text(
                        text = "${agents.size} agents · tap a desk to talk",
                        style = PixelBody.copy(fontSize = 12.sp, color = Pal.tan),
                    )
                }
                Row(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                    PixelIconButton(
                        icon = Icons.Default.Map,
                        description = "Map view",
                        onClick = onShowMap,
                    )
                    PixelIconButton(
                        icon = Icons.Default.Public,
                        description = "Agent browsers",
                        onClick = onOpenBrowsers,
                    )
                }
            }
            // 2 dp ink bottom border
            Box(Modifier.fillMaxWidth().height(2.dp).background(Pal.ink))
        }

        // Content with padding
        Column(
            modifier = Modifier
                .fillMaxSize()
                .padding(16.dp),
            verticalArrangement = Arrangement.spacedBy(12.dp),
        ) {
            if (latest != null) {
                val agent = agents.firstOrNull { it.id == latest.agentId }
                PixelCard(
                    modifier = Modifier
                        .fillMaxWidth()
                        .semantics(mergeDescendants = true) {},
                    fill = Pal.amber.copy(alpha = 0.14f),
                ) {
                    Row(
                        modifier = Modifier.padding(14.dp),
                        verticalAlignment = Alignment.Top,
                    ) {
                        Icon(
                            Icons.Default.AutoAwesome,
                            contentDescription = null,
                            tint = Pal.amber,
                        )
                        Spacer(Modifier.width(10.dp))
                        Column(Modifier.weight(1f)) {
                            Text(
                                text = "${agent?.name ?: "Office"} is thinking",
                                style = PixelLabel.copy(color = Pal.ink),
                            )
                            Text(
                                text = latest.content,
                                style = PixelBody.copy(color = Pal.ink),
                                maxLines = 3,
                                overflow = TextOverflow.Ellipsis,
                            )
                        }
                        Spacer(Modifier.width(8.dp))
                        Text(
                            text = Time.relativeTime(latest.createdAt),
                            style = PixelBody.copy(color = Pal.tan, fontSize = 12.sp),
                        )
                    }
                }
            }

            LazyVerticalGrid(
                columns = GridCells.Fixed(2),
                modifier = Modifier.weight(1f),
                contentPadding = PaddingValues(0.dp),
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
    // TalkBack: one focus stop per agent that reads name, role, status, approval flag and the
    // latest diary line, and opens the agent page. Children are cleared so nothing is read twice.
    val open = onClick
    val description = buildString {
        append(agent.name).append(", ").append(agent.role).append(". ")
        append("Status: ").append(statusLabel(agent.status)).append(". ")
        if (agent.status == "waiting_approval") append("Waiting for your approval. ")
        val note = latestDiary ?: agent.goal
        if (note.isNotBlank()) append(note.take(140))
    }
    PixelCard(
        modifier = Modifier
            .fillMaxWidth()
            .clickable(onClick = onClick)
            .clearAndSetSemantics {
                contentDescription = description
                role = Role.Button
                onClick(label = "Open ${agent.name}") { open(); true }
            },
        fill = MaterialTheme.colorScheme.surface,
    ) {
        Column(Modifier.padding(14.dp)) {
            Row(verticalAlignment = Alignment.CenterVertically) {
                AgentAvatar(agent, 46.dp)
                Spacer(Modifier.width(10.dp))
                Column(Modifier.weight(1f)) {
                    Text(agent.name, style = PixelLabel)
                    Text(
                        agent.role,
                        style = PixelBody.copy(color = Pal.tan, fontSize = 12.sp),
                    )
                }
            }
            Spacer(Modifier.height(10.dp))
            StatusPill(agent.status)
            Spacer(Modifier.height(8.dp))
            Text(
                text = latestDiary ?: agent.goal,
                style = PixelBody.copy(color = Pal.tan, fontStyle = FontStyle.Italic),
                maxLines = 3,
                overflow = TextOverflow.Ellipsis,
            )
            Spacer(Modifier.height(8.dp))
            Row(verticalAlignment = Alignment.CenterVertically) {
                Pill(text = "Talk")
                if (agent.status == "waiting_approval") {
                    Spacer(Modifier.width(6.dp))
                    Pill(text = "Approval", color = Pal.amber)
                }
            }
        }
    }
}
