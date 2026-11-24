package com.agoria.app.ui.approvals

import androidx.compose.foundation.background
import androidx.compose.foundation.border
import androidx.compose.foundation.clickable
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
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.verticalScroll
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.Settings
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberCoroutineScope
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.semantics.Role
import androidx.compose.ui.semantics.clearAndSetSemantics
import androidx.compose.ui.semantics.contentDescription
import androidx.compose.ui.semantics.onClick
import androidx.compose.ui.semantics.role
import androidx.compose.ui.text.font.FontFamily
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import com.agoria.app.data.ApprovalEntity
import com.agoria.app.data.ConnectorRequestEntity
import com.agoria.app.ui.AgoriaViewModel
import com.agoria.app.ui.components.EmptyState
import com.agoria.app.ui.components.Pill
import com.agoria.app.ui.components.PixelScaffold
import com.agoria.app.ui.components.RiskBadge
import com.agoria.app.ui.office.world.Pal
import com.agoria.app.ui.office.world.PixelBody
import com.agoria.app.ui.office.world.PixelButton
import com.agoria.app.ui.office.world.PixelDialog
import com.agoria.app.ui.office.world.PixelIconButton
import com.agoria.app.ui.office.world.PixelLabel
import com.agoria.app.ui.office.world.PixelPanel
import com.agoria.app.ui.office.world.PixelSwitch
import com.agoria.app.ui.office.world.PixelTextField
import com.agoria.app.util.Json
import com.agoria.app.util.Time
import kotlinx.coroutines.launch

@Composable
fun ApprovalsScreen(
    viewModel: AgoriaViewModel,
    onOpenConnectors: (String?) -> Unit,
    onBack: () -> Unit,
) {
    val approvals by viewModel.approvals.collectAsStateWithLifecycle()
    val pending by viewModel.pendingApprovals.collectAsStateWithLifecycle()
    val connectorRequests by viewModel.pendingConnectorRequests.collectAsStateWithLifecycle()
    val agents by viewModel.agents.collectAsStateWithLifecycle()
    val setupConnectorId by viewModel.setupConnectorId.collectAsStateWithLifecycle()
    val settings by viewModel.settings.collectAsStateWithLifecycle()
    var tab by remember { mutableStateOf(0) }
    var selected by remember { mutableStateOf<ApprovalEntity?>(null) }
    var denyNote by remember { mutableStateOf("") }
    var showAutoApproveDialog by remember { mutableStateOf(false) }
    var autoApproveTypes by remember { mutableStateOf("") }
    val snackbar = remember { com.google.accompanist.adaptive.SnackbarHostState() }
    val scope = rememberCoroutineScope()

    LaunchedEffect(setupConnectorId) {
        if (setupConnectorId != null) {
            onOpenConnectors(null)
            viewModel.clearSetupConnector()
            snackbar.showSnackbar("Connector approved. Configure it in the Connectors screen.")
        }
    }

    LaunchedEffect(settings) {
        settings?.let { s ->
            autoApproveTypes = s.autoApproveTypes ?: ""
        }
    }

    PixelScaffold(
        title = "Approvals",
        subtitle = "${pending.size} waiting · nothing external runs without you",
        onBack = onBack,
        actions = {
            PixelIconButton(
                icon = Icons.Filled.Settings,
                description = "Auto-approve settings",
                onClick = { showAutoApproveDialog = true },
            )
        },
    ) { padding ->
        Column(
            modifier = Modifier
                .fillMaxSize()
                .padding(padding),
        ) {
            PixelTabs(
                labels = listOf("Pending", "History"),
                selected = tab,
                onSelect = { tab = it },
            )

            if (tab == 0) {
                ApprovalList(
                    approvals = pending,
                    connectorRequests = connectorRequests,
                    agents = agents,
                    onOpen = { selected = it; denyNote = "" },
                    onApproveConnector = { id ->
                        viewModel.approveConnectorRequest(id)
                    },
                    onDenyConnector = { id ->
                        viewModel.denyConnectorRequest(id)
                        scope.launch { snackbar.showSnackbar("Connector request denied.") }
                    },
                )
            } else {
                val history = approvals.filter { it.status != "pending" }
                if (history.isEmpty()) {
                    EmptyState(
                        title = "No history yet",
                        body = "Approved and denied requests will appear here with their results.",
                    )
                } else {
                    ApprovalHistoryList(
                        approvals = history,
                        agents = agents,
                        onOpen = { selected = it },
                    )
                }
            }
        }
    }

    selected?.let { approval ->
        ApprovalDialog(
            approval = approval,
            agentName = agents.firstOrNull { it.id == approval.agentId }?.name ?: "Office",
            onDismiss = { selected = null },
            onApprove = {
                viewModel.approve(approval.id, denyNote)
                selected = null
                scope.launch { snackbar.showSnackbar("Approved. The agent is executing it now.") }
            },
            onDeny = {
                viewModel.deny(approval.id, denyNote)
                selected = null
                scope.launch { snackbar.showSnackbar("Denied. The agent will see your note.") }
            },
            note = denyNote,
            onNote = { denyNote = it },
        )
    }

    if (showAutoApproveDialog) {
        AutoApproveDialog(
            enabled = settings?.autoApproveEnabled ?: false,
            types = autoApproveTypes,
            onEnabledChange = { enabled ->
                viewModel.updateSettings { s ->
                    val newTypes = if (enabled && s.autoApproveTypes.isBlank()) {
                        "connector_action" // Default to connector actions when first enabling
                    } else {
                        s.autoApproveTypes
                    }
                    s.copy(autoApproveEnabled = enabled, autoApproveTypes = newTypes)
                }
                // Update local state to reflect the default
                if (enabled && autoApproveTypes.isBlank()) {
                    autoApproveTypes = "connector_action"
                }
            },
            onTypesChange = { types ->
                autoApproveTypes = types
            },
            onSave = {
                viewModel.updateSettings { s ->
                    s.copy(autoApproveTypes = autoApproveTypes)
                }
            },
            onDismiss = { showAutoApproveDialog = false },
        )
    }
}

