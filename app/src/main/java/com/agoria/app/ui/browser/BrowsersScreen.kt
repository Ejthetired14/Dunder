package com.agoria.app.ui.browser

import android.annotation.SuppressLint
import android.webkit.CookieManager
import android.webkit.WebView
import android.webkit.WebViewClient
import androidx.activity.compose.BackHandler
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.PaddingValues
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.items
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.automirrored.filled.ArrowBack
import androidx.compose.material.icons.filled.Close
import androidx.compose.material.icons.filled.MoreVert
import androidx.compose.material.icons.filled.OpenInBrowser
import androidx.compose.material.icons.filled.Refresh
import androidx.compose.material.icons.filled.Settings
import androidx.compose.material3.ExperimentalMaterial3Api
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Scaffold
import androidx.compose.material3.Surface
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.material3.TopAppBar
import androidx.compose.material3.DropdownMenu
import androidx.compose.material3.DropdownMenuItem
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import androidx.compose.ui.viewinterop.AndroidView
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import com.agoria.app.browser.BrowserHub
import com.agoria.app.ui.AgoriaViewModel
import com.agoria.app.ui.components.AgentAvatar
import com.agoria.app.ui.components.EmptyState
import com.agoria.app.util.Time

/**
 * The visible window onto an agent's browser. The browser itself keeps running in the background;
 * this only shows the page the agent is already on, in the same office cookie session.
 */
@Composable
fun BrowserOverlay() {
    val viewing by BrowserHub.viewing.collectAsStateWithLifecycle()
    val sessions by BrowserHub.sessions.collectAsStateWithLifecycle()
    val session = sessions.firstOrNull { it.agentId == viewing } ?: return

    BackHandler { BrowserHub.stopViewing() }

    Surface(modifier = Modifier.fillMaxSize(), color = MaterialTheme.colorScheme.background) {
        Column(Modifier.fillMaxSize()) {
            Surface(color = MaterialTheme.colorScheme.surfaceVariant) {
                Row(
                    modifier = Modifier
                        .fillMaxWidth()
                        .padding(start = 14.dp, top = 8.dp, bottom = 8.dp, end = 4.dp),
                    verticalAlignment = Alignment.CenterVertically,
                ) {
                    Column(Modifier.weight(1f)) {
                        Text(
                            text = "${session.agentName.ifBlank { "Agent" }}'s browser",
                            style = MaterialTheme.typography.titleSmall,
                        )
                        Text(
                            text = session.url.ifBlank { "about:blank" },
                            style = MaterialTheme.typography.labelSmall,
                            color = MaterialTheme.colorScheme.onSurfaceVariant,
                            maxLines = 1,
                            overflow = TextOverflow.Ellipsis,
                        )
                    }
                    IconButton(onClick = { BrowserHub.reload(session.agentId) }) {
                        Icon(Icons.Default.Refresh, contentDescription = "Reload")
                    }
                    IconButton(onClick = { BrowserHub.stopViewing() }) {
                        Icon(Icons.Default.Close, contentDescription = "Close")
                    }
                }
            }
            LiveWebPage(url = session.url)
        }
    }
}

@SuppressLint("SetJavaScriptEnabled")
@Composable
private fun LiveWebPage(url: String) {
    val startUrl = remember { url.ifBlank { "about:blank" } }
    AndroidView(
        modifier = Modifier.fillMaxSize(),
        factory = { context ->
            WebView(context).apply {
                settings.javaScriptEnabled = true
                settings.domStorageEnabled = true
                settings.useWideViewPort = true
                settings.loadWithOverviewMode = true
                settings.userAgentString = settings.userAgentString.replace("; wv", "")
                CookieManager.getInstance().setAcceptCookie(true)
                CookieManager.getInstance().setAcceptThirdPartyCookies(this, true)
                webViewClient = WebViewClient()
                loadUrl(startUrl)
            }
        },
    )
}

