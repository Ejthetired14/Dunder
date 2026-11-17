package com.agoria.app

import android.os.Bundle
import androidx.activity.ComponentActivity
import androidx.activity.compose.setContent
import androidx.core.splashscreen.SplashScreen.Companion.installSplashScreen
import com.agoria.app.ui.AgoriaRoot
import com.agoria.app.ui.theme.AgoriaTheme

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
}