@Composable
private fun ApprovalList(
    approvals: List<ApprovalEntity>,
    connectorRequests: List<ConnectorRequestEntity>,
    agents: List<com.agoria.app.data.AgentEntity>,
    onOpen: (ApprovalEntity) -> Unit,
    onApproveConnector: (String) -> Unit,
    onDenyConnector: (String) -> Unit,
) {
    if (approvals.isEmpty() && connectorRequests.isEmpty()) {
        EmptyState(
            title = "Nothing needs approval",
            body = "When an agent wants to build a project, connect a service, or send something outward, it will ask here first.",
        )
        return
    }

    Column(
        modifier = Modifier
            .fillMaxSize()
            .verticalScroll(rememberScrollState())
            .padding(16.dp),
        verticalArrangement = Arrangement.spacedBy(12.dp),
    ) {
        if (connectorRequests.isNotEmpty()) {
            Text(
                text = "Connector requests",
                style = MaterialTheme.typography.titleMedium,
            )
            connectorRequests.forEach { request ->
                ConnectorRequestCard(
                    request = request,
                    agentName = agents.firstOrNull { it.id == request.agentId }?.name ?: "Office",
                    onApprove = { onApproveConnector(request.id) },
                    onDeny = { onDenyConnector(request.id) },
                )
            }
        }

        if (approvals.isNotEmpty()) {
            Text(
                text = "Approvals",
                style = MaterialTheme.typography.titleMedium,
            )
            approvals.forEach { approval ->
                ApprovalCard(
                    approval = approval,
                    agentName = agents.firstOrNull { it.id == approval.agentId }?.name ?: "Office",
                    onClickAction = { onOpen(approval) },
                )
            }
        }
    }
}

@Composable
private fun ApprovalCard(
    approval: ApprovalEntity,
    agentName: String,
    onClickAction: () -> Unit,
) {
    val open = onClickAction
    val description = buildString {
        append(agentName).append(", ").append(typeLabel(approval.type)).append(". ")
        append("Risk: ").append(approval.risk).append(". ")
        append(approval.title).append(". ")
        append(approval.summary.take(160))
    }
    PixelCard(
        modifier = Modifier
            .fillMaxWidth()
            .clickable(onClick = onClickAction)
            .clearAndSetSemantics {
                contentDescription = description
                role = Role.Button
                onClick(label = "Open approval") { open(); true }
            },
        fill = MaterialTheme.colorScheme.surfaceVariant,
    ) {
        Column(Modifier.padding(16.dp)) {
            Row(verticalAlignment = Alignment.CenterVertically) {
                Text(
                    text = agentName,
                    style = MaterialTheme.typography.labelLarge,
                    color = Pal.amber,
                )
                Spacer(Modifier.width(8.dp))
                Pill(text = typeLabel(approval.type))
                Spacer(Modifier.weight(1f))
                RiskBadge(approval.risk)
            }
            Spacer(Modifier.height(8.dp))
            Text(approval.title, style = MaterialTheme.typography.titleMedium)
            Spacer(Modifier.height(4.dp))
            Text(
                text = approval.summary,
                style = MaterialTheme.typography.bodySmall,
                color = Pal.tan,
                maxLines = 3,
                overflow = TextOverflow.Ellipsis,
            )
            Spacer(Modifier.height(8.dp))
            Text(
                text = Time.relativeTime(approval.createdAt),
                style = MaterialTheme.typography.labelSmall,
                color = Pal.tan,
            )
        }
    }
}

