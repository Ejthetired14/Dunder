package com.agoria.app.service

import android.content.BroadcastReceiver
import android.content.Context
import android.content.Intent
import com.agoria.app.AgoriaApplication
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.launch

class BootReceiver : BroadcastReceiver() {

    override fun onReceive(context: Context, intent: Intent) {
        val action = intent.action ?: return
        if (action != Intent.ACTION_BOOT_COMPLETED && action != Intent.ACTION_LOCKED_BOOT_COMPLETED) return
        val app = context.applicationContext as? AgoriaApplication ?: return
        val pending = goAsync()
        CoroutineScope(Dispatchers.IO).launch {
            try {
                app.repository.seedIfNeeded()
                val settings = app.repository.settings()
                if (settings.autonomyEnabled) {
                    AutonomyService.start(context)
                }
            } catch (_: Exception) {
                // A failed boot start is retried the next time the app opens.
            } finally {
                pending.finish()
            }
        }
    }
}