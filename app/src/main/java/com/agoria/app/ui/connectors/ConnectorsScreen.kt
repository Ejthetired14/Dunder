package com.agoria.app.ui.connectors

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
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.text.KeyboardOptions
import androidx.compose.foundation.verticalScroll
import androidx.compose.material3.AlertDialog
import androidx.compose.material3.ExperimentalMaterial3Api
import androidx.compose.material3.Icon
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedTextField
import androidx.compose.material3.Surface
import androidx.compose.material3.Switch
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.text.input.KeyboardType
import androidx.compose.ui.text.input.PasswordVisualTransformation
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import com.agoria.app.core.ConnectorAuth
import com.agoria.app.core.ConnectorCatalog
import com.agoria.app.core.ConnectorDef
import com.agoria.app.data.ConnectorEntity
import com.agoria.app.data.ConnectorLogEntity
import com.agoria.app.ui.AgoriaViewModel
import com.agoria.app.ui.components.PixelScaffold
import com.agoria.app.ui.components.connectorIcon
import com.agoria.app.util.Time
import org.json.JSONObject

@OptIn(ExperimentalMaterial3Api::class)
@Composable
fun ConnectorsScreen(
    viewModel: AgoriaViewModel,
    onBack: () -> Unit,
) {
    val connectors by viewModel.connectors.collectAsStateWithLifecycle()
    val requests by viewModel.pendingConnectorRequests.collectAsStateWithLifecycle()
    val logs by viewModel.connectorLogs.collectAsStateWithLifecycle()
    val setupConnectorId by viewModel.setupConnectorId.collectAsStateWithLifecycle()

    var selectedId by remember { mutableStateOf<String?>(null) }
    var savedConfig by remember { mutableStateOf<JSONObject?>(null) }
    var values by remember { mutableStateOf<Map<String, String>>(emptyMap()) }
    var error by remember { mutableStateOf("") }
    var showLogs by remember { mutableStateOf(false) }
    // One short list per category instead of every connector on one endless page.
    var expanded by remember { mutableStateOf(setOf("Needs attention")) }

    LaunchedEffect(setupConnectorId) {
        val id = setupConnectorId
        if (id != null) {
            selectedId = id
            viewModel.clearSetupConnector()
        }
    }

    LaunchedEffect(selectedId) {
        val id = selectedId
        if (id != null) {
            savedConfig = viewModel.getConnectorConfig(id)
            val def = ConnectorCatalog.get(id)
            values = def?.fields?.associate { field ->
                field.key to (savedConfig?.optString(field.key) ?: "")
            } ?: emptyMap()
            error = ""
        }
    }

    PixelScaffold(
        title = "Connectors",
        subtitle = "Office integrations and agent tools",
        onBack = onBack,
    ) { padding ->
        Column(
            modifier = Modifier
                .fillMaxSize()
                .padding(padding)
                .verticalScroll(rememberScrollState())
                .padding(horizontal = 12.dp, vertical = 8.dp),
        ) {
            requests.forEach { request ->
                Row(
                    modifier = Modifier
                        .fillMaxWidth()
                        .padding(vertical = 4.dp),
                    verticalAlignment = Alignment.CenterVertically,
                ) {
                    Text(
                        text = "Agent asks for ${request.connectorId}",
                        style = MaterialTheme.typography.bodyMedium,
                        modifier = Modifier.weight(1f),
                        maxLines = 1,
                        overflow = TextOverflow.Ellipsis,
                    )
                    TextButton(onClick = { viewModel.approveConnectorRequest(request.id) }) { Text("Approve") }
                    TextButton(onClick = { viewModel.denyConnectorRequest(request.id) }) { Text("Deny") }
                }
            }

            val attention = connectors.filter { it.status == "error" || it.status == "needs_setup" }
            val rest = connectors.filterNot { it in attention }
            if (attention.isNotEmpty()) {
                ConnectorGroup(
                    title = "Needs attention",
                    connectors = attention,
                    expanded = "Needs attention" in expanded,
                    onToggleGroup = {
                        expanded = if ("Needs attention" in expanded) expanded - "Needs attention" else expanded + "Needs attention"
                    },
                    onOpen = { id -> selectedId = id },
                    onToggle = { id, on -> viewModel.toggleConnector(id, on) },
                )
            }
            rest.groupBy { it.category.ifBlank { "Other" } }
                .toSortedMap()
                .forEach { (category, rows) ->
                    ConnectorGroup(
                        title = category,
                        connectors = rows,
                        expanded = category in expanded,
                        onToggleGroup = {
                            expanded = if (category in expanded) expanded - category else expanded + category
                        },
                        onOpen = { id -> selectedId = id },
                        onToggle = { id, on -> viewModel.toggleConnector(id, on) },
                    )
                }

            if (logs.isNotEmpty()) {
                Spacer(Modifier.height(8.dp))
                TextButton(onClick = { showLogs = !showLogs }, contentPadding = PaddingValues(0.dp)) {
                    Text(if (showLogs) "Hide recent calls" else "Recent calls (${logs.size})")
                }
                if (showLogs) {
                    logs.take(6).forEach { log -> ConnectorLogRow(log) }
                }
            }
        }
    }

    val selected = connectors.firstOrNull { it.id == selectedId }
    if (selectedId != null) {
        val def = ConnectorCatalog.get(selectedId!!)
        ConnectorSetupDialog(
            connector = selected,
            def = def,
            values = values,
            error = error,
            onValue = { key, value ->
                val sanitized = value
                    .replace(Regex("[\\u200e\\u200f\\u202a-\\u202e\\u0000-\\u001f\\u007f]"), "")
                    .trim()
                values = values + (key to sanitized)
            },
            onDismiss = { selectedId = null },
            onSave = {
                val id = selectedId
                if (id != null) {
                    val config = JSONObject()
                    var missing = ""
                    def?.fields?.forEach { field ->
                        val value = values[field.key] ?: ""
                        val existing = savedConfig?.optString(field.key) ?: ""
                        when {
                            value.isNotBlank() -> config.put(field.key, value)
                            field.secret && existing.isNotBlank() -> config.put(field.key, existing)
                            field.required && existing.isBlank() -> missing = field.label
                            existing.isNotBlank() -> config.put(field.key, existing)
                        }
                    }
                    if (missing.isNotBlank()) {
                        error = "Missing ${missing.lowercase()}."
                    } else {
                        viewModel.saveConnectorConfig(id, config)
                        selectedId = null
                    }
                }
            },
            onClear = {
                val id = selectedId
                if (id != null) {
                    viewModel.clearConnectorConfig(id)
                    selectedId = null
                }
            },
        )
    }
}

