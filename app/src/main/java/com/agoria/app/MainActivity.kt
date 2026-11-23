package com.agoria.app

import android.os.Bundle
import androidx.activity.ComponentActivity
import androidx.activity.compose.setContent
import androidx.core.splashscreen.SplashScreen.Companion.installSplashScreen
import com.agoria.app.service.AutonomyService
import com.agoria.app.ui.AgoriaRoot
import com.agoria.app.ui.theme.AgoriaTheme
import kotlinx.coroutines.launch

class MainActivity : ComponentActivity() {

    override fun onCreate(savedInstanceState: Bundle?) {
        installSplashScreen()
        super.onCreate(savedInstanceState)
        setContent {
            AgoriaTheme {
                AgoriaRoot()
            }
        }
    }

    /**
     * Coming back to the app is the one moment Android is guaranteed to allow a foreground
     * service start, so re-arm autonomy here. Without this the office only ticks while this
     * activity is visible: as soon as another app is in front the process is frozen.
     */
    override fun onStart() {
        super.onStart()
        val app = application as AgoriaApplication
        app.appScope.launch {
            val enabled = runCatching { app.repository.settings().autonomyEnabled }
                .getOrDefault(false)
            if (enabled) {
                AutonomyService.start(app)
                app.runtime.start()
            }
        }
    }
}
