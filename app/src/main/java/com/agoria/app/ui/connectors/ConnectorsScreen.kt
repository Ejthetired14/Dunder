package com.agoria.app.ui.connectors

import androidx.compose.animation.animateColorAsState
import androidx.compose.animation.animateFloatAsState
import androidx.compose.animation.core.tween
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
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.LazyListScope
import androidx.compose.foundation.lazy.items
import androidx.compose.foundation.lazy.itemsIndexed
import androidx.compose.foundation.lazy.rememberLazyListState
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
import androidx.compose.ui.draw.clip
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.outline.RoundedRectOutline
import androidx.compose.ui.graphics.outline.asOutline
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.text.input.KeyboardType
import androidx.compose.ui.text.input.PasswordVisualTransformation
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import com.agoria.app.core.ConnectorAuth
import com.agoria.app.core.ConnectorCatalog
import com.agoria.app.core.ConnectorDef
import com.agoria.app.data.ConnectorEntity
import com.agoria.app.data.ConnectorLogEntity
import com.agoria.app.data.ConnectorRequestEntity
import com.agoria.app.ui.AgoriaViewModel
import com.agoria.app.ui.components.PixelScaffold
import com.agoria.app.ui.components.connectorIcon
import com.agoria.app.ui.office.world.Pal
import com.agoria.app.util.Time
import kotlinx.coroutines.delay
import kotlinx.coroutines.launch
import org.json.JSONObject

