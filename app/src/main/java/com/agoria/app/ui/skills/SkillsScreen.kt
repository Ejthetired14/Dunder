package com.agoria.app.ui.skills

import androidx.compose.animation.animateContentSize
import androidx.compose.foundation.background
import androidx.compose.foundation.border
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.items
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.verticalScroll
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.CheckCircle
import androidx.compose.material.icons.filled.Settings
import androidx.compose.material3.Icon
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.semantics.contentDescription
import androidx.compose.ui.semantics.onClick
import androidx.compose.ui.semantics.role
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import com.agoria.app.core.SkillActionDef
import com.agoria.app.core.SkillCatalog
import com.agoria.app.core.SkillDef
import com.agoria.app.data.AgentEntity
import com.agoria.app.data.AgentSkillEntity
import com.agoria.app.data.Repository
import com.agoria.app.data.SkillEntity
import com.agoria.app.ui.AgoriaViewModel
import com.agoria.app.ui.components.EmptyState
import com.agoria.app.ui.components.PixelCard
import com.agoria.app.ui.components.PixelScaffold
import com.agoria.app.ui.office.world.Pal
import com.agoria.app.ui.office.world.PixelBody
import com.agoria.app.ui.office.world.PixelButton
import com.agoria.app.ui.office.world.PixelDialog
import com.agoria.app.ui.office.world.PixelIconButton
import com.agoria.app.ui.office.world.PixelLabel
import com.agoria.app.ui.office.world.PixelPanel
import com.agoria.app.ui.office.world.PixelTextField
import com.agoria.app.util.Time
import kotlinx.coroutines.launch
import org.json.JSONObject

/**
 * Skills screen - browse, install, and configure skills for agents.
 */
@Composable
fun SkillsScreen(
    viewModel: AgoriaViewModel,
    onBack: () -> Unit,
) {
    val skills = viewModel.skills.value
    val agents = viewModel.agents.value
    val scrollState = rememberScrollState()

    // Selected agent for filtering
    var selectedAgentId by remember { mutableStateOf<String?>(null) }
    val selectedAgent = selectedAgentId?.let { id -> agents.find { it.id == id } }

    // Get installed skills for selected agent
    val agentSkills = remember { mutableStateOf<List<AgentSkillEntity>>(emptyList()) }

    // Fetch agent skills when selectedAgentId changes
    androidx.compose.runtime.LaunchedEffect(selectedAgentId) {
        if (selectedAgentId != null) {
            viewModel.appScope.launch {
                val skills = viewModel.agentSkillConfigs(selectedAgentId!!)
                agentSkills.value = skills
            }
        } else {
            agentSkills.value = emptyList()
        }
    }

    PixelScaffold(
        title = "Skills",
        onBack = onBack,
        actions = {
            PixelIconButton(
                icon = Icons.Default.Settings,
                description = "Sync skills from catalog",
                onClick = { viewModel.syncSkillCatalog() },
            )
        },
    ) { padding ->
        Column(
            modifier = Modifier
                .fillMaxSize()
                .padding(padding)
                .padding(16.dp)
                .verticalScroll(scrollState),
            verticalArrangement = Arrangement.spacedBy(16.dp),
        ) {
            // Agent selector
            if (agents.isNotEmpty()) {
                PixelPanel {
                    Column(
                        modifier = Modifier.fillMaxWidth(),
                        verticalArrangement = Arrangement.spacedBy(8.dp),
                    ) {
                        Row(
                            modifier = Modifier.fillMaxWidth(),
                            horizontalArrangement = Arrangement.spacedBy(8.dp),
                            verticalAlignment = Alignment.CenterVertically,
                        ) {
                            Text("Filter by agent:", style = PixelLabel)
                            Spacer(Modifier.width(8.dp))
                            agents.forEach { agent ->
                                val isSelected = selectedAgentId == agent.id
                                PixelButton(
                                    text = agent.name,
                                    onClick = { selectedAgentId = if (isSelected) null else agent.id },
                                    small = true,
                                    fill = if (isSelected) Pal.amber else Pal.tan,
                                )
                            }
                            PixelButton(
                                text = "All",
                                onClick = { selectedAgentId = null },
                                small = true,
                                fill = if (selectedAgentId == null) Pal.amber else Pal.tan,
                            )
                        }
                        if (selectedAgent != null) {
                            Text(
                                text = "Showing skills for ${selectedAgent.name} (${agentSkills.value.map { it.skillId }.toSet().size} installed)",
                                style = MaterialTheme.typography.bodySmall,
                                color = Pal.navy.copy(alpha = 0.75f),
                            )
                        }
                    }
                }
                Spacer(Modifier.height(8.dp))
            }

            // Skills by category
            SkillCatalog.categories.forEach { category ->
                val enabledCategorySkills = skills.filter { it.category == category && it.enabled }

                if (enabledCategorySkills.isNotEmpty()) {
                    PixelCard {
                        Column(
                            modifier = Modifier.fillMaxWidth(),
                            verticalArrangement = Arrangement.spacedBy(12.dp),
                        ) {
                            Text(category, style = MaterialTheme.typography.titleMedium, color = Pal.amber)
                            LazyColumn(
                                modifier = Modifier.fillMaxWidth(),
                                verticalArrangement = Arrangement.spacedBy(8.dp),
                            ) {
                                items(enabledCategorySkills) { skill ->
                                    SkillRow(
                                        skill = skill,
                                        agentId = selectedAgentId,
                                        installed = agentSkills.value.map { it.skillId }.toSet().contains(skill.id),
                                        agentSkills = agentSkills.value.associateBy { it.skillId },
                                        onInstall = { agentId, skillId ->
                                            viewModel.installSkill(agentId, skillId)
                                        },
                                        onUninstall = { agentId, skillId ->
                                            viewModel.uninstallSkill(agentId, skillId)
                                        },
                                        onConfigure = { agentId, skillId, config ->
                                            viewModel.updateSkillConfig(agentId, skillId, config)
                                        },
                                    )
                                }
                            }
                        }
                    }
                    Spacer(Modifier.height(12.dp))
                }
            }

            if (skills.isEmpty()) {
                EmptyState(
                    title = "No skills available",
                    body = "Pull to refresh or check back later.",
                )
            }
        }
    }
}

