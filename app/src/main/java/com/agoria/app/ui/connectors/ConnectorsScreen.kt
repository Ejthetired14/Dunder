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
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.text.KeyboardOptions
import androidx.compose.foundation.verticalScroll
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.CheckCircle
import androidx.compose.material.icons.filled.ErrorOutline
import androidx.compose.material3.AlertDialog
import androidx.compose.material3.Button
import androidx.compose.material3.Card
import androidx.compose.material3.CardDefaults
import androidx.compose.material3.ExperimentalMaterial3Api
import androidx.compose.material3.Icon
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedTextField
import androidx.compose.material3.Scaffold
import androidx.compose.material3.Surface
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.material3.TopAppBar
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.text.input.KeyboardType
import androidx.compose.ui.text.input.PasswordVisualTransformation
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import com.agoria.app.core.ConnectorCatalog
import com.agoria.app.core.ConnectorDef
import com.agoria.app.data.ConnectorEntity
import com.agoria.app.data.ConnectorLogEntity
import com.agoria.app.ui.AgoriaViewModel
import com.agoria.app.ui.components.EmptyState
import com.agoria.app.ui.components.Pill
import com.agoria.app.ui.components.connectorIcon
import com.agoria.app.util.Time
import org.json.JSONObject

@OptIn(ExperimentalMaterial3Api::class)
@Composable
fun ConnectorsScreen(viewModel: AgoriaViewModel) {
    val connectors by viewModel.connectors.collectAsStateWithLifecycle()
    val requests by viewModel.pendingConnectorRequests.collectAsStateWithLifecycle()
    val logs by viewModel.connectorLogs.collectAsStateWithLifecycle()
    val setupConnectorId by viewModel.setupConnectorId.collectAsStateWithLifecycle()

    var selectedId by remember { mutableStateOf<String?>(null) }
    var savedConfig by remember { mutableStateOf<JSONObject?>(null) }
    var values by remember { mutableStateOf<Map<String, String>>(emptyMap()) }
    var error by remember { mutableStateOf("") }

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
                field.key to if (field.secret) "" else (savedConfig?.optString(field.key) ?: "")
            } ?: emptyMap()
            error = ""
        }
    }

    Scaffold(
        topBar = {
            TopAppBar(
                title = { 
                    Text("Connect", style = MaterialTheme.typography.titleLarge, textAlign = androidx.compose.ui.text.style.TextAlign.Start)
                },
            )
        },
    ) { padding ->
        Column(
            modifier = Modifier
                .fillMaxSize()
                .padding(padding)
                .verticalScroll(rememberScrollState())
                .padding(16.dp),
            verticalArrangement = Arrangement.spacedBy(12.dp),
        ) {
            if (requests.isNotEmpty()) {
                Text("Pending connector requests", style = MaterialTheme.typography.titleMedium)
                requests.forEach { request ->
                    ConnectorRequestRow(
                        request = request,
                        onApprove = { viewModel.approveConnectorRequest(request.id) },
                        onDeny = { viewModel.denyConnectorRequest(request.id) },
                    )
                }
            }

            Text("Catalog", style = MaterialTheme.typography.titleMedium)
            connectors.forEach { connector ->
                ConnectorRow(
                    connector = connector,
                    onClick = { selectedId = connector.id },
                )
            }

            if (logs.isNotEmpty()) {
                Spacer(Modifier.height(8.dp))
                Text("Recent calls", style = MaterialTheme.typography.titleMedium)
                logs.take(12).forEach { log ->
                    ConnectorLogRow(log = log)
                }
            }

            if (connectors.isEmpty()) {
                EmptyState(
                    title = "No connectors",
                    body = "The built-in catalog will appear here after the database is seeded.",
                )
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
            onValue = { key, value -> values = values + (key to value) },
            onDismiss = { selectedId = null },
            onSave = {
                val id = selectedId
                if (id != null) {
                    val config = JSONObject()
                    var missing = ""
                    def?.fields?.forEach { field ->
                        var value = values[field.key] ?: ""
                        val existing = savedConfig?.optString(field.key) ?: ""
                        when {
                            value.isNotBlank() -> {
                                // Sanitize ALL config values: remove invisible/control characters
                                value = sanitizeConfigValue(value)
                                config.put(field.key, value)
                            }
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
private fun ConnectorRequestRow(
    request: com.agoria.app.data.ConnectorRequestEntity,
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
                    text = request.connectorId.replaceFirstChar { it.uppercase() },
                    style = MaterialTheme.typography.titleMedium,
                    color = MaterialTheme.colorScheme.onTertiaryContainer,
                    modifier = Modifier.weight(1f),
                )
                Pill(text = "Requested", color = MaterialTheme.colorScheme.tertiary)
            }
            Spacer(Modifier.height(4.dp))
            Text(
                text = request.reason,
                style = MaterialTheme.typography.bodySmall,
                color = MaterialTheme.colorScheme.onTertiaryContainer,
            )
            if (request.scopes.isNotBlank()) {
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
private fun ConnectorRow(
    connector: ConnectorEntity,
    onClick: () -> Unit,
) {
    val def = ConnectorCatalog.get(connector.id)
    Card(
        onClick = onClick,
        modifier = Modifier.fillMaxWidth(),
        shape = MaterialTheme.shapes.large,
        colors = CardDefaults.cardColors(
            containerColor = MaterialTheme.colorScheme.surfaceVariant,
        ),
    ) {
        Row(
            modifier = Modifier.padding(16.dp),
            verticalAlignment = Alignment.CenterVertically,
        ) {
            Icon(
                imageVector = connectorIcon(def?.iconKey ?: "http"),
                contentDescription = null,
                tint = MaterialTheme.colorScheme.primary,
                modifier = Modifier.width(28.dp),
            )
            Spacer(Modifier.width(12.dp))
            Column(Modifier.weight(1f)) {
                Row(verticalAlignment = Alignment.CenterVertically) {
                    Text(connector.name, style = MaterialTheme.typography.titleMedium)
                    Spacer(Modifier.width(8.dp))
                    StatusPillForConnector(connector.status)
                }
                Text(
                    text = def?.description ?: "Custom connector.",
                    style = MaterialTheme.typography.bodySmall,
                    color = MaterialTheme.colorScheme.onSurfaceVariant,
                    maxLines = 2,
                    overflow = TextOverflow.Ellipsis,
                )
                Text(
                    text = "${def?.actions?.size ?: 0} actions \u2022 ${def?.category ?: "Custom"}",
                    style = MaterialTheme.typography.labelSmall,
                    color = MaterialTheme.colorScheme.outline,
                    maxLines = 1,
                    overflow = TextOverflow.Ellipsis,
                )
            }
            Text(
                text = if (connector.status == "needs_setup") "Set up" else "Edit",
                style = MaterialTheme.typography.labelLarge,
                color = MaterialTheme.colorScheme.primary,
            )
        }
    }
}

@Composable
private fun StatusPillForConnector(status: String) {
    val color = when (status) {
        "ready", "configured" -> MaterialTheme.colorScheme.secondary
        "needs_setup" -> MaterialTheme.colorScheme.tertiary
        "error" -> MaterialTheme.colorScheme.error
        else -> MaterialTheme.colorScheme.outline
    }
    Surface(
        color = color.copy(alpha = 0.14f),
        shape = MaterialTheme.shapes.small,
    ) {
        Text(
            text = status.replaceFirstChar { it.uppercase() },
            style = MaterialTheme.typography.labelSmall,
            color = color,
            modifier = Modifier.padding(horizontal = 8.dp, vertical = 3.dp),
        )
    }
}

@Composable
private fun ConnectorLogRow(log: ConnectorLogEntity) {
    Surface(
        modifier = Modifier.fillMaxWidth(),
        shape = MaterialTheme.shapes.small,
        color = MaterialTheme.colorScheme.surfaceVariant,
    ) {
        Row(Modifier.padding(12.dp)) {
            Icon(
                imageVector = if (log.status == "ok") Icons.Filled.CheckCircle else Icons.Filled.ErrorOutline,
                contentDescription = null,
                tint = if (log.status == "ok") MaterialTheme.colorScheme.secondary else MaterialTheme.colorScheme.error,
            )
            Spacer(Modifier.width(10.dp))
            Column(Modifier.weight(1f)) {
                Text(
                    text = "${log.connectorId}.${log.action}",
                    style = MaterialTheme.typography.labelLarge,
                )
                Text(
                    text = log.summary,
                    style = MaterialTheme.typography.bodySmall,
                    color = MaterialTheme.colorScheme.onSurfaceVariant,
                    maxLines = 2,
                    overflow = TextOverflow.Ellipsis,
                )
            }
            Spacer(Modifier.width(8.dp))
            Text(
                text = Time.relativeTime(log.createdAt),
                style = MaterialTheme.typography.labelSmall,
                color = MaterialTheme.colorScheme.outline,
            )
        }
    }
}

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
    if (def == null) {
        AlertDialog(
            onDismissRequest = onDismiss,
            title = { Text(connector?.name ?: "Connector") },
            text = {
                Text("This connector is not in the built-in catalog. Ask the agent to use the HTTP connector, or add a new connector definition to the app.")
            },
            confirmButton = { TextButton(onClick = onDismiss) { Text("Close") } },
        )
        return
    }

    AlertDialog(
        onDismissRequest = onDismiss,
        title = { Text("Set up ${def.name}") },
        text = {
            Column(
                modifier = Modifier
                    .heightIn(max = 500.dp)
                    .verticalScroll(rememberScrollState()),
                verticalArrangement = Arrangement.spacedBy(8.dp),
            ) {
                Text(
                    text = def.description,
                    style = MaterialTheme.typography.bodySmall,
                    color = MaterialTheme.colorScheme.onSurfaceVariant,
                )
                Text(
                    text = def.setupHelp,
                    style = MaterialTheme.typography.bodySmall,
                )
                def.fields.forEach { field ->
                    val saved = connector?.configEnc != null
                    OutlinedTextField(
                        value = values[field.key] ?: "",
                        onValueChange = { onValue(field.key, it) },
                        label = { Text(field.label) },
                        placeholder = {
                            Text(
                                if (field.secret && saved) "Saved — leave blank to keep" else field.placeholder,
                            )
                        },
                        supportingText = { Text(field.hint) },
                        singleLine = true,
                        visualTransformation = if (field.secret) PasswordVisualTransformation() else androidx.compose.ui.text.input.VisualTransformation.None,
                        keyboardOptions = KeyboardOptions(
                            keyboardType = if (field.secret) KeyboardType.Password else KeyboardType.Text,
                        ),
                        modifier = Modifier.fillMaxWidth(),
                    )
                }
                if (error.isNotBlank()) {
                    Text(
                        text = error,
                        style = MaterialTheme.typography.bodySmall,
                        color = MaterialTheme.colorScheme.error,
                    )
                }
                if (connector?.configEnc != null) {
                    TextButton(onClick = onClear) { Text("Clear saved configuration") }
                }
            }
        },
        confirmButton = { TextButton(onClick = onSave) { Text("Save") } },
        dismissButton = { TextButton(onClick = onDismiss) { Text("Cancel") } },
    )
}

/**
 * Removes invisible/control characters that can cause API errors.
 * Targets: U+0000-U+001F, U+007F-U+009F (control), U+00A0 (NBSP), U+1680, U+2000-U+200A, U+200B-U+200D (zero-width),
 * U+2028-U+202F (line/para separators), U+205F, U+2060, U+3000 (ideographic space), U+FEFF (BOM).
 * Keeps ASCII printable (U+0020-U+007E) and other visible Unicode.
 */
private fun sanitizeConfigValue(input: String): String {
    return input.trim().filter { ch ->
        val cp = ch.code
        // Allow ASCII printable range
        if (cp in 0x20..0x7E) return@filter true
        // Block known problematic invisible/control characters
        when (cp) {
            in 0x00..0x1F -> false // C0 controls
            in 0x7F..0x9F -> false // DEL + C1 controls
            in 0x00A0..0x00A0 -> false     // Non-breaking space
            0x1680 -> false     // Ogham space mark
            in 0x2000..0x200A -> false // En quad, em quad, en space, em space, etc.
            in 0x200B..0x200D -> false // Zero-width space, ZWNJ, ZWJ
            in 0x2028..0x202F -> false // Line/para separators, narrow no-break space
            0x205F -> false     // Medium mathematical space
            0x2060 -> false     // Word joiner
            0x3000 -> false     // Ideographic space
            0xFEFF -> false     // BOM / zero-width no-break space
            else -> true
        }
    }
}