package com.agoria.app.service

import android.app.Notification
import android.app.PendingIntent
import android.app.Service
import android.content.Context
import android.content.Intent
import android.content.pm.ServiceInfo
import android.os.Build
import android.os.IBinder
import androidx.core.app.NotificationCompat
import androidx.core.app.ServiceCompat
import androidx.core.content.ContextCompat
import com.agoria.app.AgoriaApplication
import com.agoria.app.MainActivity
import com.agoria.app.R
import com.agoria.app.util.Notifier

class AutonomyService : Service() {

    override fun onCreate() {
        super.onCreate()
        Notifier.ensureChannels(this)
        startForegroundCompat()
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
        (application as AgoriaApplication).runtime.stop()
        super.onDestroy()
    }

    override fun onBind(intent: Intent?): IBinder? = null

    private fun stopAutonomy() {
        (application as AgoriaApplication).runtime.stop()
        stopSelf()
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

        fun start(context: Context) {
            val intent = Intent(context, AutonomyService::class.java)
            try {
                ContextCompat.startForegroundService(context, intent)
            } catch (_: Exception) {
                // The app is not allowed to start a foreground service in this state.
                // The next foreground launch will start it again.
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