@OptIn(ExperimentalMaterial3Api::class)
@Composable
fun ConnectorsScreen(
    viewModel: AgoriaViewModel,
    onBack: () -> Unit,
    initialFocusId: String? = null,
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

    // LazyListState for scrolling to focused connector
    val listState = rememberLazyListState()
    // Track which connector is currently highlighted
    var highlightId by remember { mutableStateOf<String?>(null) }
    // Track if we've already handled the initial focus
    var focusHandled by remember { mutableStateOf(false) }

    LaunchedEffect(initialFocusId) {
        val focusId = initialFocusId
        if (focusId != null && !focusHandled) {
            focusHandled = true
            // Find the connector and its category
            val allConnectors = connectors
            val connector = allConnectors.find { it.id == focusId }
            if (connector != null) {
                val category = connector.category.ifBlank { "Other" }
                val attention = allConnectors.filter { it.status == "error" || it.status == "needs_setup" }
                val isInAttention = connector in attention
                
                // Expand the correct category
                if (isInAttention) {
                    expanded = expanded + "Needs attention"
                } else {
                    expanded = expanded + category
                }
                
                // Build flat list to find index
                val items = buildFlatList(connectors, requests, logs, expanded)
                val index = items.indexOfFirst { it.connectorId == focusId }
                if (index >= 0) {
                    // Small delay to let layout happen, then scroll and highlight
                    launch {
                        delay(100)
                        listState.animateScrollToItem(index)
                        highlightId = focusId
                        delay(2000)
                        highlightId = null
                    }
                }
            }
        }
    }

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

    // Build flat list of items for LazyColumn
    val flatItems = buildFlatList(
        connectors = connectors,
        requests = requests,
        logs = logs,
        expanded = expanded,
        onConnectorOpen = { id -> selectedId = id },
        onConnectorToggle = { id, on -> viewModel.toggleConnector(id, on) },
    )

    PixelScaffold(
        title = "Connectors",
        subtitle = "Office integrations and agent tools",
        onBack = onBack,
    ) { padding ->
        LazyColumn(
            modifier = Modifier
                .fillMaxSize()
                .padding(padding)
                .padding(horizontal = 12.dp, vertical = 8.dp),
            state = listState,
            contentPadding = PaddingValues(0.dp),
            verticalArrangement = Arrangement.spacedBy(4.dp),
        ) {
            itemsIndexed(flatItems) { index, item ->
                when (item) {
                    is FlatItem.Request -> {
                        RequestRow(request = item.request, viewModel = viewModel)
                    }
                    is FlatItem.CategoryHeader -> {
                        CategoryHeaderRow(
                            title = item.title,
                            isExpanded = item.isExpanded,
                            onToggle = item.onToggle,
                            onCount = item.onCount,
                            totalCount = item.totalCount,
                        )
                    }
                    is FlatItem.ConnectorRow -> {
                        ConnectorRow(
                            connector = item.connector,
                            onOpen = item.onOpen,
                            onToggle = item.onToggle,
                            isHighlighted = highlightId == item.connector.id,
                        )
                    }
                    is FlatItem.LogsToggle -> {
                        LogsToggleRow(
                            isExpanded = showLogs,
                            onToggle = { showLogs = !showLogs },
                            count = logs.size,
                        )
                    }
                    is FlatItem.LogRow -> {
                        if (showLogs) {
                            ConnectorLogRow(log = item.log)
                        }
                    }
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

// Flat item types for LazyColumn
sealed interface FlatItem {
    data class Request(val request: ConnectorRequestEntity) : FlatItem {
        override val connectorId: String? = null
    }
    data class CategoryHeader(
        val title: String,
        val isExpanded: Boolean,
        val onToggle: () -> Unit,
        val onCount: Int,
        val totalCount: Int,
    ) : FlatItem {
        override val connectorId: String? = null
    }
    data class ConnectorRow(
        val connector: ConnectorEntity,
        val onOpen: () -> Unit,
        val onToggle: (Boolean) -> Unit,
    ) : FlatItem {
        override val connectorId: String = connector.id
    }
    data class LogsToggle(
        val isExpanded: Boolean,
        val onToggle: () -> Unit,
        val count: Int,
    ) : FlatItem {
        override val connectorId: String? = null
    }
    data class LogRow(val log: ConnectorLogEntity) : FlatItem {
        override val connectorId: String? = null
    }
    
    val connectorId: String?
}

// Build flat list of items for LazyColumn
@Composable
private fun buildFlatList(
    connectors: List<ConnectorEntity>,
    requests: List<ConnectorRequestEntity>,
    logs: List<ConnectorLogEntity>,
    expanded: MutableState<Set<String>>,
    onConnectorOpen: (String) -> Unit,
    onConnectorToggle: (String, Boolean) -> Unit,
): List<FlatItem> {
    val items = mutableListOf<FlatItem>()

    requests.forEach { request ->
        items.add(FlatItem.Request(request))
    }

    val attention = connectors.filter { it.status == "error" || it.status == "needs_setup" }
    val rest = connectors.filterNot { it in attention }
    if (attention.isNotEmpty()) {
        val onCount = attention.count { it.status != "disabled" }
        items.add(FlatItem.CategoryHeader(
            title = "Needs attention",
            isExpanded = "Needs attention" in expanded.value,
            onToggle = { expanded.value = if ("Needs attention" in expanded.value) expanded.value - "Needs attention" else expanded.value + "Needs attention" },
            onCount = onCount,
            totalCount = attention.size,
        ))
        if ("Needs attention" in expanded.value) {
            attention.forEach { connector ->
                items.add(FlatItem.ConnectorRow(
                    connector = connector,
                    onOpen = { onConnectorOpen(connector.id) },
                    onToggle = { enabled -> onConnectorToggle(connector.id, enabled) },
                ))
            }
        }
    }
    rest.groupBy { it.category.ifBlank { "Other" } }
        .toSortedMap()
        .forEach { (category, rows) ->
            val onCount = rows.count { it.status != "disabled" }
            items.add(FlatItem.CategoryHeader(
                title = category,
                isExpanded = category in expanded.value,
                onToggle = { expanded.value = if (category in expanded.value) expanded.value - category else expanded.value + category },
                onCount = onCount,
                totalCount = rows.size,
            ))
            if (category in expanded.value) {
                rows.forEach { connector ->
                    items.add(FlatItem.ConnectorRow(
                        connector = connector,
                        onOpen = { onConnectorOpen(connector.id) },
                        onToggle = { enabled -> onConnectorToggle(connector.id, enabled) },
                    ))
                }
            }
        }

    if (logs.isNotEmpty()) {
        items.add(FlatItem.LogsToggle(
            isExpanded = false, // Will be handled in component
            onToggle = { /* handled in component */ },
            count = logs.size,
        ))
        logs.take(6).forEach { log ->
            items.add(FlatItem.LogRow(log))
        }
    }

    return items
}

@Composable
private fun RequestRow(
    request: ConnectorRequestEntity,
    viewModel: AgoriaViewModel,
) {
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

@Composable
private fun CategoryHeaderRow(
    title: String,
    isExpanded: Boolean,
    onToggle: () -> Unit,
    onCount: Int,
    totalCount: Int,
) {
    Row(
        modifier = Modifier
            .fillMaxWidth()
            .padding(top = 6.dp),
        verticalAlignment = Alignment.CenterVertically,
    ) {
        TextButton(onClick = onToggle, contentPadding = PaddingValues(horizontal = 4.dp)) {
            Text(
                text = (if (isExpanded) "▾ " else "▸ ") + title,
                style = MaterialTheme.typography.titleSmall,
            )
        }
        Spacer(Modifier.weight(1f))
        Text(
            text = "$onCount/$totalCount on",
            style = MaterialTheme.typography.labelSmall,
            color = Pal.wood,
        )
    }
}

@Composable
private fun ConnectorRow(
    connector: ConnectorEntity,
    onOpen: () -> Unit,
    onToggle: (Boolean) -> Unit,
    isHighlighted: Boolean,
) {
    val def = ConnectorCatalog.get(connector.id)
    val enabled = connector.status != "disabled"
    
    // Highlight animation
    val highlightAlpha by animateFloatAsState(
        targetValue = if (isHighlighted) 1f else 0f,
        animationSpec = tween(500)
    )
    val highlightColor by animateColorAsState(
        targetValue = if (isHighlighted) Pal.amber.copy(alpha = 0.3f) : Color.Transparent,
        animationSpec = tween(500)
    )
    val borderWidth by animateFloatAsState(
        targetValue = if (isHighlighted) 2f else 0f,
        animationSpec = tween(500)
    )

    Row(
        modifier = Modifier
            .fillMaxWidth()
            .padding(vertical = 2.dp)
            .border(
                width = borderWidth.dp,
                color = Pal.amber.copy(alpha = highlightAlpha),
                shape = MaterialTheme.shapes.medium
            )
            .background(color = highlightColor)
            .padding(horizontal = 8.dp, vertical = 4.dp),
        verticalAlignment = Alignment.CenterVertically,
    ) {
        Icon(
            imageVector = connectorIcon(def?.iconKey ?: "http"),
            contentDescription = null,
            tint = Pal.tan,
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
private fun LogsToggleRow(
    isExpanded: Boolean,
    onToggle: () -> Unit,
    count: Int,
) {
    TextButton(onClick = onToggle, contentPadding = PaddingValues(0.dp)) {
        Text(if (isExpanded) "Hide recent calls" else "Recent calls ($count)")
    }
}

@Composable
private fun StatusDot(status: String) {
    val color = when (status) {
        "ready", "configured" -> Pal.teal
        "needs_setup" -> Pal.amber
        "error" -> Pal.red
        else -> Pal.wood
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
            color = if (log.status == "ok") Pal.teal else Pal.red,
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
            color = Pal.wood,
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
                    color = Pal.tan,
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
                        color = Pal.wood,
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
                    Text(error, style = MaterialTheme.typography.bodySmall, color = Pal.red)
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
    Surface(shape = MaterialTheme.shapes.medium, color = Pal.teal.copy(alpha = 0.14f)) {
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