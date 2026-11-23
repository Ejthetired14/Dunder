package com.agoria.app.ui

import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.navigationBarsPadding
import androidx.compose.foundation.layout.padding
import androidx.compose.material3.Scaffold
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.unit.dp
import androidx.navigation.compose.NavHost
import androidx.navigation.compose.composable
import androidx.navigation.compose.currentBackStackEntryAsState
import androidx.navigation.compose.rememberNavController
import com.agoria.app.ui.agent.AgentScreen
import com.agoria.app.ui.approvals.ApprovalsScreen
import com.agoria.app.ui.browser.BrowsersScreen
import com.agoria.app.ui.connectors.ConnectorsScreen
import com.agoria.app.ui.diary.DiaryScreen
import com.agoria.app.ui.home.HomeScreen
import com.agoria.app.ui.office.OfficeScreen
import com.agoria.app.ui.office.world.PixelButton
import com.agoria.app.ui.project.ProjectScreen
import com.agoria.app.ui.settings.SettingsScreen

/**
 * Routes whose screen already uses PixelScaffold (task T-BACK). They draw their own BACK TO OFFICE
 * top bar, so the floating "< Office" button is hidden on them. When you convert another screen,
 * add its route here. Once every route is listed, delete the floating button and this set.
 */
private val ScaffoldedRoutes = setOf("diary", "browsers", "home", "project", "connectors", "approvals", "agent")

/**
 * The office is the only top-level view. There is no bottom bar: every other page is opened from
 * inside the office (rooms, your desk, the notice board directory) and system Back returns to it.
 * Screens not yet on PixelScaffold (see claudefrontend.md, T-BACK2) still get the floating
 * "< Office" button as the visible way back.
 */
@Composable
fun AgoriaNav(viewModel: AgoriaViewModel) {
    val navController = rememberNavController()
    val backStackEntry by navController.currentBackStackEntryAsState()
    val currentRoute = backStackEntry?.destination?.route

    Scaffold { innerPadding ->
        Box(modifier = Modifier.fillMaxSize()) {
            NavHost(
                navController = navController,
                startDestination = "office",
                modifier = Modifier.padding(innerPadding),
            ) {
                composable("office") {
                    OfficeScreen(
                        viewModel = viewModel,
                        navController = navController,
                        onOpenAgent = { id -> navController.navigate("agent/$id") },
                        onOpenBrowsers = { navController.navigate("browsers") },
                        onOpenApprovals = { navController.navigate("approvals") },
                    )
                }
                composable("home") {
                    HomeScreen(
                        viewModel = viewModel,
                        onOpenAgent = { id -> navController.navigate("agent/$id") },
                        onOpenSettings = { navController.navigate("settings") },
                        onOpenApprovals = { navController.navigate("approvals") },
                        onOpenDiary = { navController.navigate("diary") },
                        // Back to the one office, never a second copy stacked on top.
                        onOpenOffice = { navController.popBackStack("office", false) },
                        onBack = { navController.popBackStack("office", false) },
                    )
                }
                composable("project") {
                    ProjectScreen(
                        viewModel = viewModel,
                        onOpenAgent = { id -> navController.navigate("agent/$id") },
                        onOpenBrowsers = { navController.navigate("browsers") },
                        onBack = { navController.popBackStack("office", false) },
                    )
                }
                composable("browsers") {
                    BrowsersScreen(
                        viewModel = viewModel,
                        onBack = { navController.popBackStack("office", false) },
                    )
                }
                composable("diary") {
                    DiaryScreen(
                        viewModel = viewModel,
                        onOpenAgent = { id -> navController.navigate("agent/$id") },
                        onBack = { navController.popBackStack("office", false) },
                    )
                }
                composable("approvals") {
                    ApprovalsScreen(
                        viewModel = viewModel,
                        onOpenConnectors = { navController.navigate("connectors") },
                        onBack = { navController.popBackStack("office", false) },
                    )
                }
                composable("connectors") {
                    ConnectorsScreen(
                        viewModel = viewModel,
                        onBack = { navController.popBackStack("office", false) },
                    )
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
                        onBack = { navController.popBackStack("office", false) },
                    )
                }
            }

            if (currentRoute != null && currentRoute != "office" && currentRoute !in ScaffoldedRoutes) {
                PixelButton(
                    text = "< Office",
                    onClick = { navController.popBackStack("office", false) },
                    modifier = Modifier
                        .align(Alignment.BottomStart)
                        .navigationBarsPadding()
                        .padding(12.dp),
                )
            }
        }
    }
}
