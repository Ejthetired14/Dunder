package com.agoria.app.ui.settings

import android.content.Intent
import android.net.Uri
import android.provider.Settings
import java.io.File
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
import androidx.compose.material3.Surface
import androidx.compose.material3.Switch
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.material3.TopAppBar
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
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.text.input.KeyboardType
import androidx.compose.ui.text.input.PasswordVisualTransformation
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import com.agoria.app.core.ConnectorResult
import com.agoria.app.core.ConnectorCatalog
import com.agoria.app.core.ConnectorDef
import com.agoria.app.core.ProviderCatalog
import com.agoria.app.core.ProviderSpec
import com.agoria.app.data.ConnectorEntity
import com.agoria.app.data.ProviderEntity
import com.agoria.app.data.RevenueEntity
import com.agoria.app.ui.AgoriaViewModel
import com.agoria.app.ui.ProviderTest
import com.agoria.app.ui.SettingsScreenState
import com.agoria.app.util.Share
import com.agoria.app.util.UpdateManager
import kotlinx.coroutines.launch
import kotlin.math.roundToInt

// Update state enum
enum class UpdateState {
    Idle,
    Checking,
    UpToDate,
    UpdateAvailable,
}

// Get current version info
@Composable
fun rememberCurrentVersion(context: android.content.Context): Pair<String, Int> {
    return remember(context) {
        try {
            val pm = context.packageManager
            val info = pm.getPackageInfo(context.packageName, 0)
            (info.versionName ?: "1.0.0") to info.versionCode
        } catch (e: android.content.pm.PackageManager.NameNotFoundException) {
            "1.0.0" to 1
        }
    }
}

