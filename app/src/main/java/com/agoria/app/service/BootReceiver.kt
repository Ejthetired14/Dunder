package com.agoria.app.service

import android.content.BroadcastReceiver
import android.content.Context
import android.content.Intent
import com.agoria.app.AgoriaApplication
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.launch

/**
 * Restarts the autonomy loop after a reboot, but only if the user left autonomy enabled.
 *
 * SEC-13: the manifest entry is `exported="false"` and listens for BOOT_COMPLETED only.
 * LOCKED_BOOT_COMPLETED is not handled here: this receiver reads Room (credential-encrypted
 * storage), which does not exist before the first unlock.
 */
class BootReceiver : BroadcastReceiver() {

    override fun onReceive(context: Context, intent: Intent) {
        if (intent.action != Intent.ACTION_BOOT_COMPLETED) return
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
                // A failed boot start is retried the next time the app opens (see RUN-5).
            } finally {
                pending.finish()
            }
        }
    }
}
