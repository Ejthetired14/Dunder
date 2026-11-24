package com.agoria.app.ui.connectors

import androidx.compose.foundation.background
import androidx.compose.foundation.border
import androidx.compose.foundation.clickable
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
import androidx.compose.material3.ExperimentalMaterial3Api
import androidx.compose.material3.Icon
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.semantics.Role
import androidx.compose.ui.semantics.clearAndSetSemantics
import androidx.compose.ui.semantics.contentDescription
import androidx.compose.ui.semantics.onClick
import androidx.compose.ui.semantics.role
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
import com.agoria.app.ui.office.world.PixelButton
import com.agoria.app.ui.office.world.PixelDialog
import com.agoria.app.ui.office.world.PixelPanel
import com.agoria.app.ui.office.world.PixelSwitch
import com.agoria.app.ui.office.world.PixelTextField
import com.agoria.app.util.Time
import kotlinx.coroutines.delay
import kotlinx.coroutines.launch
import org.json.JSONObject
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
    val expanded = remember { mutableStateOf(setOf("Needs attention")) }

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
                    expanded.value = expanded.value + "Needs attention"
                } else {
                    expanded.value = expanded.value + category
                }
                
                // Build flat list to find index (non-composable version)
                val items = buildFlatListItems(connectors, requests, logs, expanded.value)
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
        expanded = expanded.value,
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
                            onToggle = {
                                // Toggle category expansion
                                val current = expanded.value
                                val newSet = if (item.title in current) current - item.title else current + item.title
                                expanded.value = newSet
                            },
                            onCount = item.onCount,
                            totalCount = item.totalCount,
                        )
                    }
                    is FlatItem.ConnectorRow -> {
                        ConnectorRow(
                            connector = item.connector,
                            onOpen = { selectedId = item.connector.id },
                            onToggle = { enabled -> viewModel.toggleConnector(item.connector.id, enabled) },
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

// Build flat list of items for LazyColumn (composable version with placeholder callbacks)
@Composable
private fun buildFlatList(
    connectors: List<ConnectorEntity>,
    requests: List<ConnectorRequestEntity>,
    logs: List<ConnectorLogEntity>,
    expanded: Set<String>,
): List<FlatItem> {
    return buildFlatListItems(connectors, requests, logs, expanded)
}

// Non-composable version for use in LaunchedEffect (just for index calculation)
private fun buildFlatListItems(
    connectors: List<ConnectorEntity>,
    requests: List<ConnectorRequestEntity>,
    logs: List<ConnectorLogEntity>,
    expanded: Set<String>,
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
            isExpanded = "Needs attention" in expanded,
            onToggle = { /* handled in CategoryHeaderRow */ },
            onCount = onCount,
            totalCount = attention.size,
        ))
        if ("Needs attention" in expanded) {
            attention.forEach { connector ->
                items.add(FlatItem.ConnectorRow(
                    connector = connector,
                    onOpen = { /* handled in ConnectorRow */ },
                    onToggle = { /* handled in ConnectorRow */ },
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
                isExpanded = category in expanded,
                onToggle = { /* handled in CategoryHeaderRow */ },
                onCount = onCount,
                totalCount = rows.size,
            ))
            if (category in expanded) {
                rows.forEach { connector ->
                    items.add(FlatItem.ConnectorRow(
                        connector = connector,
                        onOpen = { /* handled in ConnectorRow */ },
                        onToggle = { /* handled in ConnectorRow */ },
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
    val description = "Agent asks for ${request.connectorId}"
    Row(
        modifier = Modifier
            .fillMaxWidth()
            .padding(vertical = 4.dp)
            .clearAndSetSemantics {
                contentDescription = description
                role = Role.Button
            },
        verticalAlignment = Alignment.CenterVertically,
    ) {
        Text(
            text = description,
            style = MaterialTheme.typography.bodyMedium,
            modifier = Modifier.weight(1f),
            maxLines = 1,
            overflow = TextOverflow.Ellipsis,
        )
        PixelButton(text = "Approve", onClick = { viewModel.approveConnectorRequest(request.id) }, small = true)
        PixelButton(text = "Deny", onClick = { viewModel.denyConnectorRequest(request.id) }, small = true, fill = Pal.tan)
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
    val open = onToggle
    Row(
        modifier = Modifier
            .fillMaxWidth()
            .padding(top = 6.dp),
        verticalAlignment = Alignment.CenterVertically,
    ) {
        PixelButton(
            text = (if (isExpanded) "▾ " else "▸ ") + title,
            onClick = onToggle,
            small = true,
            fill = Pal.tan,
            modifier = Modifier
                .clearAndSetSemantics {
                    contentDescription = "${if (isExpanded) "Collapse" else "Expand"} $title category, $onCount of $totalCount connectors on"
                    role = Role.Button
                    onClick(label = "${if (isExpanded) "Collapse" else "Expand"} $title") { open(); true }
                }
        )
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
    val open = onOpen
    val description = buildString {
        append(connector.name).append(", ")
        append(if (enabled) "enabled" else "disabled").append(". ")
        append(def?.description ?: "")
    }
    
    // Simple highlight - amber border when highlighted
    val borderWidth = if (isHighlighted) 2.dp else 0.dp
    val borderColor = if (isHighlighted) Pal.amber else Color.Transparent
    val backgroundColor = if (isHighlighted) Pal.amber.copy(alpha = 0.1f) else Color.Transparent

    Row(
        modifier = Modifier
            .fillMaxWidth()
            .padding(vertical = 2.dp)
            .border(
                width = borderWidth,
                color = borderColor,
                shape = MaterialTheme.shapes.medium
            )
            .background(color = backgroundColor)
            .padding(horizontal = 8.dp, vertical = 4.dp)
            .clickable(onClick = onOpen)
            .clearAndSetSemantics {
                contentDescription = description
                role = Role.Button
                onClick(label = "Open ${connector.name}") { open(); true }
            },
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
        PixelSwitch(checked = enabled, onChange = onToggle)
        PixelButton(
            text = if (def?.auth == ConnectorAuth.ACCOUNT || connector.configEnc != null) "Edit" else "Set up",
            onClick = onOpen,
            small = true,
        )
    }
}

@Composable
private fun LogsToggleRow(
    isExpanded: Boolean,
    onToggle: () -> Unit,
    count: Int,
) {
    val open = onToggle
    PixelButton(
        text = if (isExpanded) "Hide recent calls" else "Recent calls ($count)",
        onClick = onToggle,
        small = true,
        fill = Pal.tan,
        modifier = Modifier
            .clearAndSetSemantics {
                contentDescription = if (isExpanded) "Hide recent calls" else "Show recent calls, $count total"
                role = Role.Button
                onClick(label = if (isExpanded) "Hide recent calls" else "Show recent calls") { open(); true }
            }
    )
}

@Composable
private fun StatusDot(status: String) {
    val color = when (status) {
        "ready", "configured" -> Pal.teal
        "needs_setup" -> Pal.amber
        "error" -> Pal.red
        else -> Pal.wood
    }
    PixelPanel(fill = color, modifier = Modifier.size(8.dp)) {}
}

@Composable
private fun ConnectorLogRow(log: ConnectorLogEntity) {
    val description = buildString {
        append(if (log.status == "ok") "Success" else "Error").append(", ")
        append(log.connectorId).append(".").append(log.action).append(", ")
        append(Time.relativeTime(log.createdAt))
    }
    Row(
        modifier = Modifier
            .fillMaxWidth()
            .padding(vertical = 3.dp)
            .clearAndSetSemantics {
                contentDescription = description
                role = Role.Button
            },
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
        PixelDialog(
            onDismiss = onDismiss,
            title = connector?.name ?: "Connector",
        ) {
            Text("Not in the built-in catalog.")
            Spacer(Modifier.height(8.dp))
            Row(
                modifier = Modifier.fillMaxWidth(),
                horizontalArrangement = Arrangement.End,
            ) {
                PixelButton(text = "Close", onClick = onDismiss, small = true, fill = Pal.tan)
            }
        }
        return
    }

    val usesDeviceAccounts = def.auth == ConnectorAuth.ACCOUNT

    PixelDialog(
        onDismiss = onDismiss,
        title = def.name,
    ) {
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
                PixelTextField(
                    value = values[field.key] ?: "",
                    onValueChange = { onValue(field.key, it) },
                    label = field.label,
                    singleLine = true,
                    password = field.secret,
                    hint = if (field.secret && saved) "Saved — leave blank to keep" else field.placeholder,
                    labelColor = Pal.navy,
                    hintColor = Pal.tan,
                    modifier = Modifier.fillMaxWidth(),
                )
            }
            if (error.isNotBlank()) {
                Text(error, style = MaterialTheme.typography.bodySmall, color = Pal.red)
            }
            if (connector?.configEnc != null && !usesDeviceAccounts) {
                PixelButton(text = "Clear", onClick = onClear, small = true, fill = Pal.tan)
            }
        }
        Spacer(Modifier.height(8.dp))
        Row(
            modifier = Modifier.fillMaxWidth(),
            horizontalArrangement = Arrangement.spacedBy(8.dp),
        ) {
            if (usesDeviceAccounts) {
                PixelButton(text = "Done", onClick = onDismiss, modifier = Modifier.weight(1f), fill = Pal.tan)
            } else {
                PixelButton(text = "Save", onClick = onSave, modifier = Modifier.weight(1f))
            }
            PixelButton(text = "Close", onClick = onDismiss, modifier = Modifier.weight(1f), fill = Pal.tan)
        }
    )
}

@Composable
private fun BrowserSessionBlock(signedIn: Boolean, onOpen: () -> Unit) {
    PixelPanel(fill = Pal.teal.copy(alpha = 0.14f)) {
        Column(Modifier.padding(12.dp), verticalArrangement = Arrangement.spacedBy(4.dp)) {
            Text(
                text = if (signedIn) "Office browser is signed in" else "Office browser is signed out",
                style = MaterialTheme.typography.titleSmall,
            )
            Text(
                text = "Sign in once inside the office browser. Your phone's accounts are never read.",
                style = MaterialTheme.typography.bodySmall,
            )
            PixelButton(text = if (signedIn) "Open office browser" else "Sign in", onClick = onOpen, small = true)
        }
    }
}