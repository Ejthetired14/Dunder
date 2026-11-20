package com.agoria.app.ui.settings

import android.content.Context
import android.content.Intent
import android.net.Uri
import android.os.Process
import android.provider.Settings
import androidx.activity.compose.rememberLauncherForActivityResult
import androidx.activity.result.contract.ActivityResultContracts
import androidx.compose.foundation.background
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.PaddingValues
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.verticalScroll
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.ArrowBack
import androidx.compose.material.icons.filled.Bolt
import androidx.compose.material.icons.filled.CloudDownload
import androidx.compose.material.icons.filled.CloudUpload
import androidx.compose.material.icons.filled.Download
import androidx.compose.material.icons.filled.FileDownload
import androidx.compose.material.icons.filled.FileUpload
import androidx.compose.material.icons.filled.PlayArrow
import androidx.compose.material.icons.filled.Settings
import androidx.compose.material.icons.filled.SystemUpdate
import androidx.compose.material3.AlertDialog
import androidx.compose.material3.Button
import androidx.compose.material3.Card
import androidx.compose.material3.CardDefaults
import androidx.compose.material3.CircularProgressIndicator
import androidx.compose.material3.ExperimentalMaterial3Api
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.LinearProgressIndicator
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedTextField
import androidx.compose.material3.Scaffold
import androidx.compose.material3.Slider
import androidx.compose.material3.Snackbar
import androidx.compose.material3.SnackbarDuration
import androidx.compose.material3.SnackbarHost
import androidx.compose.material3.SnackbarHostState
import androidx.compose.material3.Surface
import androidx.compose.material3.Switch
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.material3.TopAppBar
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.collectAsState
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberCoroutineScope
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import kotlin.math.roundToInt
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.text.style.TextAlign
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import com.agoria.app.AgoriaApplication
import com.agoria.app.core.ProviderCatalog
import com.agoria.app.core.ProviderSpec
import com.agoria.app.data.ConnectorEntity
import com.agoria.app.data.ProviderEntity
import com.agoria.app.data.RevenueEntity
import com.agoria.app.ui.AgoriaViewModel
import com.agoria.app.ui.ProviderTest
import com.agoria.app.util.Files
import kotlinx.coroutines.launch

