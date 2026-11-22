package com.agoria.app.ui.approvals

import androidx.compose.foundation.layout.Arrangement
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
import androidx.compose.material3.AlertDialog
import androidx.compose.material3.Button
import androidx.compose.material3.Card
import androidx.compose.material3.CardDefaults
import androidx.compose.material3.Checkbox
import androidx.compose.material3.ExperimentalMaterial3Api
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedTextField
import androidx.compose.material3.SnackbarHost
import androidx.compose.material3.SnackbarHostState
import androidx.compose.material3.Surface
import androidx.compose.material3.Switch
import androidx.compose.material3.Tab
import androidx.compose.material3.TabRow
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.material.icons.filled.Settings as SettingsIcon
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
import androidx.compose.ui.text.font.FontFamily
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import com.agoria.app.data.ApprovalEntity
import com.agoria.app.data.ConnectorRequestEntity
import com.agoria.app.ui.AgoriaViewModel
import com.agoria.app.ui.components.EmptyState
import com.agoria.app.ui.components.Pill
import com.agoria.app.ui.components.PixelScaffold
import com.agoria.app.ui.components.RiskBadge
import com.agoria.app.util.Json
import com.agoria.app.util.Time
import kotlinx.coroutines.launch

@OptIn(ExperimentalMaterial3Api::class)
@Composable
fun ApprovalsScreen(
    viewModel: AgoriaViewModel,
    onOpenConnectors: () -> Unit,
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
    val snackbar = remember { SnackbarHostState() }
    val scope = rememberCoroutineScope()

    LaunchedEffect(setupConnectorId) {
        if (setupConnectorId != null) {
            onOpenConnectors()
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
            IconButton(onClick = { showAutoApproveDialog = true }) {
                Icon(
                    imageVector = SettingsIcon,
                    contentDescription = "Auto-approve settings",
                )
            }
        },
        snackbarHost = { SnackbarHost(snackbar) },
    ) { padding ->
        Column(
            modifier = Modifier
                .fillMaxSize()
                .padding(padding),
        ) {
            TabRow(selectedTabIndex = tab) {
                Tab(selected = tab == 0, onClick = { tab = 0 }, text = { Text("Pending") })
                Tab(selected = tab == 1, onClick = { tab = 1 }, text = { Text("History") })
            }

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
                    onClick = { onOpen(approval) },
                )
            }
        }
    }
}

