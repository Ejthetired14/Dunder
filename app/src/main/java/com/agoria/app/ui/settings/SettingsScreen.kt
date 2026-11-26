package com.agoria.app.ui.settings

import android.content.Intent
import android.net.Uri
import android.provider.Settings
import java.io.File
import androidx.activity.compose.rememberLauncherForActivityResult
import androidx.activity.result.contract.ActivityResultContracts
import androidx.compose.foundation.background
import androidx.compose.foundation.border
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.ColumnScope
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxHeight
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.verticalScroll
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.FileDownload
import androidx.compose.material.icons.filled.FileUpload
import androidx.compose.material.icons.filled.Settings
import androidx.compose.material.icons.filled.SystemUpdate
import androidx.compose.material3.ExperimentalMaterial3Api
import androidx.compose.material3.Icon
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.SnackbarHost
import androidx.compose.material3.SnackbarHostState
import androidx.compose.material3.Text
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
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import com.agoria.app.core.ConnectorCatalog
import com.agoria.app.core.ProviderCatalog
import com.agoria.app.core.ProviderSpec
import com.agoria.app.data.ProviderEntity
import com.agoria.app.ui.AgoriaViewModel
import com.agoria.app.ui.ProviderTest
import com.agoria.app.ui.components.PixelCard
import com.agoria.app.ui.components.PixelPill
import com.agoria.app.ui.components.PixelScaffold
import com.agoria.app.ui.office.world.Pal
import com.agoria.app.ui.office.world.PixelButton
import com.agoria.app.ui.office.world.PixelDialog
import com.agoria.app.ui.office.world.PixelLabel
import com.agoria.app.ui.office.world.PixelProgressBar
import com.agoria.app.ui.office.world.PixelSlider
import com.agoria.app.ui.office.world.PixelSwitch
import com.agoria.app.ui.office.world.PixelTextField
import com.agoria.app.util.UpdateManager
import kotlinx.coroutines.launch
import kotlin.math.roundToInt