@Composable
private fun SkillRow(
    skill: SkillEntity,
    agentId: String?,
    installed: Boolean,
    agentSkills: Map<String, AgentSkillEntity>,
    onInstall: (String, String) -> Unit,
    onUninstall: (String, String) -> Unit,
    onConfigure: (String, String, JSONObject) -> Unit,
) {
    val skillDef = SkillCatalog.get(skill.id)
    val agentSkill = agentSkills[skill.id]
    var showConfig by remember { mutableStateOf(false) }
    var showInfo by remember { mutableStateOf(false) }
    var configText by remember { mutableStateOf(agentSkill?.configJson ?: "{}") }

    val isSelectedAgent = agentId != null

    PixelPanel(
        modifier = Modifier.fillMaxWidth(),
        fill = if (installed) Pal.amber.copy(alpha = 0.1f) else Pal.cream,
    ) {
        Column(
            modifier = Modifier
                .fillMaxWidth()
                .padding(12.dp),
            verticalArrangement = Arrangement.spacedBy(8.dp),
        ) {
            Row(
                modifier = Modifier.fillMaxWidth(),
                horizontalArrangement = Arrangement.spacedBy(12.dp),
                verticalAlignment = Alignment.Top,
            ) {
                Column(modifier = Modifier.weight(1f)) {
                    Row(
                        modifier = Modifier.fillMaxWidth(),
                        horizontalArrangement = Arrangement.SpaceBetween,
                        verticalAlignment = Alignment.CenterVertically,
                    ) {
                        Text(skill.name, style = MaterialTheme.typography.titleSmall, color = Pal.navy)
                        if (installed) {
                            Box(
                                modifier = Modifier
                                    .padding(4.dp)
                                    .size(18.dp),
                            ) {
                                Icon(
                                    imageVector = Icons.Default.CheckCircle,
                                    contentDescription = "Installed",
                                    tint = Pal.teal,
                                )
                            }
                        }
                    }
                    Text(
                        skill.description,
                        style = MaterialTheme.typography.bodySmall,
                        color = Pal.navy.copy(alpha = 0.75f),
                        maxLines = 2,
                        overflow = TextOverflow.Ellipsis,
                    )
                    if (skillDef != null) {
                        Row(
                            modifier = Modifier.fillMaxWidth(),
                            horizontalArrangement = Arrangement.spacedBy(6.dp),
                        ) {
                            skillDef.actions.take(3).forEach { action ->
                                Text(action.label, style = PixelLabel.copy(fontSize = 10.sp))
                            }
                            if (skillDef.actions.size > 3) {
                                Text("+${skillDef.actions.size - 3} more", style = PixelLabel.copy(fontSize = 10.sp))
                            }
                        }
                    }
                }
            }

            if (isSelectedAgent) {
                Row(
                    modifier = Modifier.fillMaxWidth(),
                    horizontalArrangement = Arrangement.spacedBy(8.dp),
                    verticalAlignment = Alignment.CenterVertically,
                ) {
                    if (installed) {
                        PixelButton(
                            text = if (showConfig) "Hide Config" else "Configure",
                            onClick = { showConfig = !showConfig },
                            small = true,
                            fill = Pal.tan,
                        )
                        PixelButton(
                            text = "Uninstall",
                            onClick = { onUninstall(agentId!!, skill.id) },
                            small = true,
                            fill = Pal.ink.copy(alpha = 0.1f),
                        )
                    } else {
                        PixelButton(
                            text = "Install",
                            onClick = { onInstall(agentId!!, skill.id) },
                            small = true,
                            fill = Pal.amber,
                        )
                    }
                    PixelButton(
                        text = "Info",
                        onClick = { showInfo = true },
                        small = true,
                        fill = Pal.tan,
                    )
                }

                if (showConfig) {
                    ConfigPanel(
                        skill = skill,
                        skillDef = skillDef,
                        configText = configText,
                        onConfigChange = { configText = it },
                        onSave = {
                            try {
                                val json = JSONObject(configText)
                                onConfigure(agentId!!, skill.id, json)
                                showConfig = false
                            } catch (e: Exception) {
                                // Invalid JSON, keep panel open
                            }
                        },
                        onCancel = { showConfig = false },
                    )
                }

                if (showInfo) {
                    InfoDialog(
                        skill = skill,
                        skillDef = skillDef,
                        onDismiss = { showInfo = false },
                    )
                }
            }
        }
    }
}