@OptIn(ExperimentalMaterial3Api::class)
@Composable
fun SettingsScreen(
    viewModel: AgoriaViewModel,
    onBack: () -> Unit,
) {
    val context = LocalContext.current
    val state by viewModel.settingsScreenState.collectAsStateWithLifecycle()
    var showReset by remember { mutableStateOf(false) }
    var tick by remember { mutableStateOf(120) }
    var cooldown by remember { mutableStateOf(90) }
    var agentSplit by remember { mutableStateOf(50) }
    var userSplit by remember { mutableStateOf(50) }

    val (currentVersionName, currentVersionCode) = rememberCurrentVersion(context)
    val uiScope = rememberCoroutineScope()

    // File picker for import
    val pickImportFileLauncher = rememberLauncherForActivityResult(
        contract = ActivityResultContracts.GetContent(),
    ) { uri: Uri? ->
        uri?.let { fileUri ->
            viewModel.importData(File(fileUri.path)) { success ->
                // Could show success/error snackbar
            }
        }
    }

    val settings = state.settings
    val providers = state.providers
    val tests = state.providerTests
    val revenue = state.revenue

    // Backup to Google Drive
    fun backupToGoogleDrive() {
        uiScope.launch {
            val result = viewModel.backupToGoogleDrive()
            // Handle result
        }
    }

    // Restore from Google Drive
    fun restoreFromGoogleDrive() {
        uiScope.launch {
            val result = viewModel.restoreFromGoogleDrive()
            // Handle result
        }
    }

    LaunchedEffect(settings) {
        settings?.let {
            tick = it.tickIntervalSeconds
            cooldown = it.agentCooldownSeconds
            agentSplit = it.agentProfitSharePercent
            userSplit = it.userProfitSharePercent
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
                Row(verticalAlignment = Alignment.CenterVertically) {
                    Column(Modifier.weight(1f)) {
                        Text("Run the office", style = MaterialTheme.typography.titleMedium)
                        Text(
                            text = "Agents think, write diary entries, and propose builds while this is on.",
                            style = MaterialTheme.typography.bodySmall,
                            color = MaterialTheme.colorScheme.onSurfaceVariant,
                        )
                    }
                    Switch(
                        checked = settings?.autonomyEnabled ?: false,
                        onCheckedChange = { viewModel.setAutonomy(it) },
                    )
                }
                Spacer(Modifier.height(12.dp))
                Text(
                    text = "Cycle interval: ${formatSeconds(tick)}",
                    style = MaterialTheme.typography.labelLarge,
                )
                Slider(
                    value = tick.toFloat(),
                    onValueChange = { tick = it.roundToInt() },
                    valueRange = 30f..600f,
                    steps = 18,
                )
                Text(
                    text = "Per-agent cooldown: ${formatSeconds(cooldown)}",
                    style = MaterialTheme.typography.labelLarge,
                )
                Slider(
                    value = cooldown.toFloat(),
                    onValueChange = { cooldown = it.roundToInt() },
                    valueRange = 30f..600f,
                    steps = 18,
                )
                Row(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                    Button(
                        onClick = {
                            viewModel.updateSettings {
                                it.copy(
                                    tickIntervalSeconds = tick,
                                    agentCooldownSeconds = cooldown,
                                )
                            }
                        },
                    ) { Text("Save timing") }
                    TextButton(onClick = { viewModel.runNow() }) {
                        Icon(Icons.Default.PlayArrow, contentDescription = null)
                        Spacer(Modifier.width(6.dp))
                        Text("Run one cycle")
                    }
                    TextButton(onClick = { viewModel.nudgeAll() }) {
                        Icon(Icons.Default.Bolt, contentDescription = null)
                        Spacer(Modifier.width(6.dp))
                        Text("Nudge all")
                    }
                }
                Spacer(Modifier.height(8.dp))
                Text(
                    text = "For reliable background execution, allow notifications and disable battery optimization for Dunder.",
                    style = MaterialTheme.typography.bodySmall,
                    color = MaterialTheme.colorScheme.onSurfaceVariant,
                )
                Row(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                    TextButton(
                        onClick = {
                            val intent = Intent(Settings.ACTION_IGNORE_BATTERY_OPTIMIZATION_SETTINGS)
                            context.startActivity(intent)
                        },
                    ) { Text("Battery settings") }
                    TextButton(
                        onClick = {
                            val intent = Intent(Settings.ACTION_APP_NOTIFICATION_SETTINGS).apply {
                                putExtra(Settings.EXTRA_APP_PACKAGE, context.packageName)
                            }
                            context.startActivity(intent)
                        },
                    ) { Text("Notification settings") }
                }
            }

            SectionCard(title = "Revenue & Monetization") {
                val totalRevenue = revenue.sumOf { it.amountCents }
                val totalByAgent = revenue.groupBy { it.agentId }.mapValues { (_, v) -> v.sumOf { it.amountCents } }
                val infraFund = settings?.infrastructureFundCents ?: 0L

                Text(
                    text = "Agents build and deploy micro-APIs. Revenue is split between you and the infrastructure fund (used to upgrade models).",
                    style = MaterialTheme.typography.bodySmall,
                    color = MaterialTheme.colorScheme.onSurfaceVariant,
                )
                Spacer(Modifier.height(8.dp))

                // Summary cards
                Row(
                    modifier = Modifier.fillMaxWidth(),
                    horizontalArrangement = Arrangement.spacedBy(8.dp),
                ) {
                    SummaryCard(
                        title = "Total Revenue",
                        value = formatCents(totalRevenue),
                        color = MaterialTheme.colorScheme.primary,
                        modifier = Modifier.weight(1f, fill = true),
                    )
                    SummaryCard(
                        title = "Infrastructure Fund",
                        value = formatCents(infraFund),
                        color = MaterialTheme.colorScheme.secondary,
                        modifier = Modifier.weight(1f, fill = true),
                    )
                    SummaryCard(
                        title = "Your Share",
                        value = formatCents((totalRevenue * (userSplit.toLong()) / 100).coerceAtLeast(0L)),
                        color = MaterialTheme.colorScheme.tertiary,
                        modifier = Modifier.weight(1f, fill = true),
                    )
                }

                Spacer(Modifier.height(12.dp))

                // Profit split sliders
                Text("Profit Split", style = MaterialTheme.typography.titleSmall)
                Spacer(Modifier.height(4.dp))
                Row(
                    modifier = Modifier.fillMaxWidth(),
                    horizontalArrangement = Arrangement.spacedBy(8.dp),
                ) {
                    Column(Modifier.weight(1f)) {
                        Text("Agent Fund: ${agentSplit}%", style = MaterialTheme.typography.labelLarge)
                        Slider(
                            value = agentSplit.toFloat(),
                            onValueChange = { agentSplit = it.roundToInt().coerceIn(0, 100); userSplit = 100 - agentSplit },
                            valueRange = 0f..100f,
                            steps = 20,
                        )
                    }
                    Column(Modifier.weight(1f)) {
                        Text("Your Share: ${userSplit}%", style = MaterialTheme.typography.labelLarge)
                        Slider(
                            value = userSplit.toFloat(),
                            onValueChange = { userSplit = it.roundToInt().coerceIn(0, 100); agentSplit = 100 - userSplit },
                            valueRange = 0f..100f,
                            steps = 20,
                        )
                    }
                }
                Row(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                    Button(
                        onClick = {
                            viewModel.setProfitSplit(agentSplit, userSplit)
                        },
                    ) { Text("Save Split") }
                }

                Spacer(Modifier.height(12.dp))

                // Payment connectors
                Text("Payment Connectors", style = MaterialTheme.typography.titleSmall)
                Spacer(Modifier.height(4.dp))
                Text(
                    text = "Configure payment providers for monetizing agent APIs. Set up connectors in the Connectors screen.",
                    style = MaterialTheme.typography.bodySmall,
                    color = MaterialTheme.colorScheme.onSurfaceVariant,
                )
                Spacer(Modifier.height(8.dp))
                
                val paymentConnectors = state.connectors?.filter { it.id in setOf("stripe", "paypal", "polar") } ?: emptyList()
                if (paymentConnectors.isEmpty()) {
                    Text(
                        text = "No payment connectors configured. Go to Connectors tab to add Stripe, PayPal, or Polar.",
                        style = MaterialTheme.typography.bodySmall,
                        color = MaterialTheme.colorScheme.onSurfaceVariant,
                    )
                } else {
                    paymentConnectors.forEach { connector ->
                        val def = ConnectorCatalog.get(connector.id)
                        Row(
                            modifier = Modifier.fillMaxWidth(),
                            verticalAlignment = Alignment.CenterVertically,
                        ) {
                            Column(Modifier.weight(1f)) {
                                Row(verticalAlignment = Alignment.CenterVertically) {
                                    Text(def?.name ?: connector.name, style = MaterialTheme.typography.titleSmall)
                                    Spacer(Modifier.width(8.dp))
                                    val color = when (connector.status) {
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
                                            text = connector.status.replaceFirstChar { it.uppercase() },
                                            style = MaterialTheme.typography.labelSmall,
                                            color = color,
                                            modifier = Modifier.padding(horizontal = 8.dp, vertical = 3.dp),
                                        )
                                    }
                                }
                                Text(
                                    text = def?.description ?: "Payment connector",
                                    style = MaterialTheme.typography.bodySmall,
                                    color = MaterialTheme.colorScheme.onSurfaceVariant,
                                )
                            }
                            TextButton(onClick = { 
                                // Navigate to connectors screen would need navigation
                            }) { Text("Configure") }
                        }
                        Spacer(Modifier.height(8.dp))
                    }
                }

                Spacer(Modifier.height(12.dp))

                // Revenue by agent
                if (totalByAgent.isNotEmpty()) {
                    Text("Revenue by Agent", style = MaterialTheme.typography.titleSmall)
                    Spacer(Modifier.height(4.dp))
                    totalByAgent.toList().sortedByDescending { it.second }.forEach { (agentId, amount) ->
                        val agentName = viewModel.agents.value.firstOrNull { it.id == agentId }?.name ?: agentId
                        RevenueRow(agentName = agentName, amountCents = amount)
                    }
                } else {
                    Text(
                        text = "No revenue yet. Enable autonomy and let the API Builder agent start building.",
                        style = MaterialTheme.typography.bodySmall,
                        color = MaterialTheme.colorScheme.onSurfaceVariant,
                    )
                }

                Spacer(Modifier.height(8.dp))

                // Recent revenue events
                if (revenue.isNotEmpty()) {
                    Text("Recent Revenue Events", style = MaterialTheme.typography.titleSmall)
                    Spacer(Modifier.height(4.dp))
                    revenue.take(10).forEach { rev ->
                        val agentName = viewModel.agents.value.firstOrNull { it.id == rev.agentId }?.name ?: rev.agentId
                        RevenueEventRow(
                            agentName = agentName,
                            source = rev.source,
                            amountCents = rev.amountCents,
                            timestamp = rev.createdAt,
                        )
                    }
                }
            }

            SectionCard(title = "Model providers") {
                Text(
                    text = "The app tries providers in priority order and falls back when one fails. Keyless providers are enabled by default.",
                    style = MaterialTheme.typography.bodySmall,
                    color = MaterialTheme.colorScheme.onSurfaceVariant,
                )
                Spacer(Modifier.height(8.dp))
                var showAllProviders by remember { mutableStateOf(false) }
                val keylessProviders = providers.filter { ProviderCatalog.byId[it.id]?.keyRequired != true }
                val keyedProviders = providers.filter { ProviderCatalog.byId[it.id]?.keyRequired == true }
                
                // Show keyless providers first (always visible, compact)
                keylessProviders.forEach { provider ->
                    ProviderRow(
                        provider = provider,
                        spec = ProviderCatalog.byId[provider.id],
                        test = tests[provider.id],
                        onToggle = { viewModel.setProviderEnabled(provider.id, it) },
                        onSaveKey = { key -> viewModel.saveProviderKey(provider.id, key) },
                        onSaveModel = { model -> viewModel.setProviderModel(provider.id, model) },
                        onSaveExtra = { extra -> viewModel.saveProviderExtra(provider.id, extra) },
                        onTest = { viewModel.testProvider(provider.id) },
                        compact = true,
                    )
                }
                
                // Keyed providers - show "Show more" if there are any
                if (keyedProviders.isNotEmpty()) {
                    TextButton(onClick = { showAllProviders = !showAllProviders }) {
                        Text(if (showAllProviders) "Show less" else "Show ${keyedProviders.size} providers needing API key")
                    }
                    if (showAllProviders) {
                        Spacer(Modifier.height(4.dp))
                        keyedProviders.forEach { provider ->
                            ProviderRow(
                                provider = provider,
                                spec = ProviderCatalog.byId[provider.id],
                                test = tests[provider.id],
                                onToggle = { viewModel.setProviderEnabled(provider.id, it) },
                                onSaveKey = { key -> viewModel.saveProviderKey(provider.id, key) },
                                onSaveModel = { model -> viewModel.setProviderModel(provider.id, model) },
                                onSaveExtra = { extra -> viewModel.saveProviderExtra(provider.id, extra) },
                                onTest = { viewModel.testProvider(provider.id) },
                                compact = true,
                            )
                        }
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
                            viewModel.exportData { file ->
                                Share.shareJson(context, file)
                            }
                        },
                    ) { Text("Export JSON") }
                    TextButton(onClick = { showReset = true }) { Text("Reset app") }
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
                                    text = "Save encrypted JSON to Downloads folder",
                                    style = MaterialTheme.typography.bodySmall,
                                    color = MaterialTheme.colorScheme.onSurfaceVariant,
                                )
                            }
                        }
                    }
                    Button(
                        onClick = {
                            viewModel.exportData { file ->
                                Share.saveToDownloads(context, file, "agoria-backup-${java.text.SimpleDateFormat("yyyy-MM-dd", java.util.Locale.getDefault()).format(java.util.Date())}.json")
                            }
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
                                    text = "Pick a backup JSON file to restore",
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

                // Google Drive backup (if connector configured)
                val gdriveConnector = state.connectors?.firstOrNull { it.id == "gdrive" }
                if (gdriveConnector != null && gdriveConnector.status == "ready") {
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
                                        text = "Upload backup to Drive via gdrive connector",
                                        style = MaterialTheme.typography.bodySmall,
                                        color = MaterialTheme.colorScheme.onSurfaceVariant,
                                    )
                                }
                            }
                        }
                        Button(
                            onClick = { backupToGoogleDrive() },
                        ) { Text("Backup to Drive") }
                    }

                    Spacer(Modifier.height(8.dp))

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
                        Button(
                            onClick = { restoreFromGoogleDrive() },
                        ) { Text("Restore from Drive") }
                    }
                } else {
                    Text(
                        text = "Set up the Google Drive connector to enable cloud backup/restore.",
                        style = MaterialTheme.typography.bodySmall,
                        color = MaterialTheme.colorScheme.outline,
                    )
                }
            }

            SectionCard(title = "App Updates") {
                val updateState = remember { mutableStateOf<UpdateState>(UpdateState.Idle) }
                val releaseInfo = remember { mutableStateOf<UpdateManager.ReleaseInfo?>(null) }

                when (updateState.value) {
                    UpdateState.Idle -> {
                        Row(
                            modifier = Modifier.fillMaxWidth(),
                            horizontalArrangement = Arrangement.spacedBy(8.dp),
                        ) {
                            Column(Modifier.weight(1f)) {
                                Row(verticalAlignment = Alignment.CenterVertically) {
                                    Icon(
                                        imageVector = Icons.Default.SystemUpdate,
                                        contentDescription = null,
                                        tint = MaterialTheme.colorScheme.primary,
                                        modifier = Modifier.size(24.dp),
                                    )
                                    Spacer(Modifier.width(8.dp))
                                    Column {
                                        Text("Check for Updates", style = MaterialTheme.typography.titleSmall)
                                        Text(
                                            text = "Current version: $currentVersionName ($currentVersionCode)",
                                            style = MaterialTheme.typography.bodySmall,
                                            color = MaterialTheme.colorScheme.onSurfaceVariant,
                                        )
                                    }
                                }
                            }
                            Button(
                                onClick = {
                                    updateState.value = UpdateState.Checking
                                    viewModel.checkForUpdates { info ->
                                        releaseInfo.value = info
                                        updateState.value = if (info != null && viewModel.isNewerRelease(info)) UpdateState.UpdateAvailable else UpdateState.UpToDate
                                    }
                                },
                            ) { Text("Check") }
                        }
                    }
                    UpdateState.Checking -> {
                        Row(
                            modifier = Modifier.fillMaxWidth(),
                            horizontalArrangement = Arrangement.Center,
                        ) {
                            CircularProgressIndicator(modifier = Modifier.size(24.dp))
                            Spacer(Modifier.width(12.dp))
                            Text("Checking for updates...", style = MaterialTheme.typography.bodyMedium)
                        }
                    }
                    UpdateState.UpToDate -> {
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
                                            text = "You're running the latest version ($currentVersionName)",
                                            style = MaterialTheme.typography.bodySmall,
                                            color = MaterialTheme.colorScheme.onSurfaceVariant,
                                        )
                                    }
                                }
                            }
                            TextButton(onClick = { updateState.value = UpdateState.Idle }) { Text("Check Again") }
                        }
                    }
                    UpdateState.UpdateAvailable -> {
                        val info = releaseInfo.value!!
                        Column(Modifier.fillMaxWidth()) {
                            Row(
                                modifier = Modifier.fillMaxWidth(),
                                horizontalArrangement = Arrangement.spacedBy(8.dp),
                            ) {
                                Column(Modifier.weight(1f)) {
                                    Row(verticalAlignment = Alignment.CenterVertically) {
                                        Icon(
                                            imageVector = Icons.Default.SystemUpdate,
                                            contentDescription = null,
                                            tint = MaterialTheme.colorScheme.tertiary,
                                            modifier = Modifier.size(24.dp),
                                        )
                                        Spacer(Modifier.width(8.dp))
                                        Column {
                                            Text("Update Available: ${info.versionName}", style = MaterialTheme.typography.titleSmall)
                                            Text(
                                                text = "Released: ${info.publishedAt.take(10)}",
                                                style = MaterialTheme.typography.bodySmall,
                                                color = MaterialTheme.colorScheme.onSurfaceVariant,
                                            )
                                        }
                                    }
                                }
                            }
                            if (info.releaseNotes.isNotBlank()) {
                                Text(
                                    text = "What's new:\n${info.releaseNotes}",
                                    style = MaterialTheme.typography.bodySmall,
                                    color = MaterialTheme.colorScheme.onSurfaceVariant,
                                    modifier = Modifier.padding(top = 8.dp, bottom = 8.dp).fillMaxWidth(),
                                )
                            }
                            val downloadProgress = remember { mutableStateOf<Int?>(null) }
                            if (downloadProgress.value != null) {
                                LinearProgressIndicator(
                                    progress = downloadProgress.value!! / 100f,
                                    modifier = Modifier.fillMaxWidth(),
                                    color = MaterialTheme.colorScheme.tertiary,
                                )
                            }
                            Row(
                                modifier = Modifier.fillMaxWidth(),
                                horizontalArrangement = Arrangement.spacedBy(8.dp),
                            ) {
                                Button(
                                    onClick = {
                                        downloadProgress.value = 0
                                        viewModel.downloadAndInstallUpdate(info, { p -> downloadProgress.value = p }, { success ->
                                            downloadProgress.value = null
                                            if (!success) {
                                                // Could show error snackbar
                                            }
                                        })
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

            Spacer(Modifier.height(24.dp))
        }
    }

    if (showReset) {
        AlertDialog(
            onDismissRequest = { showReset = false },
            title = { Text("Reset Dunder?") },
            text = { Text("This deletes all agents, diaries, builds, approvals, and connector configuration on this device. It cannot be undone.") },
            confirmButton = {
                TextButton(onClick = {
                    viewModel.resetData()
                    showReset = false
                }) { Text("Reset everything") }
            },
            dismissButton = {
                TextButton(onClick = { showReset = false }) { Text("Cancel") }
            },
        )
    }
}

@Composable
private fun SectionCard(
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
private fun ProviderRow(
    provider: ProviderEntity,
    spec: ProviderSpec?,
    test: ProviderTest?,
    onToggle: (Boolean) -> Unit,
    onSaveKey: (String) -> Unit,
    onSaveModel: (String) -> Unit,
    onSaveExtra: (String) -> Unit,
    onTest: () -> Unit,
    compact: Boolean = false,
) {
    var expanded by remember { mutableStateOf(false) }
    var key by remember { mutableStateOf("") }
    var model by remember { mutableStateOf(provider.model) }
    var extra by remember { mutableStateOf("") }

    val padding = if (compact) 4.dp else 12.dp
    val verticalPadding = if (compact) 2.dp else 6.dp

    Surface(
        modifier = Modifier
            .fillMaxWidth()
            .padding(vertical = verticalPadding),
        shape = MaterialTheme.shapes.medium,
        color = MaterialTheme.colorScheme.surface,
    ) {
        Column(Modifier.padding(padding)) {
            Row(verticalAlignment = Alignment.CenterVertically) {
                Column(Modifier.weight(1f)) {
                    Text(spec?.name ?: provider.id, style = if (compact) MaterialTheme.typography.titleSmall else MaterialTheme.typography.titleMedium)
                    Text(
                        text = buildString {
                            append(provider.model.ifBlank { spec?.defaultModel ?: "default" })
                            append(" · ")
                            append(ProviderCatalog.displayStatus(provider.lastStatus))
                            if (provider.apiKeyEnc != null) append(" · key saved")
                            if (spec?.keyRequired == false) append(" · keyless")
                        },
                        style = MaterialTheme.typography.labelSmall,
                        color = MaterialTheme.colorScheme.onSurfaceVariant,
                        maxLines = 1,
                        overflow = TextOverflow.Ellipsis,
                    )
                }
                Switch(checked = provider.enabled, onCheckedChange = onToggle)
            }

            if (spec != null && !compact) {
                Text(
                    text = spec.freeNote,
                    style = MaterialTheme.typography.bodySmall,
                    color = MaterialTheme.colorScheme.onSurfaceVariant,
                    modifier = Modifier.padding(top = 4.dp),
                )
            }

            if (test != null) {
                Spacer(Modifier.height(4.dp))
                if (test.testing) {
                    Row(verticalAlignment = Alignment.CenterVertically) {
                        CircularProgressIndicator(modifier = Modifier.width(14.dp).height(14.dp))
                        Spacer(Modifier.width(8.dp))
                        Text("Testing...", style = MaterialTheme.typography.labelSmall)
                    }
                } else if (test.ok == true) {
                    Text(
                        text = test.message,
                        style = MaterialTheme.typography.labelSmall,
                        color = MaterialTheme.colorScheme.secondary,
                    )
                } else if (test.ok == false) {
                    Text(
                        text = test.message,
                        style = MaterialTheme.typography.labelSmall,
                        color = MaterialTheme.colorScheme.error,
                        maxLines = 2,
                        overflow = TextOverflow.Ellipsis,
                    )
                }
            }

            Row(horizontalArrangement = Arrangement.spacedBy(6.dp)) {
                TextButton(onClick = { expanded = !expanded }) {
                    Text(if (expanded) "Hide setup" else "Configure")
                }
                TextButton(onClick = onTest) { Text("Test") }
            }

            if (expanded) {
                Spacer(Modifier.height(6.dp))
                OutlinedTextField(
                    value = key,
                    onValueChange = { key = it },
                    label = {
                        Text(
                            if (provider.apiKeyEnc != null) {
                                "${spec?.keyLabel ?: "API key"} (saved)"
                            } else {
                                spec?.keyLabel ?: "API key"
                            },
                        )
                    },
                    supportingText = {
                        Text(spec?.keyHint ?: "Optional. Some providers work without a key.")
                    },
                    singleLine = true,
                    visualTransformation = PasswordVisualTransformation(),
                    keyboardOptions = androidx.compose.foundation.text.KeyboardOptions(
                        keyboardType = KeyboardType.Password,
                    ),
                    modifier = Modifier.fillMaxWidth(),
                )
                Row(horizontalArrangement = Arrangement.spacedBy(6.dp)) {
                    TextButton(onClick = {
                        onSaveKey(key)
                        key = ""
                    }) { Text("Save key") }
                    if (provider.apiKeyEnc != null) {
                        TextButton(onClick = {
                            onSaveKey("")
                            key = ""
                        }) { Text("Clear key") }
                    }
                }

                Spacer(Modifier.height(6.dp))
                OutlinedTextField(
                    value = model,
                    onValueChange = { model = it },
                    label = { Text("Model") },
                    supportingText = { Text(spec?.models?.joinToString(", ") { it.id }?.take(400) ?: "") },
                    singleLine = true,
                    modifier = Modifier.fillMaxWidth(),
                )
                TextButton(onClick = { onSaveModel(model) }) { Text("Save model") }

                if (spec?.needsExtra == true) {
                    Spacer(Modifier.height(6.dp))
                    OutlinedTextField(
                        value = extra,
                        onValueChange = { extra = it },
                        label = { Text(spec.extraLabel) },
                        supportingText = { Text(spec.extraHint) },
                        singleLine = true,
                        modifier = Modifier.fillMaxWidth(),
                    )
                    TextButton(onClick = { onSaveExtra(extra) }) { Text("Save ${spec.extraLabel}") }
                }

                if (!provider.lastError.isNullOrBlank()) {
                    Spacer(Modifier.height(4.dp))
                    Text(
                        text = provider.lastError,
                        style = MaterialTheme.typography.labelSmall,
                        color = MaterialTheme.colorScheme.error,
                        maxLines = 3,
                        overflow = TextOverflow.Ellipsis,
                    )
                }
            }
        }
    }
}

private fun formatSeconds(seconds: Int): String {
    return if (seconds < 60) "${seconds}s" else "${seconds / 60}m ${seconds % 60}s"
}

private fun formatCents(cents: Long): String {
    val dollars = cents / 100
    val remainingCents = cents % 100
    return "\$$dollars.${"%02d".format(remainingCents)}"
}

@Composable
private fun SummaryCard(
    title: String,
    value: String,
    color: androidx.compose.ui.graphics.Color,
    modifier: Modifier = Modifier,
) {
    Card(
        modifier = modifier
            .fillMaxSize(),
        shape = MaterialTheme.shapes.medium,
        colors = CardDefaults.cardColors(
            containerColor = color.copy(alpha = 0.15f),
        ),
    ) {
        Column(
            modifier = Modifier
                .fillMaxSize()
                .padding(16.dp),
            horizontalAlignment = Alignment.CenterHorizontally,
            verticalArrangement = Arrangement.Center,
        ) {
            Text(title, style = MaterialTheme.typography.labelMedium, color = color)
            Spacer(Modifier.height(4.dp))
            Text(value, style = MaterialTheme.typography.titleLarge, color = color)
        }
    }
}

@Composable
private fun RevenueRow(
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
private fun RevenueEventRow(
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