// Secondary text on cream cards. The theme's onSurfaceVariant is tan, which is unreadable on cream.
private val Dim = Pal.navy.copy(alpha = 0.75f)

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
    onOpenConnectors: (String?) -> Unit,
    onOpenSkills: () -> Unit,
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
    val snackbarHostState = remember { SnackbarHostState() }
    val scope = rememberCoroutineScope()

    // File picker for import
    val pickImportFileLauncher = rememberLauncherForActivityResult(
        contract = ActivityResultContracts.GetContent(),
    ) { uri: Uri? ->
        uri?.let { fileUri ->
            // A document picked through the system picker is not a file path, so it has to be read
            // through the content resolver.
            viewModel.importDataFromUri(context, fileUri) { success, message ->
                scope.launch { snackbarHostState.showSnackbar(message) }
            }
        }
    }

    // File picker for export (user chooses where to save)
    val createExportFileLauncher = rememberLauncherForActivityResult(
        contract = ActivityResultContracts.CreateDocument("application/json"),
    ) { uri: Uri? ->
        uri?.let { exportUri ->
            viewModel.exportDataToUri(context, exportUri) { success, message ->
                scope.launch { snackbarHostState.showSnackbar(message ?: (if (success) "Export successful" else "Export failed")) }
            }
        }
    }

    val settings = state.settings
    val providers = state.providers
    val tests = state.providerTests
    val revenue = state.revenue

    LaunchedEffect(settings) {
        settings?.let {
            tick = it.tickIntervalSeconds
            cooldown = it.agentCooldownSeconds
            agentSplit = it.agentProfitSharePercent
            userSplit = it.userProfitSharePercent
        }
    }

    PixelScaffold(
        title = "Settings",
        onBack = onBack,
        snackbarHost = { SnackbarHost(snackbarHostState) },
    ) { padding ->
        Column(
            modifier = Modifier
                .fillMaxSize()
                .padding(padding)
                .verticalScroll(rememberScrollState())
                .padding(16.dp),
            verticalArrangement = Arrangement.spacedBy(14.dp),
        ) {
            SettingsCard(title = "Autonomy") {
                Row(verticalAlignment = Alignment.CenterVertically) {
                    Column(Modifier.weight(1f)) {
                        Text("Run the office", style = MaterialTheme.typography.titleMedium)
                        Text(
                            text = "Agents think, write diary entries, and propose builds while this is on.",
                            style = MaterialTheme.typography.bodySmall,
                            color = Dim,
                        )
                    }
                    PixelSwitch(
                        checked = settings?.autonomyEnabled ?: false,
                        onChange = { viewModel.setAutonomy(it) },
                    )
                }
                Spacer(Modifier.height(12.dp))
                Text(
                    text = "Cycle interval: ${formatSeconds(tick)}",
                    style = MaterialTheme.typography.labelLarge,
                )
                PixelSlider(
                    value = tick.toFloat(),
                    onValueChange = { tick = it.roundToInt() },
                    valueRange = 30f..600f,
                    steps = 18,
                )
                Text(
                    text = "Per-agent cooldown: ${formatSeconds(cooldown)}",
                    style = MaterialTheme.typography.labelLarge,
                )
                PixelSlider(
                    value = cooldown.toFloat(),
                    onValueChange = { cooldown = it.roundToInt() },
                    valueRange = 30f..600f,
                    steps = 18,
                )
                Row(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                    PixelButton("Save timing", {
                        viewModel.updateSettings {
                            it.copy(
                                tickIntervalSeconds = tick,
                                agentCooldownSeconds = cooldown,
                            )
                        }
                    }, small = true)
                    PixelButton("Run one cycle", { viewModel.runNow() }, small = true)
                    PixelButton("Nudge all", { viewModel.nudgeAll() }, small = true, fill = Pal.tan)
                }
                Spacer(Modifier.height(8.dp))
                Text(
                    text = "For reliable background execution, allow notifications and disable battery optimization for Dunder.",
                    style = MaterialTheme.typography.bodySmall,
                    color = Dim,
                )
                Spacer(Modifier.height(6.dp))
                Row(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                    PixelButton("Battery settings", {
                        val intent = Intent(Settings.ACTION_IGNORE_BATTERY_OPTIMIZATION_SETTINGS)
                        context.startActivity(intent)
                    }, small = true, fill = Pal.tan)
                    PixelButton("Notification settings", {
                        val intent = Intent(Settings.ACTION_APP_NOTIFICATION_SETTINGS).apply {
                            putExtra(Settings.EXTRA_APP_PACKAGE, context.packageName)
                        }
                        context.startActivity(intent)
                    }, small = true, fill = Pal.tan)
                }
            }

            SettingsCard(title = "Revenue & Monetization") {
                val totalRevenue = revenue.sumOf { it.amountCents }
                val totalByAgent = revenue.groupBy { it.agentId }.mapValues { (_, v) -> v.sumOf { it.amountCents } }
                val infraFund = settings?.infrastructureFundCents ?: 0L

                Text(
                    text = "Agents build and deploy micro-APIs. Revenue is split between you and the infrastructure fund (used to upgrade models).",
                    style = MaterialTheme.typography.bodySmall,
                    color = Dim,
                )
                Spacer(Modifier.height(8.dp))

                // Summary cards
                Row(
                    modifier = Modifier.fillMaxWidth(),
                    horizontalArrangement = Arrangement.spacedBy(8.dp),
                ) {
                    PixelCard(
                        modifier = Modifier.weight(1f, fill = true),
                        fill = Pal.cream,
                    ) {
                        Column(
                            modifier = Modifier
                                .fillMaxSize()
                                .padding(12.dp),
                            horizontalAlignment = Alignment.CenterHorizontally,
                            verticalArrangement = Arrangement.Center,
                        ) {
                            Text("Total Revenue", style = PixelLabel.copy(color = Pal.wood, fontSize = 11.sp))
                            Spacer(Modifier.height(4.dp))
                            Text(formatCents(totalRevenue), style = MaterialTheme.typography.titleLarge.copy(color = Pal.wood))
                        }
                    }
                    PixelCard(
                        modifier = Modifier.weight(1f, fill = true),
                        fill = Pal.cream,
                    ) {
                        Column(
                            modifier = Modifier
                                .fillMaxSize()
                                .padding(12.dp),
                            horizontalAlignment = Alignment.CenterHorizontally,
                            verticalArrangement = Arrangement.Center,
                        ) {
                            Text("Infrastructure Fund", style = PixelLabel.copy(color = Pal.teal, fontSize = 11.sp))
                            Spacer(Modifier.height(4.dp))
                            Text(formatCents(infraFund), style = MaterialTheme.typography.titleLarge.copy(color = Pal.teal))
                        }
                    }
                    PixelCard(
                        modifier = Modifier.weight(1f, fill = true),
                        fill = Pal.cream,
                    ) {
                        Column(
                            modifier = Modifier
                                .fillMaxSize()
                                .padding(12.dp),
                            horizontalAlignment = Alignment.CenterHorizontally,
                            verticalArrangement = Arrangement.Center,
                        ) {
                            Text("Your Share", style = PixelLabel.copy(color = Pal.navy, fontSize = 11.sp))
                            Spacer(Modifier.height(4.dp))
                            Text(formatCents((totalRevenue * (userSplit.toLong()) / 100).coerceAtLeast(0L)), style = MaterialTheme.typography.titleLarge.copy(color = Pal.navy))
                        }
                    }
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
                        PixelSlider(
                            value = agentSplit.toFloat(),
                            onValueChange = { agentSplit = it.roundToInt().coerceIn(0, 100); userSplit = 100 - agentSplit },
                            valueRange = 0f..100f,
                            steps = 20,
                        )
                    }
                    Column(Modifier.weight(1f)) {
                        Text("Your Share: ${userSplit}%", style = MaterialTheme.typography.labelLarge)
                        PixelSlider(
                            value = userSplit.toFloat(),
                            onValueChange = { userSplit = it.roundToInt().coerceIn(0, 100); agentSplit = 100 - userSplit },
                            valueRange = 0f..100f,
                            steps = 20,
                        )
                    }
                }
                Row(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                    PixelButton("Save Split", { viewModel.setProfitSplit(agentSplit, userSplit) }, small = true)
                }

                Spacer(Modifier.height(12.dp))

                // Payment connectors
                Text("Payment Connectors", style = MaterialTheme.typography.titleSmall)
                Spacer(Modifier.height(4.dp))
                Text(
                    text = "Configure payment providers for monetizing agent APIs. Set up connectors in the Connectors screen.",
                    style = MaterialTheme.typography.bodySmall,
                    color = Dim,
                )
                Spacer(Modifier.height(8.dp))

                val paymentConnectors = state.connectors?.filter { it.category == "Payments" } ?: emptyList()
                if (paymentConnectors.isEmpty()) {
                    Text(
                        text = "No payment connector configured. Open Connectors to add PayPal or Polar.",
                        style = MaterialTheme.typography.bodySmall,
                        color = Dim,
                    )
                    Spacer(Modifier.height(6.dp))
                    PixelButton("Open Connectors", { onOpenConnectors(null) }, small = true, fill = Pal.tan)
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
                                        "ready", "configured" -> Pal.teal
                                        "needs_setup" -> Pal.wood
                                        "error" -> Pal.red
                                        else -> Pal.ink
                                    }
                                    PixelPill(
                                        text = connector.status.replaceFirstChar { it.uppercase() },
                                        fill = color.copy(alpha = 0.14f),
                                        textColor = color,
                                    )
                                }
                                Text(
                                    text = def?.description ?: "Payment connector",
                                    style = MaterialTheme.typography.bodySmall,
                                    color = Dim,
                                )
                            }
                            PixelButton("Configure", { onOpenConnectors(connector.id) }, small = true, fill = Pal.tan)
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
                        color = Dim,
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

            SettingsCard(title = "Model providers") {
                Text(
                    text = "The app tries providers in priority order and falls back when one fails. Keyless providers are enabled by default.",
                    style = MaterialTheme.typography.bodySmall,
                    color = Dim,
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
                    Spacer(Modifier.height(6.dp))
                    PixelButton(
                        text = if (showAllProviders) "Show less" else "Show ${keyedProviders.size} providers needing API key",
                        onClick = { showAllProviders = !showAllProviders },
                        small = true,
                        fill = Pal.tan,
                    )
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

            SettingsCard(title = "Data & privacy") {
                Text(
                    text = "API keys and connector credentials are encrypted with a key in the Android Keystore. The database stays on this device.",
                    style = MaterialTheme.typography.bodySmall,
                    color = Dim,
                )
                Spacer(Modifier.height(10.dp))
                Row(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                    PixelButton(
                        text = "Export to Device",
                        onClick = {
                            createExportFileLauncher.launch("agoria-export-${System.currentTimeMillis()}.json")
                        },
                        small = true,
                    )
                    PixelButton(
                        text = "Reset app",
                        onClick = { showReset = true },
                        small = true,
                        fill = Pal.red,
                    )
                }
            }

            SettingsCard(title = "Backup & Restore") {
                Text(
                    text = "Backup your agents, settings, and data to a file you keep, and restore it on any phone.",
                    style = MaterialTheme.typography.bodySmall,
                    color = Dim,
                )
                Spacer(Modifier.height(10.dp))

                // Local backup
                Row(
                    modifier = Modifier.fillMaxWidth(),
                    horizontalArrangement = Arrangement.spacedBy(8.dp),
                    verticalAlignment = Alignment.CenterVertically,
                ) {
                    Column(Modifier.weight(1f)) {
                        Row(verticalAlignment = Alignment.CenterVertically) {
                            Icon(
                                imageVector = Icons.Default.FileDownload,
                                contentDescription = null,
                                tint = Pal.teal,
                                modifier = Modifier.size(24.dp),
                            )
                            Spacer(Modifier.width(8.dp))
                            Column {
                                Text("Export to Device", style = MaterialTheme.typography.titleSmall)
                                Text(
                                    text = "Save a JSON copy to the Downloads folder",
                                    style = MaterialTheme.typography.bodySmall,
                                    color = Dim,
                                )
                                Text(
                                    text = "API keys and connector credentials are not included and must be re-entered after restoring on a new device.",
                                    style = MaterialTheme.typography.bodySmall,
                                    color = Dim,
                                )
                            }
                        }
                    }
                    PixelButton(
                        text = "Export",
                        onClick = {
                            createExportFileLauncher.launch("agoria-export-${System.currentTimeMillis()}.json")
                        },
                        small = true,
                    )
                }

                Spacer(Modifier.height(10.dp))

                // Local restore
                Row(
                    modifier = Modifier.fillMaxWidth(),
                    horizontalArrangement = Arrangement.spacedBy(8.dp),
                    verticalAlignment = Alignment.CenterVertically,
                ) {
                    Column(Modifier.weight(1f)) {
                        Row(verticalAlignment = Alignment.CenterVertically) {
                            Icon(
                                imageVector = Icons.Default.FileUpload,
                                contentDescription = null,
                                tint = Pal.wood,
                                modifier = Modifier.size(24.dp),
                            )
                            Spacer(Modifier.width(8.dp))
                            Column {
                                Text("Import from Device", style = MaterialTheme.typography.titleSmall)
                                Text(
                                    text = "Pick a backup JSON file to restore",
                                    style = MaterialTheme.typography.bodySmall,
                                    color = Dim,
                                )
                            }
                        }
                    }
                    PixelButton(
                        text = "Import",
                        onClick = { pickImportFileLauncher.launch("application/json") },
                        small = true,
                    )
                }

                Spacer(Modifier.height(12.dp))
                Text("Automatic backups", style = MaterialTheme.typography.titleSmall)
                Text(
                    text = "A full snapshot is written once a day inside the app, so an update or an " +
                        "accidental reset never leaves you with nothing.",
                    style = MaterialTheme.typography.bodySmall,
                    color = Dim,
                )
                Spacer(Modifier.height(6.dp))
                val backups = remember { mutableStateOf(viewModel.autoBackups()) }
                backups.value.take(3).forEach { file ->
                    Row(
                        modifier = Modifier
                            .fillMaxWidth()
                            .padding(vertical = 3.dp),
                        verticalAlignment = Alignment.CenterVertically,
                    ) {
                        Text(
                            text = android.text.format.DateFormat.format("MMM d, HH:mm", file.lastModified()).toString(),
                            style = MaterialTheme.typography.bodySmall,
                            modifier = Modifier.weight(1f),
                        )
                        PixelButton(
                            text = "Restore",
                            onClick = {
                                viewModel.restoreAutoBackup(file) { _, message ->
                                    backups.value = viewModel.autoBackups()
                                    scope.launch { snackbarHostState.showSnackbar(message) }
                                }
                            },
                            small = true,
                            fill = Pal.tan,
                        )
                    }
                }
                if (backups.value.isEmpty()) {
                    Text(
                        text = "No automatic backup yet.",
                        style = MaterialTheme.typography.bodySmall,
                        color = Dim,
                    )
                }
                Spacer(Modifier.height(6.dp))
                PixelButton(
                    text = "Back up now",
                    onClick = {
                        viewModel.writeAutoBackupNow { file ->
                            backups.value = viewModel.autoBackups()
                            scope.launch {
                                snackbarHostState.showSnackbar(
                                    if (file != null) "Backed up" else "Backup failed",
                                )
                            }
                        }
                    },
                    small = true,
                    fill = Pal.tan,
                )
            }

            SettingsCard(title = "App Updates") {
                val updateState = remember { mutableStateOf<UpdateState>(UpdateState.Idle) }
    var updateRefusalReason by remember { mutableStateOf<String?>(null) }
                val releaseInfo = remember { mutableStateOf<UpdateManager.ReleaseInfo?>(null) }

                when (updateState.value) {
                    UpdateState.Idle -> {
                        Row(
                            modifier = Modifier.fillMaxWidth(),
                            horizontalArrangement = Arrangement.spacedBy(8.dp),
                            verticalAlignment = Alignment.CenterVertically,
                        ) {
                            Column(Modifier.weight(1f)) {
                                Row(verticalAlignment = Alignment.CenterVertically) {
                                    Icon(
                                        imageVector = Icons.Default.SystemUpdate,
                                        contentDescription = null,
                                        tint = Pal.teal,
                                        modifier = Modifier.size(24.dp),
                                    )
                                    Spacer(Modifier.width(8.dp))
                                    Column {
                                        Text("Check for Updates", style = MaterialTheme.typography.titleSmall)
                                        Text(
                                            text = "Current version: $currentVersionName ($currentVersionCode)",
                                            style = MaterialTheme.typography.bodySmall,
                                            color = Dim,
                                        )
                                    }
                                }
                            }
                            PixelButton(
                                text = "Check",
                                onClick = {
                                    updateRefusalReason = null
                                    updateState.value = UpdateState.Checking
                                    viewModel.checkForUpdates { result ->
                                        when (result) {
                                            is com.agoria.app.util.UpdateManager.CheckResult.Available -> {
                                                releaseInfo.value = result
                                                updateState.value = UpdateState.UpdateAvailable
                                            }
                                            com.agoria.app.util.UpdateManager.CheckResult.UpToDate -> {
                                                releaseInfo.value = null
                                                updateState.value = UpdateState.UpToDate
                                                updateRefusalReason = null
                                            }
                                            com.agoria.app.util.UpdateManager.CheckResult.Invalid -> {
                                                releaseInfo.value = null
                                                updateState.value = UpdateState.Idle
                                                updateRefusalReason = "Update information is unavailable or invalid."
                                            }
                                            com.agoria.app.util.UpdateManager.CheckResult.NetworkError -> {
                                                releaseInfo.value = null
                                                updateState.value = UpdateState.Idle
                                                updateRefusalReason = "Update failed: unable to reach the update server. Check your network and try again."
                                            }
                                        }
                                    }
                                },
                                small = true,
                            )
                        }
                    }
                    UpdateState.Checking -> {
                        // No spinner: Material's round progress indicator breaks the pixel look.
                        Text("Checking for updates...", style = MaterialTheme.typography.labelLarge)
                    }
                    UpdateState.UpToDate -> {
                        val remoteVersion = releaseInfo.value?.versionName ?: "unknown"
                        Row(
                            modifier = Modifier.fillMaxWidth(),
                            horizontalArrangement = Arrangement.spacedBy(8.dp),
                            verticalAlignment = Alignment.CenterVertically,
                        ) {
                            Column(Modifier.weight(1f)) {
                                Row(verticalAlignment = Alignment.CenterVertically) {
                                    Icon(
                                        imageVector = Icons.Default.Settings,
                                        contentDescription = null,
                                        tint = Pal.teal,
                                        modifier = Modifier.size(24.dp),
                                    )
                                    Spacer(Modifier.width(8.dp))
                                    Column {
                                        Text("Up to Date", style = MaterialTheme.typography.titleSmall)
                                        Text(
                                            text = "Installed: $currentVersionName | Latest: $remoteVersion",
                                            style = MaterialTheme.typography.bodySmall,
                                            color = Dim,
                                        )
                                    }
                                }
                            }
                            PixelButton(
                                text = "Check Again",
                                onClick = {
                                    updateRefusalReason = null
                                    updateState.value = UpdateState.Idle
                                },
                                small = true,
                                fill = Pal.tan,
                            )
                        }
                    }
                    UpdateState.UpdateAvailable -> {
                        val info = releaseInfo.value!!
                        Column(Modifier.fillMaxWidth()) {
                            Row(verticalAlignment = Alignment.CenterVertically) {
                                Icon(
                                    imageVector = Icons.Default.SystemUpdate,
                                    contentDescription = null,
                                    tint = Pal.wood,
                                    modifier = Modifier.size(24.dp),
                                )
                                Spacer(Modifier.width(8.dp))
                                Column {
                                    Text("Update Available: ${info.versionName}", style = MaterialTheme.typography.titleSmall)
                                    Text(
                                        text = "Released: ${info.publishedAt.take(10)}",
                                        style = MaterialTheme.typography.bodySmall,
                                        color = Dim,
                                    )
                                }
                            }
                            if (info.releaseNotes.isNotBlank()) {
                                Text(
                                    text = "What's new:\n${info.releaseNotes}",
                                    style = MaterialTheme.typography.bodySmall,
                                    color = Dim,
                                    modifier = Modifier.padding(top = 8.dp, bottom = 8.dp).fillMaxWidth(),
                                )
                            }
                            val downloadProgress = remember { mutableStateOf<Int?>(null) }
                            val progress = downloadProgress.value
                            if (progress != null) {
                                PixelProgressBar(
                                    fraction = progress / 100f,
                                    modifier = Modifier
                                        .fillMaxWidth()
                                        .padding(bottom = 8.dp),
                                )
                            }
                            Row(
                                modifier = Modifier.fillMaxWidth(),
                                horizontalArrangement = Arrangement.spacedBy(8.dp),
                            ) {
                                PixelButton(
                                    text = if (progress != null) "Downloading $progress%" else "Download & Install",
                                    onClick = {
                                        // Ignore taps while a download is already running (replaces enabled = false).
                                        if (downloadProgress.value == null) {
                                            downloadProgress.value = 0
                                            viewModel.downloadAndInstallUpdate(info, { pct -> downloadProgress.value = pct }, { result ->
                                                downloadProgress.value = null
                                                when (result) {
                                                    is com.agoria.app.util.UpdateManager.UpdateResult.Installed -> {
                                                        updateState.value = UpdateState.Idle
                                                        updateRefusalReason = null
                                                    }
                                                    com.agoria.app.util.UpdateManager.UpdateResult.NeedsPermission -> {
                                                        updateState.value = UpdateState.Idle
                                                        updateRefusalReason = "Install blocked: allow \"Install from this source\" in Settings, then try again."
                                                    }
                                                    is com.agoria.app.util.UpdateManager.UpdateResult.Refused -> {
                                                        updateState.value = UpdateState.Idle
                                                        updateRefusalReason = "Update refused: ${result.reason}"
                                                    }
                                                    com.agoria.app.util.UpdateManager.UpdateResult.NetworkError -> {
                                                        updateState.value = UpdateState.Idle
                                                        updateRefusalReason = "Update failed: unable to reach the update server. Check your network and try again."
                                                    }
                                                }
                                            })
                                        }
                                    },
                                    small = true,
                                    fill = if (progress != null) Pal.tan else Pal.amber,
                                )
                                PixelButton(
                                    text = "Later",
                                    onClick = { updateState.value = UpdateState.Idle },
                                    small = true,
                                    fill = Pal.tan,
                                )
                            }
                            updateRefusalReason?.let { reason ->
                                Text(
                                    text = reason,
                                    style = MaterialTheme.typography.bodySmall,
                                    color = Pal.amber,
                                    modifier = Modifier.padding(top = 4.dp),
                                )
                            }
                        }
                    }
                }
            }

            SettingsCard(title = "Agent Skills") {
                Text(
                    text = "Install skills on agents to give them new capabilities. Each skill provides specific actions (code review, testing, deployment, git, debugging, research, security, performance).",
                    style = MaterialTheme.typography.bodySmall,
                    color = Dim,
                )
                Spacer(Modifier.height(8.dp))
                Row(
                    modifier = Modifier.fillMaxWidth(),
                    horizontalArrangement = Arrangement.spacedBy(8.dp),
                    verticalAlignment = Alignment.CenterVertically,
                ) {
                    Column(Modifier.weight(1f)) {
                        Row(verticalAlignment = Alignment.CenterVertically) {
                            Icon(
                                imageVector = Icons.Default.FileDownload,
                                contentDescription = null,
                                tint = Pal.teal,
                                modifier = Modifier.size(24.dp),
                            )
                            Spacer(Modifier.width(8.dp))
                            Column {
                                Text("Open Skills", style = MaterialTheme.typography.titleSmall)
                                Text(
                                    text = "Browse and install skills for your agents",
                                    style = MaterialTheme.typography.bodySmall,
                                    color = Dim,
                                )
                            }
                        }
                    }
                    PixelButton(
                        text = "Open",
                        onClick = onOpenSkills,
                        small = true,
                        fill = Pal.amber,
                    )
                }
            }

            SettingsCard(title = "About") {
                Text("Dunder $currentVersionName ($currentVersionCode)", style = MaterialTheme.typography.titleMedium)
                Text(
                    text = "A local autonomous office for Android 13+. Agents run on free model APIs and keep their thoughts in a local diary. Builds and outward-facing actions require approval.",
                    style = MaterialTheme.typography.bodySmall,
                    color = Dim,
                )
                Spacer(Modifier.height(8.dp))
                Text("Free model list", style = MaterialTheme.typography.labelLarge)
                Text(
                    text = "github.com/mnfst/awesome-free-llm-apis",
                    style = MaterialTheme.typography.bodySmall,
                    color = Pal.teal,
                )
                Text("Office assets", style = MaterialTheme.typography.labelLarge)
                Text(
                    text = "github.com/Parcha-ai/ai-office",
                    style = MaterialTheme.typography.bodySmall,
                    color = Pal.teal,
                )
                Spacer(Modifier.height(8.dp))
                Text(
                    text = "Free tiers change. Check each provider's docs for current limits and terms. Some free providers log prompts; avoid putting secrets in prompts.",
                    style = MaterialTheme.typography.bodySmall,
                    color = Dim,
                )
            }

            Spacer(Modifier.height(24.dp))
        }
    }

    if (showReset) {
        ResetDialog(
            onConfirm = {
                viewModel.resetData()
                showReset = false
            },
            onDismiss = { showReset = false },
        )
    }
}