@Composable
private fun ConnectorRequestCard(
    request: ConnectorRequestEntity,
    agentName: String,
    onApprove: () -> Unit,
    onDeny: () -> Unit,
) {
    val description = buildString {
        append(agentName).append(" requests ").append(request.connectorId).append(". ")
        append(request.reason.take(160))
    }
    PixelCard(
        modifier = Modifier
            .fillMaxWidth()
            .clearAndSetSemantics {
                contentDescription = description
                role = Role.Button
            },
        fill = Pal.amber.copy(alpha = 0.14f),
    ) {
        Column(Modifier.padding(16.dp)) {
            Row(verticalAlignment = Alignment.CenterVertically) {
                Text(
                    text = agentName,
                    style = MaterialTheme.typography.labelLarge,
                    color = Pal.ink,
                )
                Spacer(Modifier.width(8.dp))
                Pill(
                    text = "Connector",
                    color = Pal.amber,
                )
            }
            Spacer(Modifier.height(8.dp))
            Text(
                text = request.connectorId.replaceFirstChar { it.uppercase() },
                style = MaterialTheme.typography.titleMedium,
            )
            Spacer(Modifier.height(4.dp))
            Text(
                text = request.reason,
                style = MaterialTheme.typography.bodySmall,
                color = Pal.ink,
            )
            if (request.scopes.isNotBlank()) {
                Spacer(Modifier.height(4.dp))
                Text(
                    text = "Scopes: ${request.scopes}",
                    style = MaterialTheme.typography.labelSmall,
                    color = Pal.ink,
                )
            }
            Spacer(Modifier.height(10.dp))
            Row(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                PixelButton(text = "Approve & set up", onClick = onApprove)
                PixelButton(text = "Deny", onClick = onDeny, small = true, fill = Pal.tan)
            }
        }
    }
}

@Composable
private fun ApprovalHistoryList(
    approvals: List<ApprovalEntity>,
    agents: List<com.agoria.app.data.AgentEntity>,
    onOpen: (ApprovalEntity) -> Unit,
) {
    Column(
        modifier = Modifier
            .fillMaxSize()
            .verticalScroll(rememberScrollState())
            .padding(16.dp),
        verticalArrangement = Arrangement.spacedBy(10.dp),
    ) {
        approvals.forEach { approval ->
            val agentName = agents.firstOrNull { it.id == approval.agentId }?.name ?: "Office"
            val open = { onOpen(approval) }
            val description = buildString {
                append(agentName).append(", status ").append(approval.status).append(". ")
                append(approval.title).append(". ")
                if (!approval.result.isNullOrBlank()) append(approval.result.take(120))
            }
            PixelPanel(
                modifier = Modifier
                    .fillMaxWidth()
                    .clickable(onClick = open)
                    .clearAndSetSemantics {
                        contentDescription = description
                        role = Role.Button
                        onClick(label = "Open approval history") { open(); true }
                    },
                fill = MaterialTheme.colorScheme.surfaceVariant,
            ) {
                Column(Modifier.padding(14.dp)) {
                    Row(verticalAlignment = Alignment.CenterVertically) {
                        Text(
                            text = agentName,
                            style = MaterialTheme.typography.labelLarge,
                        )
                        Spacer(Modifier.width(8.dp))
                        Pill(
                            text = approval.status.replaceFirstChar { it.uppercase() },
                            color = statusTint(approval.status),
                        )
                        Spacer(Modifier.weight(1f))
                        Text(
                            text = Time.relativeTime(approval.createdAt),
                            style = MaterialTheme.typography.labelSmall,
                            color = Pal.tan,
                        )
                    }
                    Spacer(Modifier.height(4.dp))
                    Text(approval.title, style = MaterialTheme.typography.titleMedium)
                    if (!approval.result.isNullOrBlank()) {
                        Spacer(Modifier.height(2.dp))
                        Text(
                            text = approval.result,
                            style = MaterialTheme.typography.bodySmall,
                            color = Pal.tan,
                            maxLines = 2,
                            overflow = TextOverflow.Ellipsis,
                        )
                    }
                }
            }
        }
    }
}