@OptIn(ExperimentalMaterial3Api::class)
@Composable
fun SettingsScreen(
    viewModel: AgoriaViewModel,
    onBack: () -> Unit,
) {
    val context = androidx.compose.ui.platform.LocalContext.current
    val settings: com.agoria.app.data.SettingsEntity? by viewModel.settings.collectAsStateWithLifecycle()
    val providers: List<ProviderEntity> by viewModel.providers.collectAsState()
    val providerTests: Map<String, ProviderTest> by viewModel.providerTests.collectAsState()
    val revenueEntities: List<RevenueEntity> by viewModel.revenue.collectAsState()
    val connectors: List<ConnectorEntity> by viewModel.connectors.collectAsState()

    val showReset = remember { mutableStateOf(false) }
    val tick = remember { mutableStateOf(120) }
    val cooldown = remember { mutableStateOf(90) }
    val agentSplit = remember { mutableStateOf(50) }
    val userSplit = remember { mutableStateOf(50) }
    val showUnknownAppsDialog = remember { mutableStateOf(false) }
    val showRestartDialog = remember { mutableStateOf(false) }

    val (currentVersionName, currentVersionCode) = rememberCurrentVersion(context)
    val uiScope = rememberCoroutineScope()
    val snackbarHostState = remember { SnackbarHostState() }
    val scope = rememberCoroutineScope()

    // File picker for import
    val pickImportFileLauncher = rememberLauncherForActivityResult(
        contract = ActivityResultContracts.GetContent(),
    ) { uri: Uri? ->
        uri?.let { fileUri ->
            viewModel.importData(java.io.File(fileUri.path)) { success ->
                scope.launch { snackbarHostState.showSnackbar(if (success) "Import successful" else "Import failed") }
            }
        }
    }

    // File picker for export (user chooses where to save)
    val createExportFileLauncher = rememberLauncherForActivityResult(
        contract = ActivityResultContracts.CreateDocument("application/json"),
    ) { uri: Uri? ->
        uri?.let { exportUri ->
            viewModel.exportDataToUri(context, exportUri) { success, message ->
                scope.launch { snackbarHostState.showSnackbar(message ?: (if (success) "Export successful" else "Export failed"), duration = SnackbarDuration.Long) }
            }
        }
    }

    // Compute revenue summary
    val totalRevenueCents = remember(revenueEntities) { revenueEntities.sumOf { it.amountCents } }
    val agentRevenueCents = remember(revenueEntities) { revenueEntities.filter { it.source == "agent_share" }.sumOf { it.amountCents } }
    val userRevenueCents = remember(revenueEntities) { revenueEntities.filter { it.source == "user_share" }.sumOf { it.amountCents } }
    val agentBreakdown: Map<String, Long> = remember(revenueEntities) {
        revenueEntities
            .groupBy { it.agentId }
            .mapValues { (_, list) -> list.sumOf { it.amountCents } }
    }
    val recentEvents: List<RevenueEntity> = remember(revenueEntities) { revenueEntities.sortedByDescending { it.createdAt }.take(20) }

    // Backup to Google Drive
    fun backupToGoogleDrive() {
        uiScope.launch {
            val result = viewModel.backupToGoogleDrive()
            scope.launch { snackbarHostState.showSnackbar(if (result.ok) "Backup uploaded" else "Backup failed: ${result.error}") }
        }
    }

    // Restore from Google Drive
    fun restoreFromGoogleDrive() {
        uiScope.launch {
            val result = viewModel.restoreFromGoogleDrive()
            scope.launch { snackbarHostState.showSnackbar(if (result.ok) "Restored from Google Drive" else "Restore failed: ${result.error}") }
        }
    }

    LaunchedEffect(settings) {
        settings?.let {
            tick.value = it.tickIntervalSeconds
            cooldown.value = it.agentCooldownSeconds
            agentSplit.value = it.agentProfitSharePercent
            userSplit.value = it.userProfitSharePercent
        }
    }

    Scaffold(
        topBar = {
            TopAppBar(
                title = { Text("Settings") },
                navigationIcon = {
                    IconButton(onClick = onBack) {
                        Icon(Icons.Default.ArrowBack, contentDescription = "Back")
                    }
                },
            )
        },
        snackbarHost = { SnackbarHost(snackbarHostState) }
    ) { padding ->
        Column(
            modifier = Modifier
                .fillMaxSize()
                .padding(padding)
                .verticalScroll(rememberScrollState())
                .padding(16.dp),
            verticalArrangement = Arrangement.spacedBy(14.dp),
        ) {
            SectionCard(title = "Autonomy") {
                Column(verticalArrangement = Arrangement.spacedBy(12.dp)) {
                    // Main autonomy toggle
                    Row(
                        modifier = Modifier.fillMaxWidth(),
                        horizontalArrangement = Arrangement.SpaceBetween,
                        verticalAlignment = Alignment.CenterVertically,
                    ) {
                        Column {
                            Text("Enable Autonomy", style = MaterialTheme.typography.titleSmall)
                            Text("Let agents run automatically in the background", style = MaterialTheme.typography.bodySmall, color = MaterialTheme.colorScheme.onSurfaceVariant)
                        }
                        Switch(
                            checked = settings?.autonomyEnabled ?: false,
                            onCheckedChange = { enabled ->
                                viewModel.updateSettings { it.copy(autonomyEnabled = enabled) }
                            }
                        )
                    }

                    Spacer(Modifier.height(8.dp))

                    Row(
                        modifier = Modifier.fillMaxWidth(),
                        horizontalArrangement = Arrangement.SpaceBetween,
                    ) {
                        Column {
                            Text("Agent loop interval", style = MaterialTheme.typography.titleSmall)
                            Text("How often agents wake up to act", style = MaterialTheme.typography.bodySmall, color = MaterialTheme.colorScheme.onSurfaceVariant)
                        }
                        Text(text = "${tick}s", style = MaterialTheme.typography.bodyLarge, color = MaterialTheme.colorScheme.primary)
                    }
                    Slider(
                        value = tick.value.toFloat(),
                        onValueChange = { tick.value = it.roundToInt() },
                        valueRange = 10f..600f,
                        modifier = Modifier.fillMaxWidth(),
                    )

                    Spacer(Modifier.height(8.dp))

                    Row(
                        modifier = Modifier.fillMaxWidth(),
                        horizontalArrangement = Arrangement.SpaceBetween,
                    ) {
                        Column {
                            Text("Agent cooldown", style = MaterialTheme.typography.titleSmall)
                            Text("Minimum time between agent actions", style = MaterialTheme.typography.bodySmall, color = MaterialTheme.colorScheme.onSurfaceVariant)
                        }
                        Text(text = "${cooldown}s", style = MaterialTheme.typography.bodyLarge, color = MaterialTheme.colorScheme.primary)
                    }
                    Slider(
                        value = cooldown.value.toFloat(),
                        onValueChange = { cooldown.value = it.roundToInt() },
                        valueRange = 5f..300f,
                        modifier = Modifier.fillMaxWidth(),
                    )

                    Spacer(Modifier.height(8.dp))

                    Row(
                        modifier = Modifier.fillMaxWidth(),
                        horizontalArrangement = Arrangement.SpaceBetween,
                    ) {
                        Column {
                            Text("Profit split — Agent / You", style = MaterialTheme.typography.titleSmall)
                            Text("Percentage of revenue the agent keeps", style = MaterialTheme.typography.bodySmall, color = MaterialTheme.colorScheme.onSurfaceVariant)
                        }
                    }
                    Row(
                        modifier = Modifier.fillMaxWidth(),
                        horizontalArrangement = Arrangement.spacedBy(16.dp),
                    ) {
                        Column(Modifier.weight(1f)) {
                            Text("Agent: ${agentSplit.value}%", style = MaterialTheme.typography.bodyMedium)
                            Slider(
                                value = agentSplit.value.toFloat(),
                                onValueChange = {
                                    agentSplit.value = it.roundToInt()
                                    userSplit.value = 100 - agentSplit.value
                                },
                                valueRange = 0f..100f,
                                modifier = Modifier.fillMaxWidth(),
                            )
                        }
                        Column(Modifier.weight(1f)) {
                            Text("You: ${userSplit.value}%", style = MaterialTheme.typography.bodyMedium)
                            Slider(
                                value = userSplit.value.toFloat(),
                                onValueChange = {
                                    userSplit.value = it.roundToInt()
                                    agentSplit.value = 100 - userSplit.value
                                },
                                valueRange = 0f..100f,
                                modifier = Modifier.fillMaxWidth(),
                            )
                        }
                    }

                    Spacer(Modifier.height(8.dp))

                    Button(
                        onClick = {
                            settings?.let { viewModel.updateSettings { it.copy(tickIntervalSeconds = tick.value, agentCooldownSeconds = cooldown.value, agentProfitSharePercent = agentSplit.value, userProfitSharePercent = userSplit.value) } }
                        },
                    ) { Text("Save Autonomy Settings") }
                }
            }

            SectionCard(title = "Providers") {
                providers.forEach { provider: ProviderEntity ->
                    val spec = ProviderCatalog.byId[provider.id] ?: ProviderSpec(
                        id = provider.id, name = provider.id, baseUrl = "", keyRequired = false,
                        keyLabel = "", keyHint = "", docsUrl = "", freeNote = "",
                        models = listOf(), defaultModel = "",
                    )
                    val test = providerTests[provider.id]
                    ProviderRow(
                        provider = provider,
                        spec = spec,
                        test = test,
                        onToggle = { viewModel.setProviderEnabled(provider.id, it) },
                        onSaveKey = { key -> viewModel.saveProviderKey(provider.id, key) },
                        onSaveModel = { model -> viewModel.setProviderModel(provider.id, model) },
                        onSaveExtra = { extra -> viewModel.saveProviderExtra(provider.id, extra) },
                        onTest = { viewModel.testProvider(provider.id) },
                        compact = true,
                    )
                }
            }

            SectionCard(title = "Revenue") {
                Text("Total: ${formatCents(totalRevenueCents)}", style = MaterialTheme.typography.titleMedium)
                Text("Agent share: ${formatCents(agentRevenueCents)}  |  Your share: ${formatCents(userRevenueCents)}", style = MaterialTheme.typography.bodyMedium)
                Spacer(Modifier.height(8.dp))
                Text("Agents", style = MaterialTheme.typography.labelLarge)
                Column {
                    for (entry in agentBreakdown.entries) {
                        RevenueRow(agentName = entry.key, amountCents = entry.value)
                    }
                }
                Spacer(Modifier.height(8.dp))
                Text("Recent events", style = MaterialTheme.typography.labelLarge)
                Column {
                    for (event in recentEvents) {
                        RevenueEventRow(
                            agentName = event.agentId,
                            source = event.source,
                            amountCents = event.amountCents,
                            timestamp = event.createdAt,
                        )
                    }
                }
            }

            SectionCard(title = "Data & privacy") {
                Text(
                    text = "API keys and connector credentials are encrypted with a key in the Android Keystore. The database stays on this device.",
                    style = MaterialTheme.typography.bodySmall,
                    color = MaterialTheme.colorScheme.onSurfaceVariant,
                )
                Spacer(Modifier.height(10.dp))
                Row(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                    Button(
                        onClick = {
                            createExportFileLauncher.launch("agoria-export-${System.currentTimeMillis()}.json")
                        },
                    ) { Text("Export to Device") }
                    TextButton(onClick = { showReset.value = true }) { Text("Reset app") }
                }
            }

            SectionCard(title = "Backup & Restore") {
                Text(
                    text = "Backup your agents, settings, and data. Restore from local file or Google Drive.",
                    style = MaterialTheme.typography.bodySmall,
                    color = MaterialTheme.colorScheme.onSurfaceVariant,
                )
                Spacer(Modifier.height(10.dp))

                // Local backup
                Row(
                    modifier = Modifier.fillMaxWidth(),
                    horizontalArrangement = Arrangement.spacedBy(8.dp),
                ) {
                    Column(Modifier.weight(1f)) {
                        Row(verticalAlignment = Alignment.CenterVertically) {
                            Icon(
                                imageVector = Icons.Default.FileDownload,
                                contentDescription = null,
                                tint = MaterialTheme.colorScheme.primary,
                                modifier = Modifier.size(24.dp),
                            )
                            Spacer(Modifier.width(8.dp))
                            Column {
                                Text("Export to Device", style = MaterialTheme.typography.titleSmall)
                                Text(
                                    text = "Save encrypted JSON to a location of your choice",
                                    style = MaterialTheme.typography.bodySmall,
                                    color = MaterialTheme.colorScheme.onSurfaceVariant,
                                )
                            }
                        }
                    }
                    Button(
                        onClick = {
                            createExportFileLauncher.launch("agoria-export-${System.currentTimeMillis()}.json")
                        },
                    ) { Text("Export") }
                }

                Spacer(Modifier.height(10.dp))

                // Local restore
                Row(
                    modifier = Modifier.fillMaxWidth(),
                    horizontalArrangement = Arrangement.spacedBy(8.dp),
                ) {
                    Column(Modifier.weight(1f)) {
                        Row(verticalAlignment = Alignment.CenterVertically) {
                            Icon(
                                imageVector = Icons.Default.FileUpload,
                                contentDescription = null,
                                tint = MaterialTheme.colorScheme.secondary,
                                modifier = Modifier.size(24.dp),
                            )
                            Spacer(Modifier.width(8.dp))
                            Column {
                                Text("Import from Device", style = MaterialTheme.typography.titleSmall)
                                Text(
                                    text = "Select a previously exported JSON file",
                                    style = MaterialTheme.typography.bodySmall,
                                    color = MaterialTheme.colorScheme.onSurfaceVariant,
                                )
                            }
                        }
                    }
                    Button(
                        onClick = { pickImportFileLauncher.launch("application/json") },
                    ) { Text("Import") }
                }

                Spacer(Modifier.height(10.dp))

                // Google Drive backup
                Row(
                    modifier = Modifier.fillMaxWidth(),
                    horizontalArrangement = Arrangement.spacedBy(8.dp),
                ) {
                    Column(Modifier.weight(1f)) {
                        Row(verticalAlignment = Alignment.CenterVertically) {
                            Icon(
                                imageVector = Icons.Default.CloudUpload,
                                contentDescription = null,
                                tint = MaterialTheme.colorScheme.tertiary,
                                modifier = Modifier.size(24.dp),
                            )
                            Spacer(Modifier.width(8.dp))
                            Column {
                                Text("Backup to Google Drive", style = MaterialTheme.typography.titleSmall)
                                Text(
                                    text = "Upload encrypted backup to your Drive",
                                    style = MaterialTheme.typography.bodySmall,
                                    color = MaterialTheme.colorScheme.onSurfaceVariant,
                                )
                            }
                        }
                    }
                    Button(onClick = { backupToGoogleDrive() }) { Text("Backup Now") }
                }

                Spacer(Modifier.height(10.dp))

                // Google Drive restore
                Row(
                    modifier = Modifier.fillMaxWidth(),
                    horizontalArrangement = Arrangement.spacedBy(8.dp),
                ) {
                    Column(Modifier.weight(1f)) {
                        Row(verticalAlignment = Alignment.CenterVertically) {
                            Icon(
                                imageVector = Icons.Default.CloudDownload,
                                contentDescription = null,
                                tint = MaterialTheme.colorScheme.tertiary,
                                modifier = Modifier.size(24.dp),
                            )
                            Spacer(Modifier.width(8.dp))
                            Column {
                                Text("Restore from Google Drive", style = MaterialTheme.typography.titleSmall)
                                Text(
                                    text = "Download and restore latest backup from Drive",
                                    style = MaterialTheme.typography.bodySmall,
                                    color = MaterialTheme.colorScheme.onSurfaceVariant,
                                )
                            }
                        }
                    }
                    Button(onClick = { restoreFromGoogleDrive() }) { Text("Restore from Drive") }
                }
            }

            SectionCard(title = "App Updates") {
                val updateState = remember { mutableStateOf(UpdateState.Idle) }
                val downloadProgress = remember { mutableStateOf<Int?>(null) }
                val releaseInfo = remember { mutableStateOf<com.agoria.app.util.UpdateManager.ReleaseInfo?>(null) }

                when (updateState.value) {
                    UpdateState.Idle -> {
                        Row(
                            modifier = Modifier.fillMaxWidth(),
                            horizontalArrangement = Arrangement.spacedBy(8.dp),
                        ) {
                            Column(Modifier.weight(1f)) {
                                Text("Check for Updates", style = MaterialTheme.typography.titleSmall)
                                Text(
                                    text = "Installed: $currentVersionName",
                                    style = MaterialTheme.typography.bodySmall,
                                    color = MaterialTheme.colorScheme.onSurfaceVariant,
                                )
                            }
                            Button(onClick = {
                                updateState.value = UpdateState.Checking
                                viewModel.checkForUpdates { info ->
                                    releaseInfo.value = info
                                    if (info != null && viewModel.isNewerRelease(info)) {
                                        updateState.value = UpdateState.UpdateAvailable
                                    } else {
                                        updateState.value = UpdateState.UpToDate
                                    }
                                }
                            }) { Text("Check Now") }
                        }
                    }
                    UpdateState.Checking -> {
                        Row(
                            modifier = Modifier.fillMaxWidth(),
                            horizontalArrangement = Arrangement.spacedBy(8.dp),
                        ) {
                            CircularProgressIndicator(modifier = Modifier.size(24.dp))
                            Spacer(Modifier.width(16.dp))
                            Text("Checking...", style = MaterialTheme.typography.bodyMedium)
                        }
                    }
                    UpdateState.UpdateAvailable -> {
                        val info = releaseInfo.value!!
                        Column(verticalArrangement = Arrangement.spacedBy(8.dp)) {
                            Row(
                                modifier = Modifier.fillMaxWidth(),
                                horizontalArrangement = Arrangement.spacedBy(8.dp),
                            ) {
                                Column(Modifier.weight(1f)) {
                                    Text("Update Available", style = MaterialTheme.typography.titleSmall)
                                    Text(
                                        text = "Version ${info.versionName} (${info.versionCode})",
                                        style = MaterialTheme.typography.bodySmall,
                                        color = MaterialTheme.colorScheme.primary,
                                    )
                                }
                            }
                            Text(info.releaseNotes, style = MaterialTheme.typography.bodySmall, color = MaterialTheme.colorScheme.onSurfaceVariant)
                            Row(
                                modifier = Modifier.fillMaxWidth(),
                                horizontalArrangement = Arrangement.spacedBy(8.dp),
                            ) {
                                Button(
                                    onClick = {
                                        if (!viewModel.hasInstallPermission()) {
                                            showUnknownAppsDialog.value = true
                                        } else {
                                            downloadProgress.value = 0
                                            viewModel.downloadAndInstallUpdate(info, { p -> downloadProgress.value = p }, { success, error ->
                                                downloadProgress.value = null
                                                if (success) {
                                                    scope.launch { snackbarHostState.showSnackbar("Install started", duration = SnackbarDuration.Long) }
                                                } else {
                                                    val err = error ?: "Install failed"
                                                    // Only show permission dialog if permission check actually fails
                                                    // If hasInstallPermission() is true but install fails with permission error, 
                                                    // it's a transient Android issue - show actual error instead of looping
                                                    if (!viewModel.hasInstallPermission() && (err.contains("unknown apps", ignoreCase = true) || err.contains("Install unknown apps", ignoreCase = true) || err.contains("permission not granted", ignoreCase = true))) {
                                                        showUnknownAppsDialog.value = true
                                                    } else {
                                                        scope.launch { snackbarHostState.showSnackbar(err, duration = SnackbarDuration.Long) }
                                                    }
                                                }
                                            })
                                        }
                                    },
                                    enabled = downloadProgress.value == null,
                                ) {
                                    if (downloadProgress.value != null) {
                                        Row(verticalAlignment = Alignment.CenterVertically) {
                                            CircularProgressIndicator(modifier = Modifier.size(16.dp))
                                            Spacer(Modifier.width(6.dp))
                                            Text("Downloading ${downloadProgress.value}%")
                                        }
                                    } else {
                                        Text("Download & Install")
                                    }
                                }
                                TextButton(onClick = { updateState.value = UpdateState.Idle }) { Text("Later") }
                            }
                        }
                    }
                    UpdateState.UpToDate -> {
                        val remoteVersion = releaseInfo.value?.versionName ?: "unknown"
                        Row(
                            modifier = Modifier.fillMaxWidth(),
                            horizontalArrangement = Arrangement.spacedBy(8.dp),
                        ) {
                            Column(Modifier.weight(1f)) {
                                Row(verticalAlignment = Alignment.CenterVertically) {
                                    Icon(
                                        imageVector = Icons.Default.Settings,
                                        contentDescription = null,
                                        tint = MaterialTheme.colorScheme.secondary,
                                        modifier = Modifier.size(24.dp),
                                    )
                                    Spacer(Modifier.width(8.dp))
                                    Column {
                                        Text("Up to Date", style = MaterialTheme.typography.titleSmall)
                                        Text(
                                            text = "Installed: $currentVersionName | Latest: $remoteVersion",
                                            style = MaterialTheme.typography.bodySmall,
                                            color = MaterialTheme.colorScheme.onSurfaceVariant,
                                        )
                                    }
                                }
                            }
                            TextButton(onClick = { updateState.value = UpdateState.Idle }) { Text("Check Again") }
                        }
                    }
                }
            }

            SectionCard(title = "About") {
                Text("Dunder 1.0.0", style = MaterialTheme.typography.titleMedium)
                Text(
                    text = "A local autonomous office for Android 13+. Agents run on free model APIs and keep their thoughts in a local diary. Builds and outward-facing actions require approval.",
                    style = MaterialTheme.typography.bodySmall,
                    color = MaterialTheme.colorScheme.onSurfaceVariant,
                )
                Spacer(Modifier.height(8.dp))
                Text("Free model list", style = MaterialTheme.typography.labelLarge)
                Text(
                    text = "github.com/mnfst/awesome-free-llm-apis",
                    style = MaterialTheme.typography.bodySmall,
                    color = MaterialTheme.colorScheme.primary,
                )
                Text("Office assets", style = MaterialTheme.typography.labelLarge)
                Text(
                    text = "github.com/Parcha-ai/ai-office",
                    style = MaterialTheme.typography.bodySmall,
                    color = MaterialTheme.colorScheme.primary,
                )
                Spacer(Modifier.height(8.dp))
                Text(
                    text = "Free tiers change. Check each provider's docs for current limits and terms. Some free providers log prompts; avoid putting secrets in prompts.",
                    style = MaterialTheme.typography.bodySmall,
                    color = MaterialTheme.colorScheme.onSurfaceVariant,
                )
            }

            // Dialogs - must be inside the Column (Scaffold content)
            if (showReset.value) {
                AlertDialog(
                    onDismissRequest = { showReset.value = false },
                    title = { Text("Reset Dunder?") },
                    text = { Text("This deletes all agents, diaries, builds, approvals, and connector configuration on this device. It cannot be undone.") },
                    confirmButton = {
                        TextButton(onClick = {
                            viewModel.resetData()
                            showReset.value = false
                        }) { Text("Reset everything") }
                    },
                    dismissButton = {
                        TextButton(onClick = { showReset.value = false }) { Text("Cancel") }
                    },
                )
            }

            if (showUnknownAppsDialog.value) {
                AlertDialog(
                    onDismissRequest = { showUnknownAppsDialog.value = false },
                    title = { Text("Permission Required") },
                    text = { Text("To install updates, Dunder needs permission to install apps from unknown sources.\n\nTap 'Open Settings' to enable this permission. After enabling, you MUST restart the app (swipe away from recent apps and reopen) for the change to take effect.") },
                    confirmButton = {
                        TextButton(onClick = {
                            val intent = Intent(Settings.ACTION_MANAGE_UNKNOWN_APP_SOURCES).apply {
                                data = Uri.parse("package:${context.packageName}")
                            }
                            if (intent.resolveActivity(context.packageManager) != null) {
                                context.startActivity(intent)
                            }
                            showUnknownAppsDialog.value = false
                            showRestartDialog.value = true
                        }) { Text("Open Settings") }
                    },
                    dismissButton = {
                        TextButton(onClick = { showUnknownAppsDialog.value = false }) { Text("Cancel") }
                    },
                )
            }

            if (showRestartDialog.value) {
                AlertDialog(
                    onDismissRequest = { showRestartDialog.value = false },
                    title = { Text("Restart Required") },
                    text = { Text("Permission granted! Please restart the app now:\n1. Swipe up from bottom and hold (or tap recent apps)\n2. Swipe Dunder away to close it\n3. Reopen Dunder\n4. Try the update again") },
                    confirmButton = {
                        TextButton(onClick = {
                            showRestartDialog.value = false
                            Process.killProcess(Process.myPid())
                            System.exit(0)
                        }) { Text("Restart Now") }
                    },
                    dismissButton = {
                        TextButton(onClick = { showRestartDialog.value = false }) { Text("Later") }
                    },
                )
            }

            Spacer(Modifier.height(24.dp))
        }
    }
}

