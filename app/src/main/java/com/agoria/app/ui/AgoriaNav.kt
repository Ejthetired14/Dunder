package com.agoria.app.ui

import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.padding
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.Business
import androidx.compose.material.icons.filled.FactCheck
import androidx.compose.material.icons.filled.Home
import androidx.compose.material.icons.filled.Hub
import androidx.compose.material.icons.filled.Folder
import androidx.compose.material3.Badge
import androidx.compose.material3.BadgedBox
import androidx.compose.material3.Icon
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.NavigationBar
import androidx.compose.material3.NavigationBarItem
import androidx.compose.material3.Scaffold
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.mutableIntStateOf
import androidx.compose.runtime.setValue
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.vector.ImageVector
import androidx.compose.ui.input.pointer.PointerEventPass
import androidx.compose.ui.input.pointer.pointerInput
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import kotlinx.coroutines.delay

import androidx.navigation.compose.NavHost
import androidx.navigation.compose.composable
import androidx.navigation.compose.currentBackStackEntryAsState
import androidx.navigation.compose.rememberNavController
import com.agoria.app.ui.agent.AgentScreen
import com.agoria.app.ui.approvals.ApprovalsScreen
import com.agoria.app.ui.connectors.ConnectorsScreen
import com.agoria.app.ui.browser.BrowsersScreen
import com.agoria.app.ui.diary.DiaryScreen
import com.agoria.app.ui.home.HomeScreen
import com.agoria.app.ui.office.OfficeScreen
import com.agoria.app.ui.project.ProjectScreen
import com.agoria.app.ui.settings.SettingsScreen

private data class TopDestination(
    val route: String,
    val label: String,
    val icon: ImageVector,
)

private val topDestinations = listOf(
    TopDestination("home", "Home", Icons.Default.Home),
    TopDestination("office", "Office", Icons.Default.Business),
    TopDestination("project", "Project", Icons.Default.Folder),
    TopDestination("approvals", "Approvals", Icons.Default.FactCheck),
    TopDestination("connectors", "Connect", Icons.Default.Hub),
)

@Composable
fun AgoriaNav(viewModel: AgoriaViewModel) {
    val navController = rememberNavController()
    val pendingCount by viewModel.pendingCount.collectAsStateWithLifecycle()
    val backStackEntry by navController.currentBackStackEntryAsState()
    val currentRoute = backStackEntry?.destination?.route
    val isTopDestination = topDestinations.any { it.route == currentRoute }

    // Auto-hide bottom bar. A counter bumped from the pointer listener restarts this
    // countdown, so each touch gets the full idle time. Holding the Job in a plain
    // local var lost it on recomposition, and a detector that ran after the screen
    // content had consumed the touch could never bring the bar back.
    val bottomBarVisible = remember { mutableStateOf(true) }
    var activityTick by remember { mutableIntStateOf(0) }
    val autoHideDelay = 3000L // 3 seconds

    LaunchedEffect(activityTick, currentRoute) {
        if (!isTopDestination) return@LaunchedEffect
        bottomBarVisible.value = true
        delay(autoHideDelay)
        bottomBarVisible.value = false
    }

    // Initial pass: observe every press before maps, lists and buttons consume it.
    val touchDetector = Modifier
        .fillMaxSize()
        .pointerInput(Unit) {
            awaitPointerEventScope {
                var wasPressed = false
                while (true) {
                    val event = awaitPointerEvent(PointerEventPass.Initial)
                    val pressed = event.changes.any { it.pressed }
                    if (pressed && !wasPressed) activityTick++
                    wasPressed = pressed
                }
            }
        }

    Scaffold(
        bottomBar = {
            if (isTopDestination && bottomBarVisible.value) {
                AgoriaBottomBar(
                    currentRoute = currentRoute,
                    pendingCount = pendingCount,
                    onNavigate = { route ->
                        if (currentRoute != route) {
                            navController.navigate(route) {
                                // Pop back to start destination (home) to avoid stacking duplicate tabs
                                // saveState = true preserves each tab's ViewModel and scroll position
                                popUpTo(navController.graph.startDestinationId) {
                                    saveState = true
                                }
                                launchSingleTop = true
                                restoreState = true
                            }
                        }
                    },
                    // The touch detector lives on the content Box. Passing it here gave the
                    // NavigationBar a fillMaxSize modifier, which stretched the bar over the
                    // whole screen and hid every screen behind it.
                    modifier = Modifier,
                )
            }
        },
    ) { innerPadding ->
        Box(modifier = touchDetector) {
            NavHost(
                navController = navController,
                startDestination = "home",
                modifier = Modifier.padding(innerPadding),
            ) {
                composable("home") {
                    HomeScreen(
                        viewModel = viewModel,
                        onOpenAgent = { id -> navController.navigate("agent/$id") },
                        onOpenSettings = { navController.navigate("settings") },
                        onOpenApprovals = { navController.navigate("approvals") },
                        onOpenDiary = { navController.navigate("diary") },
                        onOpenOffice = { navController.navigate("office") },
                    )
                }
                composable("office") {
                    OfficeScreen(
                        viewModel = viewModel,
                        navController = navController,
                        onOpenAgent = { id -> navController.navigate("agent/$id") },
                        onOpenBrowsers = { navController.navigate("browsers") },
                        onOpenApprovals = { navController.navigate("approvals") },
                    )
                }
            composable("project") {
                ProjectScreen(
                    viewModel = viewModel,
                    onOpenAgent = { id -> navController.navigate("agent/$id") },
                    onOpenBrowsers = { navController.navigate("browsers") },
                )
            }
            composable("browsers") {
                BrowsersScreen(
                    viewModel = viewModel,
                    onBack = { navController.popBackStack() },
                )
            }
            composable("diary") {
                DiaryScreen(
                    viewModel = viewModel,
                    onOpenAgent = { id -> navController.navigate("agent/$id") },
                )
            }
            composable("approvals") {
                ApprovalsScreen(
                    viewModel = viewModel,
                    onOpenConnectors = { navController.navigate("connectors") },
                )
            }
            composable("connectors") {
                ConnectorsScreen(viewModel = viewModel)
            }
            composable("settings") {
                SettingsScreen(
                    viewModel = viewModel,
                    onBack = { navController.popBackStack() },
                )
            }
            composable("agent/{agentId}") { entry ->
                val agentId = entry.arguments?.getString("agentId") ?: return@composable
                AgentScreen(
                    viewModel = viewModel,
                    agentId = agentId,
                    onBack = { navController.popBackStack() },
                )
            }
        }
    }
}
}

@Composable
private fun AgoriaBottomBar(
    currentRoute: String?,
    pendingCount: Int,
    onNavigate: (String) -> Unit,
    modifier: Modifier = Modifier,
) {
    NavigationBar(containerColor = MaterialTheme.colorScheme.surface, modifier = modifier) {
        topDestinations.forEach { destination ->
            NavigationBarItem(
                selected = currentRoute == destination.route,
                onClick = { onNavigate(destination.route) },
                icon = {
                    if (destination.route == "approvals" && pendingCount > 0) {
                        BadgedBox(
                            badge = {
                                Badge { Text(pendingCount.toString()) }
                            },
                        ) {
                            Icon(destination.icon, contentDescription = destination.label)
                        }
                    } else {
                        Icon(destination.icon, contentDescription = destination.label)
                    }
                },
                label = { Text(destination.label) },
            )
        }
    }
}