@Composable
private fun ApprovalCard(
    approval: ApprovalEntity,
    agentName: String,
    onClick: () -> Unit,
) {
    Card(
        onClick = onClick,
        modifier = Modifier.fillMaxWidth(),
        shape = MaterialTheme.shapes.large,
        colors = CardDefaults.cardColors(
            containerColor = MaterialTheme.colorScheme.surfaceVariant,
        ),
    ) {
        Column(Modifier.padding(16.dp)) {
            Row(verticalAlignment = Alignment.CenterVertically) {
                Text(
                    text = agentName,
                    style = MaterialTheme.typography.labelLarge,
                    color = MaterialTheme.colorScheme.primary,
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
                color = MaterialTheme.colorScheme.onSurfaceVariant,
                maxLines = 3,
                overflow = TextOverflow.Ellipsis,
            )
            Spacer(Modifier.height(8.dp))
            Text(
                text = Time.relativeTime(approval.createdAt),
                style = MaterialTheme.typography.labelSmall,
                color = MaterialTheme.colorScheme.onSurfaceVariant,
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
    Card(
        modifier = Modifier.fillMaxWidth(),
        shape = MaterialTheme.shapes.large,
        colors = CardDefaults.cardColors(
            containerColor = MaterialTheme.colorScheme.tertiaryContainer,
        ),
    ) {
        Column(Modifier.padding(16.dp)) {
            Row(verticalAlignment = Alignment.CenterVertically) {
                Text(
                    text = agentName,
                    style = MaterialTheme.typography.labelLarge,
                    color = MaterialTheme.colorScheme.onTertiaryContainer,
                )
                Spacer(Modifier.width(8.dp))
                Pill(
                    text = "Connector",
                    color = MaterialTheme.colorScheme.tertiary,
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
                color = MaterialTheme.colorScheme.onTertiaryContainer,
            )
            if (request.scopes.isNotBlank()) {
                Spacer(Modifier.height(4.dp))
                Text(
                    text = "Scopes: ${request.scopes}",
                    style = MaterialTheme.typography.labelSmall,
                    color = MaterialTheme.colorScheme.onTertiaryContainer,
                )
            }
            Spacer(Modifier.height(10.dp))
            Row(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                Button(onClick = onApprove) { Text("Approve & set up") }
                TextButton(onClick = onDeny) { Text("Deny") }
            }
        }
    }
}

@OptIn(ExperimentalMaterial3Api::class)
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
            Surface(
                onClick = { onOpen(approval) },
                modifier = Modifier.fillMaxWidth(),
                shape = MaterialTheme.shapes.medium,
                color = MaterialTheme.colorScheme.surfaceVariant,
            ) {
                Column(Modifier.padding(14.dp)) {
                    Row(verticalAlignment = Alignment.CenterVertically) {
                        Text(
                            text = agents.firstOrNull { it.id == approval.agentId }?.name ?: "Office",
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
                            color = MaterialTheme.colorScheme.onSurfaceVariant,
                        )
                    }
                    Spacer(Modifier.height(4.dp))
                    Text(approval.title, style = MaterialTheme.typography.titleMedium)
                    if (!approval.result.isNullOrBlank()) {
                        Spacer(Modifier.height(2.dp))
                        Text(
                            text = approval.result,
                            style = MaterialTheme.typography.bodySmall,
                            color = MaterialTheme.colorScheme.onSurfaceVariant,
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
    AlertDialog(
        onDismissRequest = onDismiss,
        title = { Text(approval.title) },
        text = {
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
                        color = MaterialTheme.colorScheme.onSurfaceVariant,
                    )
                }
                if (approval.type == "connector_action") {
                    Text(
                        text = "This runs the connector action exactly as shown below. Review the arguments carefully.",
                        style = MaterialTheme.typography.bodySmall,
                        color = MaterialTheme.colorScheme.onSurfaceVariant,
                    )
                }
                if (approval.details.isNotBlank()) {
                    Surface(
                        modifier = Modifier.fillMaxWidth(),
                        shape = MaterialTheme.shapes.small,
                        color = MaterialTheme.colorScheme.surfaceVariant,
                    ) {
                        Text(
                            text = Json.pretty(approval.details),
                            style = MaterialTheme.typography.bodySmall,
                            fontFamily = FontFamily.Monospace,
                            modifier = Modifier.padding(10.dp),
                        )
                    }
                }
                if (pending) {
                    OutlinedTextField(
                        value = note,
                        onValueChange = onNote,
                        label = { Text("Note for the agent (optional)") },
                        modifier = Modifier.fillMaxWidth(),
                        minLines = 2,
                    )
                }
                if (!pending && !approval.result.isNullOrBlank()) {
                    Text(
                        text = "Result: ${approval.result}",
                        style = MaterialTheme.typography.bodySmall,
                        color = MaterialTheme.colorScheme.onSurfaceVariant,
                    )
                }
            }
        },
        confirmButton = {
            if (pending) {
                TextButton(onClick = onApprove) { Text("Approve") }
            } else {
                TextButton(onClick = onDismiss) { Text("Close") }
            }
        },
        dismissButton = {
            if (pending) {
                TextButton(onClick = onDeny) { Text("Deny") }
            } else {
                TextButton(onClick = onDismiss) { Text("Close") }
            }
        },
    )
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

@Composable
private fun statusTint(status: String): Color = when (status) {
    "executed" -> MaterialTheme.colorScheme.secondary
    "failed" -> MaterialTheme.colorScheme.error
    "denied" -> MaterialTheme.colorScheme.error
    else -> MaterialTheme.colorScheme.tertiary
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
        "connector_action" to "Connector actions (GitHub, Notion, etc.)",
        "send_email" to "Send emails",
        "publish" to "Publish content",
        "delete" to "Delete resources",
        "spend_api" to "Spend API credits",
        "connector_install" to "Install connectors",
    )

    // Default to connector_action if enabled but no types selected
    val effectiveTypes = if (enabled && types.isBlank()) "connector_action" else types

    AlertDialog(
        onDismissRequest = onDismiss,
        title = { Text("Auto-approve Settings") },
        text = {
            Column(
                modifier = Modifier
                    .heightIn(max = 500.dp)
                    .verticalScroll(rememberScrollState()),
                verticalArrangement = Arrangement.spacedBy(8.dp),
            ) {
                Row(verticalAlignment = Alignment.CenterVertically) {
                    Text("Enable auto-approve", style = MaterialTheme.typography.titleSmall)
                    Spacer(Modifier.weight(1f))
                    Switch(checked = enabled, onCheckedChange = onEnabledChange)
                }
                Text(
                    text = "When enabled, approvals of selected types will be automatically approved without prompting. Use with caution. Connector actions must be checked for GitHub/Notion auto-approve.",
                    style = MaterialTheme.typography.bodySmall,
                    color = MaterialTheme.colorScheme.onSurfaceVariant,
                )
                Spacer(Modifier.height(8.dp))
                Text("Auto-approve these types:", style = MaterialTheme.typography.labelLarge)
                allTypes.forEach { (typeId, typeLabel) ->
                    val isChecked = effectiveTypes.split(",").map { it.trim() }.contains(typeId)
                    Row(
                        modifier = Modifier.fillMaxWidth(),
                        verticalAlignment = Alignment.CenterVertically,
                    ) {
                        Checkbox(checked = isChecked, onCheckedChange = { checked ->
                            val current = effectiveTypes.split(",").map { it.trim() }.filter { it.isNotBlank() }.toMutableSet()
                            if (checked) current.add(typeId) else current.remove(typeId)
                            onTypesChange(current.joinToString(","))
                        })
                        Spacer(Modifier.width(12.dp))
                        Text(typeLabel, style = MaterialTheme.typography.bodyMedium)
                    }
                }
            }
        },
        confirmButton = { TextButton(onClick = { onSave(); onDismiss() }) { Text("Save") } },
        dismissButton = { TextButton(onClick = onDismiss) { Text("Cancel") } },
    )
}