// Helper functions (top-level, not inside SettingsScreen)
@Composable
internal fun SectionCard(
    title: String,
    content: @Composable () -> Unit,
) {
    Card(
        modifier = Modifier.fillMaxWidth(),
        shape = MaterialTheme.shapes.large,
        colors = CardDefaults.cardColors(
            containerColor = MaterialTheme.colorScheme.surfaceVariant,
        ),
    ) {
        Column(Modifier.padding(16.dp)) {
            Text(title, style = MaterialTheme.typography.titleMedium)
            Spacer(Modifier.height(8.dp))
            content()
        }
    }
}

@Composable
internal fun ProviderRow(
    provider: ProviderEntity,
    spec: ProviderSpec,
    test: ProviderTest?,
    onToggle: (Boolean) -> Unit,
    onSaveKey: (String) -> Unit,
    onSaveModel: (String) -> Unit,
    onSaveExtra: (String) -> Unit,
    onTest: () -> Unit,
    compact: Boolean = false,
) {
    val enabled = provider.enabled
    val model = provider.model ?: ""

    Card(
        modifier = Modifier.fillMaxWidth(),
        colors = CardDefaults.cardColors(
            containerColor = MaterialTheme.colorScheme.surfaceVariant,
        ),
    ) {
        Column(Modifier.padding(16.dp), verticalArrangement = Arrangement.spacedBy(8.dp)) {
            Row(
                modifier = Modifier.fillMaxWidth(),
                horizontalArrangement = Arrangement.SpaceBetween,
            ) {
                Text(spec.name, style = MaterialTheme.typography.titleSmall)
                Switch(checked = enabled, onCheckedChange = onToggle)
            }
            if (!enabled && !compact) {
                Text("Disabled", style = MaterialTheme.typography.bodySmall, color = MaterialTheme.colorScheme.onSurfaceVariant)
            }
            if (enabled && !compact) {
                if (spec.keyRequired) {
                    OutlinedTextField(
                        value = "",
                        onValueChange = onSaveKey,
                        label = { Text(spec.keyLabel) },
                        modifier = Modifier.fillMaxWidth(),
                        singleLine = true,
                    )
                }
                if (spec.models.isNotEmpty()) {
                    androidx.compose.material3.Text(
                        text = "Model: $model",
                        style = MaterialTheme.typography.bodySmall,
                    )
                }
                if (spec.needsExtra) {
                    OutlinedTextField(
                        value = "",
                        onValueChange = onSaveExtra,
                        label = { Text(spec.extraLabel) },
                        placeholder = { Text(spec.extraPlaceholder) },
                        modifier = Modifier.fillMaxWidth(),
                        singleLine = true,
                    )
                }
                Row(
                    modifier = Modifier.fillMaxWidth(),
                    horizontalArrangement = Arrangement.spacedBy(8.dp),
                ) {
                    Button(onClick = onTest) { Text("Test") }
                    if (test != null) {
                        val isOk = test.ok == true
                        Text(if (isOk) "✓ OK" else "✗ ${test.message}", style = MaterialTheme.typography.bodySmall, color = if (isOk) MaterialTheme.colorScheme.primary else MaterialTheme.colorScheme.error)
                    }
                }
            }
        }
    }
}