@Composable
private fun ConfigPanel(
    skill: SkillEntity,
    skillDef: SkillDef?,
    configText: String,
    onConfigChange: (String) -> Unit,
    onSave: () -> Unit,
    onCancel: () -> Unit,
) {
    var text by remember { mutableStateOf(configText) }

    PixelCard(
        modifier = Modifier.fillMaxWidth(),
        fill = Pal.cream.copy(alpha = 0.5f),
    ) {
        Column(
            modifier = Modifier
                .fillMaxWidth()
                .padding(12.dp),
            verticalArrangement = Arrangement.spacedBy(8.dp),
        ) {
            Text("Configuration (JSON)", style = MaterialTheme.typography.labelMedium, color = Pal.navy)
            PixelTextField(
                value = text,
                onValueChange = onConfigChange,
                label = "Config",
                singleLine = false,
            )
            if (skillDef != null) {
                val actionList = skillDef.actions.map { it.id }.joinToString(", ")
                Text(
                    "Available actions: $actionList",
                    style = MaterialTheme.typography.bodySmall,
                    color = Pal.navy.copy(alpha = 0.5f),
                )
            }
            Row(
                modifier = Modifier.fillMaxWidth(),
                horizontalArrangement = Arrangement.End,
            ) {
                PixelButton("Cancel", onClick = onCancel, small = true, fill = Pal.tan)
                PixelButton("Save", onClick = onSave, small = true, fill = Pal.amber)
            }
        }
    }
}

@Composable
private fun InfoDialog(
    skill: SkillEntity,
    skillDef: SkillDef?,
    onDismiss: () -> Unit,
) {
    PixelDialog(
        title = skill.name,
        onDismiss = onDismiss,
    ) {
        Column(
            modifier = Modifier
                .fillMaxWidth()
                .padding(16.dp),
            verticalArrangement = Arrangement.spacedBy(12.dp),
        ) {
            Text(skill.description, style = MaterialTheme.typography.bodyMedium, color = Pal.navy)
            Text("Category: ${skill.category}", style = MaterialTheme.typography.bodySmall, color = Pal.navy.copy(alpha = 0.75f))
            Text("Version: ${skill.version}", style = MaterialTheme.typography.bodySmall, color = Pal.navy.copy(alpha = 0.75f))
            Text("Actions: ${skill.manifestJson}", style = MaterialTheme.typography.bodySmall, color = Pal.navy.copy(alpha = 0.75f))

            if (skillDef != null) {
                Spacer(Modifier.height(8.dp))
                Text("Available Actions:", style = MaterialTheme.typography.labelMedium, color = Pal.amber)
                skillDef.actions.forEach { action ->
                    PixelPanel(
                        modifier = Modifier.fillMaxWidth(),
                        fill = Pal.cream,
                    ) {
                        Column(
                            modifier = Modifier
                                .fillMaxWidth()
                                .padding(12.dp),
                            verticalArrangement = Arrangement.spacedBy(4.dp),
                        ) {
                            Text(action.label, style = MaterialTheme.typography.titleSmall, color = Pal.navy)
                            Text(action.description, style = MaterialTheme.typography.bodySmall, color = Pal.navy.copy(alpha = 0.75f))
                            Text("Args: ${action.argsHint}", style = MaterialTheme.typography.bodySmall, color = Pal.navy.copy(alpha = 0.5f))
                        }
                    }
                }
            }
        }
    }
}