@Composable
private fun ConnectorGroup(
    title: String,
    connectors: List<ConnectorEntity>,
    expanded: Boolean,
    onToggleGroup: () -> Unit,
    onOpen: (String) -> Unit,
    onToggle: (String, Boolean) -> Unit,
) {
    val on = connectors.count { it.status != "disabled" }
    Row(
        modifier = Modifier
            .fillMaxWidth()
            .padding(top = 6.dp),
        verticalAlignment = Alignment.CenterVertically,
    ) {
        TextButton(onClick = onToggleGroup, contentPadding = PaddingValues(horizontal = 4.dp)) {
            Text(
                text = (if (expanded) "▾ " else "▸ ") + title,
                style = MaterialTheme.typography.titleSmall,
            )
        }
        Spacer(Modifier.weight(1f))
        Text(
            text = "$on/${connectors.size} on",
            style = MaterialTheme.typography.labelSmall,
            color = MaterialTheme.colorScheme.outline,
        )
    }
    if (expanded) {
        connectors.forEach { connector ->
            ConnectorRow(
                connector = connector,
                onOpen = { onOpen(connector.id) },
                onToggle = { enabled -> onToggle(connector.id, enabled) },
            )
        }
    }
}

@Composable
private fun ConnectorRow(
    connector: ConnectorEntity,
    onOpen: () -> Unit,
    onToggle: (Boolean) -> Unit,
) {
    val def = ConnectorCatalog.get(connector.id)
    val enabled = connector.status != "disabled"
    Row(
        modifier = Modifier
            .fillMaxWidth()
            .padding(vertical = 2.dp),
        verticalAlignment = Alignment.CenterVertically,
    ) {
        Icon(
            imageVector = connectorIcon(def?.iconKey ?: "http"),
            contentDescription = null,
            tint = MaterialTheme.colorScheme.onSurfaceVariant,
            modifier = Modifier.size(20.dp),
        )
        Spacer(Modifier.width(12.dp))
        Text(
            text = connector.name,
            style = MaterialTheme.typography.bodyLarge,
            modifier = Modifier.weight(1f),
            maxLines = 1,
            overflow = TextOverflow.Ellipsis,
        )
        StatusDot(status = connector.status)
        Spacer(Modifier.width(10.dp))
        Switch(checked = enabled, onCheckedChange = onToggle)
        TextButton(onClick = onOpen, contentPadding = PaddingValues(horizontal = 8.dp)) {
            Text(if (def?.auth == ConnectorAuth.ACCOUNT || connector.configEnc != null) "Edit" else "Set up")
        }
    }
}

@Composable
private fun StatusDot(status: String) {
    val color = when (status) {
        "ready", "configured" -> MaterialTheme.colorScheme.secondary
        "needs_setup" -> MaterialTheme.colorScheme.tertiary
        "error" -> MaterialTheme.colorScheme.error
        else -> MaterialTheme.colorScheme.outline
    }
    Surface(color = color, shape = MaterialTheme.shapes.small, modifier = Modifier.size(8.dp)) {}
}