@Composable
internal fun RevenueRow(
    agentName: String,
    amountCents: Long,
) {
    Row(
        modifier = Modifier
            .fillMaxWidth()
            .padding(vertical = 4.dp),
        horizontalArrangement = Arrangement.SpaceBetween,
    ) {
        Text(agentName, style = MaterialTheme.typography.bodyMedium)
        Text(
            formatCents(amountCents),
            style = MaterialTheme.typography.bodyMedium,
            color = MaterialTheme.colorScheme.primary,
        )
    }
}

@Composable
internal fun RevenueEventRow(
    agentName: String,
    source: String,
    amountCents: Long,
    timestamp: Long,
) {
    val time = java.text.SimpleDateFormat("MM/dd HH:mm", java.util.Locale.getDefault())
        .format(java.util.Date(timestamp))
    Row(
        modifier = Modifier
            .fillMaxWidth()
            .padding(vertical = 4.dp),
        horizontalArrangement = Arrangement.SpaceBetween,
    ) {
        Column {
            Text("$agentName — $source", style = MaterialTheme.typography.labelMedium)
            Text(time, style = MaterialTheme.typography.bodySmall, color = MaterialTheme.colorScheme.onSurfaceVariant)
        }
        Text(
            formatCents(amountCents),
            style = MaterialTheme.typography.bodyMedium,
            color = MaterialTheme.colorScheme.primary,
        )
    }
}

internal fun formatCents(cents: Long): String {
    val dollars = cents / 100
    val rem = cents % 100
    return "\$$dollars.${rem.toString().padStart(2, '0')}"
}

@Composable
internal fun rememberCurrentVersion(context: Context): Pair<String, Int> {
    val pm = context.packageManager
    val info = try {
        pm.getPackageInfo(context.packageName, 0)
    } catch (e: android.content.pm.PackageManager.NameNotFoundException) {
        return Pair("1.0.0", 1)
    }
    return Pair(info.versionName ?: "1.0.0", info.versionCode)
}

enum class UpdateState {
    Idle, Checking, UpdateAvailable, UpToDate
}