@OptIn(ExperimentalMaterial3Api::class)
@Composable
fun BrowsersScreen(
    viewModel: AgoriaViewModel,
    onBack: () -> Unit,
) {
    val agents by viewModel.agents.collectAsStateWithLifecycle()
    val sessions by BrowserHub.sessions.collectAsStateWithLifecycle()
    var expanded by remember { mutableStateOf(false) }

    Scaffold(
        topBar = {
            TopAppBar(
                title = {
                    Column {
                        Text("Agent browsers")
                        Text(
                            text = "Every agent browses in its own window, in the background",
                            style = MaterialTheme.typography.labelSmall,
                            color = MaterialTheme.colorScheme.onSurfaceVariant,
                        )
                    }
                },
                navigationIcon = {
                    IconButton(onClick = onBack) {
                        Icon(Icons.AutoMirrored.Filled.ArrowBack, contentDescription = "Back")
                    }
                },
                actions = {
                    IconButton(onClick = { expanded = !expanded }) {
                        Icon(Icons.Default.MoreVert, contentDescription = "More options")
                    }
                    DropdownMenu(
                        expanded = expanded,
                        onDismissRequest = { expanded = false },
                    ) {
                        DropdownMenuItem(onClick = { expanded = false; BrowserHub.reloadAll() }) {
                            Row(Modifier.fillMaxWidth().padding(16.dp), verticalAlignment = Alignment.CenterVertically) {
                                Icon(Icons.Default.Refresh, contentDescription = null, modifier = Modifier.size(24.dp).padding(end = 12.dp))
                                Text("Refresh all")
                            }
                        }
                        DropdownMenuItem(onClick = { expanded = false }) {
                            Row(Modifier.fillMaxWidth().padding(16.dp), verticalAlignment = Alignment.CenterVertically) {
                                Icon(Icons.Default.Settings, contentDescription = null, modifier = Modifier.size(24.dp).padding(end = 12.dp))
                                Text("Settings")
                            }
                        }
                    }
                },
            )
        },
    ) { padding ->
        if (agents.isEmpty()) {
            Column(Modifier.fillMaxSize().padding(padding)) {
                EmptyState(
                    title = "No agents yet",
                    body = "Create an agent and it gets its own browser automatically.",
                )
            }
        } else {
            LazyColumn(
                modifier = Modifier.fillMaxSize().padding(padding),
                contentPadding = PaddingValues(vertical = 8.dp),
            ) {
                items(agents, key = { it.id }) { agent ->
                    val session = sessions.firstOrNull { it.agentId == agent.id }
                    Row(
                        modifier = Modifier
                            .fillMaxWidth()
                            .padding(horizontal = 16.dp, vertical = 8.dp),
                        verticalAlignment = Alignment.CenterVertically,
                    ) {
                        AgentAvatar(agent, 36.dp, showStatus = false)
                        Spacer(Modifier.width(12.dp))
                        Column(Modifier.weight(1f)) {
                            Text(agent.name, style = MaterialTheme.typography.bodyLarge)
                            Text(
                                text = when {
                                    session == null -> "No page loaded yet"
                                    session.loading -> "Loading ${session.url}"
                                    session.error != null -> "Failed: ${session.error}"
                                    else -> session.title.ifBlank { session.url }
                                },
                                style = MaterialTheme.typography.labelSmall,
                                color = MaterialTheme.colorScheme.onSurfaceVariant,
                                maxLines = 2,
                                overflow = TextOverflow.Ellipsis,
                            )
                            if (session != null) {
                                Text(
                                    text = "${session.url.take(60)} · ${Time.relativeTime(session.updatedAt)}",
                                    style = MaterialTheme.typography.labelSmall,
                                    color = MaterialTheme.colorScheme.outline,
                                    maxLines = 1,
                                    overflow = TextOverflow.Ellipsis,
                                )
                            }
                        }
                        if (session != null && session.url.isNotBlank()) {
                            TextButton(onClick = { BrowserHub.show(agent.id) }) {
                                Icon(
                                    Icons.Default.OpenInBrowser,
                                    contentDescription = null,
                                    modifier = Modifier.padding(end = 4.dp),
                                )
                                Text("View")
                            }
                        }
                    }
                }
                item {
                    Text(
                        text = "Agents open pages here through the Office Browser connector. " +
                            "They load in the background, and the sign-in stays in the office browser " +
                            "instead of the phone's accounts.",
                        style = MaterialTheme.typography.labelSmall,
                        color = MaterialTheme.colorScheme.outline,
                        modifier = Modifier.padding(16.dp),
                    )
                }
            }
        }
    }
}