@Composable
private fun ApprovalDialog(
    approval: ApprovalEntity,
    agentName: String,
    onDismiss: () -> Unit,
    onApprove: () -> Unit,
    onDeny: () -> Unit,
    note: String,
    onNote: (String) -> Unit,
) {
    val pending = approval.status == "pending"
    var noteText by remember(note) { mutableStateOf(note) }

    PixelDialog(
        onDismiss = onDismiss,
        title = approval.title,
    ) {
        Column(
            modifier = Modifier
                .heightIn(max = 460.dp)
                .verticalScroll(rememberScrollState()),
            verticalArrangement = Arrangement.spacedBy(8.dp),
        ) {
            Row(verticalAlignment = Alignment.CenterVertically) {
                Text(agentName, style = MaterialTheme.typography.labelLarge)
                Spacer(Modifier.width(8.dp))
                Pill(text = typeLabel(approval.type))
                Spacer(Modifier.width(8.dp))
                RiskBadge(approval.risk)
            }
            Text(approval.summary, style = MaterialTheme.typography.bodyMedium)
            if (approval.type == "build_project") {
                Text(
                    text = "This generates a downloadable project ZIP inside the app. Nothing is published or sent to an external service.",
                    style = MaterialTheme.typography.bodySmall,
                    color = Pal.tan,
                )
            }
            if (approval.type == "connector_action") {
                Text(
                    text = "This runs the connector action exactly as shown below. Review the arguments carefully.",
                    style = MaterialTheme.typography.bodySmall,
                    color = Pal.tan,
                )
            }
            if (approval.details.isNotBlank()) {
                PixelPanel(
                    modifier = Modifier.fillMaxWidth(),
                    fill = MaterialTheme.colorScheme.surfaceVariant,
                ) {
                    Text(
                        text = Json.pretty(approval.details),
                        style = MaterialTheme.typography.bodySmall.copy(fontFamily = FontFamily.Monospace),
                        modifier = Modifier.padding(10.dp),
                    )
                }
            }
            if (pending) {
                PixelTextField(
                    value = noteText,
                    onValueChange = { noteText = it; onNote(it) },
                    label = "Note for the agent (optional)",
                    singleLine = false,
                    hint = "Optional: add a note for the agent",
                    labelColor = Pal.navy,
                    hintColor = Pal.tan,
                )
            }
            if (!pending && !approval.result.isNullOrBlank()) {
                Text(
                    text = "Result: ${approval.result}",
                    style = MaterialTheme.typography.bodySmall,
                    color = Pal.tan,
                )
            }
            Spacer(Modifier.height(8.dp))
            Row(
                modifier = Modifier.fillMaxWidth(),
                horizontalArrangement = Arrangement.spacedBy(8.dp),
            ) {
                if (pending) {
                    PixelButton(text = "Approve", onClick = onApprove, modifier = Modifier.weight(1f))
                    PixelButton(text = "Deny", onClick = onDeny, modifier = Modifier.weight(1f), fill = Pal.red)
                } else {
                    PixelButton(text = "Close", onClick = onDismiss, modifier = Modifier.weight(1f), fill = Pal.tan)
                }
            }
        }
    }
}

private fun typeLabel(type: String): String = when (type) {
    "build_project" -> "Build"
    "connector_action" -> "Connector action"
    "connector_install" -> "Install connector"
    "send_email" -> "Send email"
    "publish" -> "Publish"
    "delete" -> "Delete"
    "spend_api" -> "Spend"
    else -> type.replaceFirstChar { it.uppercase() }
}

private fun statusTint(status: String): Color = when (status) {
    "executed" -> Pal.teal
    "failed" -> Pal.red
    "denied" -> Pal.red
    else -> Pal.amber
}

/** Square checkbox for pixel UI. */
@Composable
private fun PixelCheckbox(
    checked: Boolean,
    onCheckedChange: (Boolean) -> Unit,
    modifier: Modifier = Modifier,
) {
    Box(
        modifier = modifier
            .size(24.dp)
            .background(if (checked) Pal.amber else Pal.cream)
            .border(2.dp, Pal.ink)
            .clickable(onClick = { onCheckedChange(!checked) }),
        contentAlignment = Alignment.Center,
    ) {
        if (checked) {
            androidx.compose.material.icons.Icons.Default.Check.apply {
                androidx.compose.material3.Icon(
                    imageVector = this,
                    contentDescription = null,
                    tint = Pal.ink,
                    modifier = Modifier.size(16.dp),
                )
            }
        }
    }
}

