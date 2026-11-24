package com.agoria.app.ui.project

import androidx.compose.foundation.clickable
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
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.items
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.verticalScroll
import androidx.compose.material3.AlertDialog
import androidx.compose.material3.ExperimentalMaterial3Api
import androidx.compose.material3.FilledTonalButton
import androidx.compose.material3.Icon
import androidx.compose.material3.LinearProgressIndicator
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedTextField
import androidx.compose.material3.Surface
import androidx.compose.material3.Tab
import androidx.compose.material3.TabRow
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.produceState
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.semantics.Role
import androidx.compose.ui.semantics.clearAndSetSemantics
import androidx.compose.ui.semantics.contentDescription
import androidx.compose.ui.semantics.onClick
import androidx.compose.ui.semantics.role
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import com.agoria.app.data.AgentEntity
import com.agoria.app.data.BuildEntity
import com.agoria.app.data.ProjectEntity
import com.agoria.app.data.TaskEntity
import com.agoria.app.ui.AgoriaViewModel
import com.agoria.app.ui.components.EmptyState
import com.agoria.app.ui.components.Pill
import com.agoria.app.ui.components.PixelScaffold
import com.agoria.app.ui.components.StatusPill
import com.agoria.app.ui.office.world.Pal
import com.agoria.app.util.Files
import com.agoria.app.util.Time
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext
import java.io.File

/**
 * One shared project folder for the whole office: what is being worked on, what was built and the
 * files that exist. Only the lead agent writes into it, so there is a single place to check
 * progress instead of a build tab on every agent.
 */
@OptIn(ExperimentalMaterial3Api::class)
@Composable
fun ProjectScreen(
    viewModel: AgoriaViewModel,
    onOpenAgent: (String) -> Unit,
    onOpenBrowsers: () -> Unit,
    onBack: () -> Unit,
) {
    val context = LocalContext.current
    val projects by viewModel.projects.collectAsStateWithLifecycle()
    val agents by viewModel.agents.collectAsStateWithLifecycle()

    LaunchedEffect(Unit) {
        if (projects.isEmpty()) viewModel.ensureProject()
    }

    val project = projects.firstOrNull()
    var tab by remember { mutableStateOf(0) }
    var showLead by remember { mutableStateOf(false) }
    var showRename by remember { mutableStateOf(false) }

    PixelScaffold(
        title = project?.name ?: "Project",
        subtitle = project?.let { p ->
            val lead = agents.firstOrNull { it.id == p.leadAgentId }
            "Lead: ${lead?.name ?: "not set"}"
        } ?: "Shared folder for every build",
        onBack = onBack,
    ) { padding ->
        if (project == null) {
            Column(Modifier.fillMaxSize().padding(padding)) {
                EmptyState(
                    title = "No project yet",
                    body = "The office project is created automatically. Pull to refresh or open this tab again.",
                )
            }
            return@PixelScaffold
        }

        Column(Modifier.fillMaxSize().padding(padding)) {
            Row(
                modifier = Modifier
                    .fillMaxWidth()
                    .padding(horizontal = 16.dp, vertical = 6.dp),
                verticalAlignment = Alignment.CenterVertically,
            ) {
                Text(
                    text = project.description.ifBlank { "Everything the office is building." },
                    style = MaterialTheme.typography.bodySmall,
                    color = Pal.tan,
                    modifier = Modifier.weight(1f),
                )
                TextButton(onClick = { showLead = true }) { Text("Lead") }
                TextButton(onClick = { showRename = true }) { Text("Edit") }
            }

            TabRow(selectedTabIndex = tab) {
                Tab(selected = tab == 0, onClick = { tab = 0 }, text = { Text("Progress") })
                Tab(selected = tab == 1, onClick = { tab = 1 }, text = { Text("Builds") })
                Tab(selected = tab == 2, onClick = { tab = 2 }, text = { Text("Files") })
            }

            when (tab) {
                0 -> ProgressTab(viewModel, project, agents, onOpenAgent)
                1 -> BuildsTab(
                    viewModel = viewModel,
                    project = project,
                    agents = agents,
                    onView = { build ->
                        context.startActivity(
                            com.agoria.app.viewer.BuildViewerActivity.intent(
                                context,
                                build.zipPath,
                                build.name,
                            ),
                        )
                    },
                )
                else -> FilesTab(
                    project = project,
                    onView = { file ->
                        context.startActivity(
                            com.agoria.app.viewer.BuildViewerActivity.fileIntent(
                                context,
                                file.absolutePath,
                                Files.projectWorkspace(context, project.folder)
                                    .let { root -> Files.relativePath(root, file) },
                            ),
                        )
                    },
                )
            }
        }
    }

    if (showLead) {
        AlertDialog(
            onDismissRequest = { showLead = false },
            title = { Text("Who leads ${project?.name ?: "this project"}?") },
            text = {
                Column(Modifier.height(320.dp).verticalScroll(rememberScrollState())) {
                    agents.forEach { agent ->
                        Row(
                            modifier = Modifier
                                .fillMaxWidth()
                                .padding(vertical = 4.dp),
                            verticalAlignment = Alignment.CenterVertically,
                        ) {
                            Text(agent.name, modifier = Modifier.weight(1f))
                            if (agent.id == project?.leadAgentId) {
                                Pill(text = "Lead")
                            } else {
                                TextButton(onClick = {
                                    project?.let { viewModel.setProjectLead(it.id, agent.id) }
                                    showLead = false
                                }) { Text("Make lead") }
                            }
                        }
                    }
                }
            },
            confirmButton = { TextButton(onClick = { showLead = false }) { Text("Close") } },
        )
    }

    if (showRename && project != null) {
        var name by remember { mutableStateOf(project.name) }
        var description by remember { mutableStateOf(project.description) }
        AlertDialog(
            onDismissRequest = { showRename = false },
            title = { Text("Project details") },
            text = {
                Column(verticalArrangement = Arrangement.spacedBy(8.dp)) {
                    OutlinedTextField(
                        value = name,
                        onValueChange = { name = it },
                        label = { Text("Name") },
                        singleLine = true,
                        modifier = Modifier.fillMaxWidth(),
                    )
                    OutlinedTextField(
                        value = description,
                        onValueChange = { description = it },
                        label = { Text("Description") },
                        minLines = 2,
                        maxLines = 4,
                        modifier = Modifier.fillMaxWidth(),
                    )
                }
            },
            confirmButton = {
                TextButton(onClick = {
                    viewModel.renameProject(project.id, name, description)
                    showRename = false
                }) { Text("Save") }
            },
            dismissButton = { TextButton(onClick = { showRename = false }) { Text("Cancel") } },
        )
    }
}

