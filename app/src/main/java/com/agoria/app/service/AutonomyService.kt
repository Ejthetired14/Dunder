package com.agoria.app.service

import android.app.Notification
import android.app.PendingIntent
import android.app.Service
import android.content.Context
import android.content.Intent
import android.content.pm.ServiceInfo
import android.os.Build
import android.os.IBinder
import android.os.PowerManager
import android.util.Log
import androidx.core.app.NotificationCompat
import androidx.core.app.ServiceCompat
import androidx.core.content.ContextCompat
import com.agoria.app.AgoriaApplication
import com.agoria.app.MainActivity
import com.agoria.app.R
import com.agoria.app.util.Notifier
import kotlinx.coroutines.launch

class AutonomyService : Service() {

    private var wakeLock: PowerManager.WakeLock? = null

    override fun onCreate() {
        super.onCreate()
        Notifier.ensureChannels(this)
        startForegroundCompat()
        acquireWakeLock()
        // RUN-5: the service is running, so any earlier "restart after reboot failed" note is stale.
        BootStatus.clear(this)
        // Start ticking from the service itself, so the office keeps working even when the
        // activity that asked for autonomy is long gone.
        (application as AgoriaApplication).runtime.start()
    }

    override fun onStartCommand(intent: Intent?, flags: Int, startId: Int): Int {
        if (intent?.action == ACTION_STOP) {
            stopAutonomy()
            return START_NOT_STICKY
        }
        startForegroundCompat()
        (application as AgoriaApplication).runtime.start()
        return START_STICKY
    }

    override fun onDestroy() {
        releaseWakeLock()
        (application as AgoriaApplication).runtime.stop()
        super.onDestroy()
    }

    private fun acquireWakeLock() {
        if (wakeLock != null) return
        wakeLock = try {
            val power = getSystemService(POWER_SERVICE) as? PowerManager
            power?.newWakeLock(PowerManager.PARTIAL_WAKE_LOCK, "agoria:autonomy")?.apply {
                setReferenceCounted(false)
                acquire()
            }
        } catch (e: Exception) {
            Log.w(TAG, "Could not hold the autonomy wake lock: ${e.message}")
            null
        }
    }

    private fun releaseWakeLock() {
        try {
            wakeLock?.takeIf { it.isHeld }?.release()
        } catch (_: Exception) {
            // The lock was already released by the system.
        }
        wakeLock = null
    }

    override fun onBind(intent: Intent?): IBinder? = null

    /**
     * RUN-3: every explicit stop (notification action, [stop]) also persists
     * `autonomyEnabled = false`, otherwise [BootReceiver] would bring the loop back after the next
     * reboot. The service stays alive until the write finishes, so the process is not reclaimed
     * mid-write. System kills go through [onDestroy] only and deliberately leave the flag alone.
     */
    private fun stopAutonomy() {
        val app = application as AgoriaApplication
        app.runtime.stop()
        app.appScope.launch {
            try {
                app.repository.updateSettings { it.copy(autonomyEnabled = false) }
            } catch (e: Exception) {
                Log.w(TAG, "Could not persist autonomyEnabled=false: ${e.message ?: e.javaClass.simpleName}")
            } finally {
                stopSelf()
            }
        }
    }

    private fun startForegroundCompat() {
        val openIntent = Intent(this, MainActivity::class.java).apply {
            flags = Intent.FLAG_ACTIVITY_NEW_TASK or Intent.FLAG_ACTIVITY_CLEAR_TOP
        }
        val openPending = PendingIntent.getActivity(
            this,
            0,
            openIntent,
            PendingIntent.FLAG_UPDATE_CURRENT or PendingIntent.FLAG_IMMUTABLE,
        )
        val stopIntent = Intent(this, AutonomyService::class.java).apply { action = ACTION_STOP }
        val stopPending = PendingIntent.getService(
            this,
            1,
            stopIntent,
            PendingIntent.FLAG_UPDATE_CURRENT or PendingIntent.FLAG_IMMUTABLE,
        )

        val notification: Notification = NotificationCompat.Builder(this, Notifier.CHANNEL_AUTONOMY)
            .setSmallIcon(R.drawable.ic_stat_agoria)
            .setContentTitle(getString(R.string.autonomy_notification_title))
            .setContentText(getString(R.string.autonomy_notification_text))
            .setOngoing(true)
            .setContentIntent(openPending)
            .addAction(0, "Stop autonomy", stopPending)
            .build()

        if (Build.VERSION.SDK_INT >= 34) {
            ServiceCompat.startForeground(
                this,
                NOTIFICATION_ID,
                notification,
                ServiceInfo.FOREGROUND_SERVICE_TYPE_SPECIAL_USE,
            )
        } else {
            startForeground(NOTIFICATION_ID, notification)
        }
    }

    companion object {
        const val ACTION_STOP = "com.agoria.app.action.STOP_AUTONOMY"
        private const val NOTIFICATION_ID = 7100
        private const val TAG = "AutonomyService"

        /**
         * Starts the autonomy service. Returns false only when Android refused both a foreground
         * and a plain service start, which happens when the app is in the background and the
         * system will not lift the restriction; the caller then keeps the in-process loop going.
         */
        fun start(context: Context): Boolean {
            val intent = Intent(context, AutonomyService::class.java)
            try {
                ContextCompat.startForegroundService(context, intent)
                return true
            } catch (e: Exception) {
                Log.w(TAG, "Foreground start refused: ${e.message ?: e.javaClass.simpleName}")
            }
            return try {
                context.startService(intent)
                true
            } catch (e: Exception) {
                Log.w(TAG, "Service start refused: ${e.message ?: e.javaClass.simpleName}")
                false
            }
        }

        fun stop(context: Context) {
            try {
                context.startService(
                    Intent(context, AutonomyService::class.java).apply { action = ACTION_STOP },
                )
            } catch (_: Exception) {
                // Nothing to stop.
            }
        }
    }
}