/** Pixel section card for this page: cream PixelCard (ink text by default) with 14 dp padding and a monospace title. */
@Composable
private fun SettingsCard(
    title: String,
    content: @Composable ColumnScope.() -> Unit,
) {
    PixelCard(modifier = Modifier.fillMaxWidth()) {
        Column(Modifier.padding(14.dp)) {
            Text(title, style = MaterialTheme.typography.titleMedium)
            Spacer(Modifier.height(8.dp))
            content()
        }
    }
}

/** Reset confirmation: the shared [PixelDialog] (square, ink border, hard shadow). */
@Composable
private fun ResetDialog(
    onConfirm: () -> Unit,
    onDismiss: () -> Unit,
) {
    PixelDialog(onDismiss = onDismiss, title = "Reset Dunder?") {
        Text(
            text = "This deletes all agents, diaries, builds, approvals, and connector configuration on this device. It cannot be undone.",
            style = MaterialTheme.typography.bodyMedium,
        )
        Row(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
            PixelButton(
                text = "Reset everything",
                onClick = onConfirm,
                small = true,
                fill = Pal.red,
            )
            PixelButton(
                text = "Cancel",
                onClick = onDismiss,
                small = true,
                fill = Pal.tan,
            )
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

    val padding = if (compact) 8.dp else 12.dp
    val verticalPadding = if (compact) 4.dp else 6.dp

    // Tan fill so the row stands out from the cream section card; PixelCard keeps the square ink border.
    PixelCard(
        modifier = Modifier
            .fillMaxWidth()
            .padding(vertical = verticalPadding),
        fill = Pal.tan,
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
                        color = Pal.navy,
                        maxLines = 1,
                        overflow = TextOverflow.Ellipsis,
                    )
                }
                Spacer(Modifier.width(8.dp))
                PixelSwitch(checked = provider.enabled, onChange = onToggle)
            }

            if (spec != null && !compact) {
                Text(
                    text = spec.freeNote,
                    style = MaterialTheme.typography.bodySmall,
                    color = Pal.navy,
                    modifier = Modifier.padding(top = 4.dp),
                )
            }

            if (test != null) {
                Spacer(Modifier.height(4.dp))
                if (test.testing) {
                    Text("Testing...", style = MaterialTheme.typography.labelSmall)
                } else if (test.ok == true) {
                    Text(
                        text = test.message,
                        style = MaterialTheme.typography.labelSmall,
                        color = Pal.teal,
                    )
                } else if (test.ok == false) {
                    Text(
                        text = test.message,
                        style = MaterialTheme.typography.labelSmall,
                        color = Pal.red,
                        maxLines = 2,
                        overflow = TextOverflow.Ellipsis,
                    )
                }
            }

            Spacer(Modifier.height(6.dp))
            Row(horizontalArrangement = Arrangement.spacedBy(6.dp)) {
                PixelButton(
                    text = if (expanded) "Hide setup" else "Configure",
                    onClick = { expanded = !expanded },
                    small = true,
                    fill = Pal.cream,
                )
                PixelButton(text = "Test", onClick = onTest, small = true, fill = Pal.cream)
            }

            if (expanded) {
                Spacer(Modifier.height(8.dp))
                PixelTextField(
                    value = key,
                    onValueChange = { key = it },
                    label = if (provider.apiKeyEnc != null) {
                        "${spec?.keyLabel ?: "API key"} (saved)"
                    } else {
                        spec?.keyLabel ?: "API key"
                    },
                    hint = spec?.keyHint ?: "Optional. Some providers work without a key.",
                    password = true,
                    labelColor = Pal.navy,
                    hintColor = Pal.navy,
                    modifier = Modifier.fillMaxWidth(),
                )
                Spacer(Modifier.height(6.dp))
                Row(horizontalArrangement = Arrangement.spacedBy(6.dp)) {
                    PixelButton(
                        text = "Save key",
                        onClick = {
                            onSaveKey(key)
                            key = ""
                        },
                        small = true,
                    )
                    if (provider.apiKeyEnc != null) {
                        PixelButton(
                            text = "Clear key",
                            onClick = {
                                onSaveKey("")
                                key = ""
                            },
                            small = true,
                            fill = Pal.red,
                        )
                    }
                }

                Spacer(Modifier.height(10.dp))
                PixelTextField(
                    value = model,
                    onValueChange = { model = it },
                    label = "Model",
                    hint = spec?.models?.joinToString(", ") { it.id }?.take(400) ?: "",
                    labelColor = Pal.navy,
                    hintColor = Pal.navy,
                    modifier = Modifier.fillMaxWidth(),
                )
                Spacer(Modifier.height(6.dp))
                PixelButton(text = "Save model", onClick = { onSaveModel(model) }, small = true)

                if (spec?.needsExtra == true) {
                    Spacer(Modifier.height(10.dp))
                    PixelTextField(
                        value = extra,
                        onValueChange = { extra = it },
                        label = spec.extraLabel,
                        hint = spec.extraHint,
                        labelColor = Pal.navy,
                        hintColor = Pal.navy,
                        modifier = Modifier.fillMaxWidth(),
                    )
                    Spacer(Modifier.height(6.dp))
                    PixelButton(text = "Save ${spec.extraLabel}", onClick = { onSaveExtra(extra) }, small = true)
                }

                if (!provider.lastError.isNullOrBlank()) {
                    Spacer(Modifier.height(6.dp))
                    Text(
                        text = provider.lastError,
                        style = MaterialTheme.typography.labelSmall,
                        color = Pal.red,
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
            color = Pal.teal,
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
            Text(time, style = MaterialTheme.typography.bodySmall, color = Dim)
        }
        Text(
            formatCents(amountCents),
            style = MaterialTheme.typography.bodyMedium,
            color = Pal.teal,
        )
    }
}