@Composable
private fun ProgressTab(
    viewModel: AgoriaViewModel,
    project: ProjectEntity,
    agents: List<AgentEntity>,
    onOpenAgent: (String) -> Unit,
) {
    val tasks by viewModel.tasksForProject(project.id)
        .collectAsStateWithLifecycle(initialValue = emptyList<TaskEntity>())

    if (tasks.isEmpty()) {
        EmptyState(
            title = "No tasks yet",
            body = "Agents add tasks here when they plan work. Every agent's progress lands in this one list.",
        )
        return
    }

    LazyColumn(
        modifier = Modifier.fillMaxSize(),
        contentPadding = PaddingValues(16.dp),
        verticalArrangement = Arrangement.spacedBy(8.dp),
    ) {
        items(tasks, key = { it.id }) { task ->
            val agent = agents.firstOrNull { it.id == task.agentId }
            val agentName = agent?.name ?: "Agent"
            val open = { onOpenAgent(task.agentId) }
            val description = buildString {
                append(task.title).append(", ")
                append(agentName).append(", ")
                append("status ").append(task.status).append(", ")
                append(task.progress).append(" percent complete, ")
                append(Time.relativeTime(task.updatedAt))
            }
            Surface(
                modifier = Modifier
                    .fillMaxWidth()
                    .clickable(onClick = open)
                    .clearAndSetSemantics {
                        contentDescription = description
                        role = Role.Button
                        onClick(label = "Open agent $agentName") { open(); true }
                    },
                shape = MaterialTheme.shapes.medium,
                color = MaterialTheme.colorScheme.surfaceVariant,
            ) {
                Column(Modifier.padding(12.dp)) {
                    Row(verticalAlignment = Alignment.CenterVertically) {
                        Text(
                            text = task.title,
                            style = MaterialTheme.typography.bodyLarge,
                            modifier = Modifier.weight(1f),
                            maxLines = 2,
                            overflow = TextOverflow.Ellipsis,
                        )
                        Pill(text = task.status)
                    }
                    Text(
                        text = agentName,
                        style = MaterialTheme.typography.labelSmall,
                        color = Pal.tan,
                    )
                    if (task.description.isNotBlank()) {
                        Spacer(Modifier.height(4.dp))
                        Text(
                            text = task.description,
                            style = MaterialTheme.typography.bodySmall,
                            maxLines = 2,
                            overflow = TextOverflow.Ellipsis,
                        )
                    }
                    Spacer(Modifier.height(8.dp))
                    LinearProgressIndicator(
                        progress = { task.progress.coerceIn(0, 100) / 100f },
                        modifier = Modifier.fillMaxWidth(),
                    )
                    Text(
                        text = "${task.progress}% · ${Time.relativeTime(task.updatedAt)}",
                        style = MaterialTheme.typography.labelSmall,
                        color = Pal.wood,
                    )
                }
            }
        }
    }
}

