package com.agoria.app.ui

import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.material3.CircularProgressIndicator
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Surface
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import androidx.lifecycle.viewmodel.compose.viewModel

@Composable
fun AgoriaRoot() {
    val viewModel: AgoriaViewModel = viewModel()
    val settings = viewModel.settings.collectAsStateWithLifecycle().value

    LaunchedEffect(Unit) {
        viewModel.syncAutonomy()
    }

    Surface(
        modifier = Modifier.fillMaxSize(),
        color = MaterialTheme.colorScheme.background,
    ) {
        when {
            settings == null -> {
                Box(Modifier.fillMaxSize(), contentAlignment = Alignment.Center) {
                    CircularProgressIndicator(color = MaterialTheme.colorScheme.primary)
                }
            }
            !settings.setupComplete -> {
                OnboardingScreen(
                    viewModel = viewModel,
                    onBack = { viewModel.finishSetup("Boss") },
                )
            }
            else -> {
                androidx.compose.foundation.layout.Box(Modifier.fillMaxSize()) {
                    AgoriaNav(viewModel = viewModel)
                    com.agoria.app.ui.browser.BrowserOverlay()
                }
            }
        }
    }
}