@Composable
private fun AutoApproveDialog(
    enabled: Boolean,
    types: String,
    onEnabledChange: (Boolean) -> Unit,
    onTypesChange: (String) -> Unit,
    onSave: () -> Unit,
    onDismiss: () -> Unit,
) {
    val allTypes = listOf(
        "build_project" to "Build projects",
        "connector_install" to "Install connectors",
        "other" to "Other safe types",
    )

    // Only SAFE_TYPES per Rule 3: build_project, connector_install, other
    val effectiveTypes = if (enabled && types.isBlank()) "build_project,connector_install,other" else types

    PixelDialog(
        onDismiss = onDismiss,
        title = "Auto-approve Settings",
    ) {
        Column(
            modifier = Modifier
                .heightIn(max = 500.dp)
                .verticalScroll(rememberScrollState()),
            verticalArrangement = Arrangement.spacedBy(8.dp),
        ) {
            Row(verticalAlignment = Alignment.CenterVertically) {
                Text("Enable auto-approve", style = MaterialTheme.typography.titleSmall)
                Spacer(Modifier.weight(1f))
                PixelSwitch(checked = enabled, onChange = onEnabledChange)
            }
            Text(
                text = "When enabled, approvals of selected SAFE types will be automatically approved without prompting. Per Rule 3, only build_project, connector_install, and other are offered. Connector actions (payments), send_email, publish, delete, and spend_api are never auto-approved.",
                style = MaterialTheme.typography.bodySmall,
                color = Pal.tan,
            )
            Spacer(Modifier.height(8.dp))
            Text("Auto-approve these safe types:", style = MaterialTheme.typography.labelLarge)
            allTypes.forEach { (typeId, typeLabel) ->
                val isChecked = effectiveTypes.split(",").map { it.trim() }.contains(typeId)
                Row(
                    modifier = Modifier.fillMaxWidth(),
                    verticalAlignment = Alignment.CenterVertically,
                ) {
                    PixelCheckbox(checked = isChecked, onCheckedChange = { checked ->
                        val current = effectiveTypes.split(",").map { it.trim() }.filter { it.isNotBlank() }.toMutableSet()
                        if (checked) current.add(typeId) else current.remove(typeId)
                        onTypesChange(current.joinToString(","))
                    })
                    Spacer(Modifier.width(12.dp))
                    Text(typeLabel, style = MaterialTheme.typography.bodyMedium)
                }
            }
            Spacer(Modifier.height(8.dp))
            Row(
                modifier = Modifier.fillMaxWidth(),
                horizontalArrangement = Arrangement.spacedBy(8.dp),
            ) {
                PixelButton(text = "Save", onClick = { onSave(); onDismiss() }, modifier = Modifier.weight(1f))
                PixelButton(text = "Cancel", onClick = onDismiss, modifier = Modifier.weight(1f), fill = Pal.tan)
            }
        }
    }
}

/** Square tab strip (replaces the Material TabRow): selected tab amber with ink text, others teal with cream text. */
@Composable
private fun PixelTabs(
    labels: List<String>,
    selected: Int,
    onSelect: (Int) -> Unit,
) {
    Row(
        modifier = Modifier
            .fillMaxWidth()
            .padding(horizontal = 12.dp, vertical = 6.dp),
        horizontalArrangement = Arrangement.spacedBy(6.dp),
    ) {
        labels.forEachIndexed { index, label ->
            val on = index == selected
            val open = { onSelect(index) }
            Box(
                modifier = Modifier
                    .weight(1f)
                    .heightIn(min = 40.dp)
                    .background(if (on) Pal.amber else Pal.teal)
                    .border(2.dp, Pal.ink)
                    .clickable {
                        if (!on) com.agoria.app.ui.office.world.OfficeSound.play(com.agoria.app.ui.office.world.OfficeSound.Fx.CLICK)
                        onSelect(index)
                    }
                    .clearAndSetSemantics {
                        contentDescription = "${label} tab, ${if (on) "selected" else "not selected"}"
                        role = Role.Tab
                        onClick(label = "Select ${label} tab") { open(); true }
                    },
                contentAlignment = Alignment.Center,
            ) {
                Text(
                    text = label.uppercase(),
                    style = PixelLabel.copy(fontSize = 11.sp, color = if (on) Pal.ink else Pal.cream),
                )
            }
        }
    }
}