@Composable
private fun BuildsTab(
    viewModel: AgoriaViewModel,
    project: ProjectEntity,
    agents: List<AgentEntity>,
    onView: (BuildEntity) -> Unit,
) {
    val builds by viewModel.buildsForProject(project.id)
        .collectAsStateWithLifecycle(initialValue = emptyList<BuildEntity>())
    val context = LocalContext.current
    var saving by remember { mutableStateOf<String?>(null) }

    if (builds.isEmpty()) {
        EmptyState(
            title = "No builds yet",
            body = "A build appears here after the lead agent proposes one and you approve it. All agents share this list.",
        )
        return
    }

    LazyColumn(
        modifier = Modifier.fillMaxSize(),
        contentPadding = PaddingValues(16.dp),
        verticalArrangement = Arrangement.spacedBy(8.dp),
    ) {
        items(builds, key = { it.id }) { build ->
            val agent = agents.firstOrNull { it.id == build.agentId }
            val agentName = agent?.name ?: "Agent"
            val description = buildString {
                append(build.name).append(", ")
                append(agentName).append(", ")
                append(build.fileCount).append(" files, ")
                append(Time.relativeTime(build.createdAt))
            }
            Surface(
                modifier = Modifier
                    .fillMaxWidth()
                    .clearAndSetSemantics {
                        contentDescription = description
                        role = Role.Button
                    },
                shape = MaterialTheme.shapes.medium,
                color = MaterialTheme.colorScheme.surfaceVariant,
            ) {
                Column(Modifier.padding(12.dp)) {
                    Row(verticalAlignment = Alignment.CenterVertically) {
                        Text(
                            text = build.name,
                            style = MaterialTheme.typography.titleSmall,
                            modifier = Modifier.weight(1f),
                        )
                        Pill(text = "${build.fileCount} files")
                    }
                    Text(
                        text = "$agentName · ${Time.relativeTime(build.createdAt)}",
                        style = MaterialTheme.typography.labelSmall,
                        color = Pal.tan,
                    )
                    if (build.description.isNotBlank()) {
                        Text(
                            text = build.description,
                            style = MaterialTheme.typography.bodySmall,
                            maxLines = 2,
                            overflow = TextOverflow.Ellipsis,
                        )
                    }
                    Spacer(Modifier.height(6.dp))
                    Row(horizontalArrangement = Arrangement.spacedBy(6.dp)) {
                        FilledTonalButton(onClick = { onView(build) }) { Text("View") }
                        TextButton(
                            onClick = {
                                saving = build.id
                                viewModel.saveBuildToDownloads(context, build) { saving = null }
                            },
                            enabled = saving != build.id,
                        ) { Text(if (saving == build.id) "Saving" else "Save ZIP") }
                    }
                }
            }
        }
    }
}

@Composable
private fun FilesTab(project: ProjectEntity, onView: (File) -> Unit) {
    val context = LocalContext.current
    val files by produceState<List<File>>(initialValue = emptyList(), project.id) {
        value = withContext(Dispatchers.IO) {
            Files.projectFiles(Files.projectWorkspace(context, project.folder))
        }
    }
    val root = remember(project.id) { Files.projectWorkspace(context, project.folder) }

    if (files.isEmpty()) {
        EmptyState(
            title = "No files yet",
            body = "Approved builds write their files into this shared folder, so you can read them here without downloading a ZIP.",
        )
        return
    }

    LazyColumn(
        modifier = Modifier.fillMaxSize(),
        contentPadding = PaddingValues(16.dp),
        verticalArrangement = Arrangement.spacedBy(2.dp),
    ) {
        items(files, key = { it.absolutePath }) { file ->
            val relPath = Files.relativePath(root, file)
            val size = Files.prettySize(file.length())
            val description = "$relPath, $size"
            Row(
                modifier = Modifier
                    .fillMaxWidth()
                    .padding(vertical = 6.dp)
                    .clearAndSetSemantics {
                        contentDescription = description
                        role = Role.Button
                    },
                verticalAlignment = Alignment.CenterVertically,
            ) {
                Text(
                    text = relPath,
                    style = MaterialTheme.typography.bodyMedium,
                    modifier = Modifier.weight(1f),
                    maxLines = 1,
                    overflow = TextOverflow.Ellipsis,
                )
                Text(
                    text = size,
                    style = MaterialTheme.typography.labelSmall,
                    color = Pal.wood,
                )
                Spacer(Modifier.width(8.dp))
                TextButton(onClick = { onView(file) }) { Text("View") }
            }
        }
    }
}