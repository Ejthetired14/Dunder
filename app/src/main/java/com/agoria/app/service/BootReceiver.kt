package com.agoria.app.service

import android.content.BroadcastReceiver
import android.content.Context
import android.content.Intent
import android.util.Log
import com.agoria.app.AgoriaApplication
import com.agoria.app.util.Notifier
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.TimeoutCancellationException
import kotlinx.coroutines.launch
import kotlinx.coroutines.withTimeout

/**
 * Restarts the autonomy loop after a reboot, but only if the user left autonomy enabled.
 *
 * SEC-13: the manifest entry is `exported="false"` and listens for BOOT_COMPLETED only.
 * LOCKED_BOOT_COMPLETED is not handled here: this receiver reads Room (credential-encrypted
 * storage), which does not exist before the first unlock.
 *
 * RUN-5: no seeding here (the app seeds on first launch), the settings read is read-only and
 * bounded by [SETTINGS_READ_TIMEOUT_MS] (a receiver has about 10 s), and every failure path is
 * visible: it is logged, stored in [BootStatus] and shown as a notification.
 */
class BootReceiver : BroadcastReceiver() {

    override fun onReceive(context: Context, intent: Intent) {
        if (intent.action != Intent.ACTION_BOOT_COMPLETED) return
        val appContext = context.applicationContext
        val app = appContext as? AgoriaApplication ?: return
        val pending = goAsync()
        CoroutineScope(Dispatchers.IO).launch {
            try {
                val enabled = withTimeout(SETTINGS_READ_TIMEOUT_MS) {
                    app.database.settingsDao().get()?.autonomyEnabled == true
                }
                if (!enabled) return@launch
                if (AutonomyService.start(appContext)) {
                    BootStatus.clear(appContext)
                } else {
                    reportFailure(appContext, "Android refused to restart autonomy after the reboot.")
                }
            } catch (_: TimeoutCancellationException) {
                reportFailure(appContext, "Settings did not load in time after the reboot.")
            } catch (e: Exception) {
                reportFailure(appContext, "Autonomy restart failed after the reboot (${e.javaClass.simpleName}).")
            } finally {
                pending.finish()
            }
        }
    }

    private fun reportFailure(context: Context, reason: String) {
        Log.w(TAG, reason)
        BootStatus.recordFailure(context, reason)
        runCatching {
            Notifier.notifyLocal(context, "Autonomy did not restart", "$reason Open the app to turn it back on.")
        }
    }

    private companion object {
        const val TAG = "BootReceiver"
        const val SETTINGS_READ_TIMEOUT_MS = 8_000L
    }
}