@Composable
private fun ConnectorLogRow(log: ConnectorLogEntity) {
    Row(
        modifier = Modifier
            .fillMaxWidth()
            .padding(vertical = 3.dp),
        verticalAlignment = Alignment.CenterVertically,
    ) {
        Text(
            text = if (log.status == "ok") "ok" else "err",
            style = MaterialTheme.typography.labelSmall,
            color = if (log.status == "ok") MaterialTheme.colorScheme.secondary else MaterialTheme.colorScheme.error,
        )
        Spacer(Modifier.width(8.dp))
        Text(
            text = log.connectorId + "." + log.action,
            style = MaterialTheme.typography.bodySmall,
            modifier = Modifier.weight(1f),
            maxLines = 1,
            overflow = TextOverflow.Ellipsis,
        )
        Text(
            text = Time.relativeTime(log.createdAt),
            style = MaterialTheme.typography.labelSmall,
            color = MaterialTheme.colorScheme.outline,
        )
    }
}

@OptIn(ExperimentalMaterial3Api::class)
@Composable
private fun ConnectorSetupDialog(
    connector: ConnectorEntity?,
    def: ConnectorDef?,
    values: Map<String, String>,
    error: String,
    onValue: (String, String) -> Unit,
    onDismiss: () -> Unit,
    onSave: () -> Unit,
    onClear: () -> Unit,
) {
    val context = LocalContext.current
    // The office works in its own browser window with its own cookies. Nothing here reads the
    // accounts that are signed in to the phone.
    val browserSignedIn = remember {
        runCatching {
            val cookies = android.webkit.CookieManager.getInstance()
            cookies.getCookie("https://accounts.google.com").orEmpty().isNotBlank()
        }.getOrDefault(false)
    }

    if (def == null) {
        AlertDialog(
            onDismissRequest = onDismiss,
            title = { Text(connector?.name ?: "Connector") },
            text = { Text("Not in the built-in catalog.") },
            confirmButton = { TextButton(onClick = onDismiss) { Text("Close") } },
        )
        return
    }

    val usesDeviceAccounts = def.auth == ConnectorAuth.ACCOUNT

    AlertDialog(
        onDismissRequest = onDismiss,
        title = { Text(def.name) },
        text = {
            Column(
                modifier = Modifier
                    .heightIn(max = 480.dp)
                    .verticalScroll(rememberScrollState()),
                verticalArrangement = Arrangement.spacedBy(8.dp),
            ) {
                Text(
                    text = def.setupHelp,
                    style = MaterialTheme.typography.bodySmall,
                    color = MaterialTheme.colorScheme.onSurfaceVariant,
                )
                if (usesDeviceAccounts) {
                    BrowserSessionBlock(
                        signedIn = browserSignedIn,
                        onOpen = {
                            runCatching {
                                context.startActivity(
                                    com.agoria.app.browser.AgentBrowserActivity.intent(
                                        context,
                                        "https://accounts.google.com/ServiceLogin",
                                    ),
                                )
                            }
                        },
                    )
                }
                if (def.actions.isNotEmpty()) {
                    Text(
                        text = def.actions.joinToString(", ") { it.id },
                        style = MaterialTheme.typography.labelSmall,
                        color = MaterialTheme.colorScheme.outline,
                    )
                }
                def.fields.forEach { field ->
                    val saved = connector?.configEnc != null
                    OutlinedTextField(
                        value = values[field.key] ?: "",
                        onValueChange = { onValue(field.key, it) },
                        label = { Text(field.label) },
                        placeholder = {
                            Text(if (field.secret && saved) "Saved — leave blank to keep" else field.placeholder)
                        },
                        singleLine = true,
                        visualTransformation = if (field.secret) PasswordVisualTransformation() else androidx.compose.ui.text.input.VisualTransformation.None,
                        keyboardOptions = KeyboardOptions(
                            keyboardType = if (field.secret) KeyboardType.Password else KeyboardType.Text,
                        ),
                        modifier = Modifier.fillMaxWidth(),
                    )
                }
                if (error.isNotBlank()) {
                    Text(error, style = MaterialTheme.typography.bodySmall, color = MaterialTheme.colorScheme.error)
                }
                if (connector?.configEnc != null && !usesDeviceAccounts) {
                    TextButton(onClick = onClear, contentPadding = PaddingValues(0.dp)) { Text("Clear") }
                }
            }
        },
        confirmButton = {
            if (usesDeviceAccounts) TextButton(onClick = onDismiss) { Text("Done") }
            else TextButton(onClick = onSave) { Text("Save") }
        },
        dismissButton = { TextButton(onClick = onDismiss) { Text("Close") } },
    )
}

@Composable
private fun BrowserSessionBlock(signedIn: Boolean, onOpen: () -> Unit) {
    Surface(shape = MaterialTheme.shapes.medium, color = MaterialTheme.colorScheme.secondaryContainer) {
        Column(Modifier.padding(12.dp), verticalArrangement = Arrangement.spacedBy(4.dp)) {
            Text(
                text = if (signedIn) "Office browser is signed in" else "Office browser is signed out",
                style = MaterialTheme.typography.titleSmall,
            )
            Text(
                text = "Sign in once inside the office browser. Your phone's accounts are never read.",
                style = MaterialTheme.typography.bodySmall,
            )
            TextButton(onClick = onOpen, contentPadding = PaddingValues(0.dp)) {
                Text(if (signedIn) "Open office browser" else "Sign in")
            }
        }
    }
}