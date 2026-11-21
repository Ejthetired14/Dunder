package com.agoria.app.util

import android.app.NotificationChannel
import android.app.NotificationManager
import android.app.PendingIntent
import android.content.Context
import android.content.Intent
import androidx.core.app.NotificationCompat
import androidx.core.app.NotificationManagerCompat
import com.agoria.app.MainActivity
import com.agoria.app.R

object Notifier {

    const val CHANNEL_AUTONOMY = "autonomy"
    const val CHANNEL_APPROVALS = "approvals"
    const val CHANNEL_BUILDS = "builds"
    const val CHANNEL_AGENT_MESSAGES = "agent_messages"

    fun ensureChannels(context: Context) {
        val manager = context.getSystemService(NotificationManager::class.java) ?: return
        manager.createNotificationChannel(
            NotificationChannel(
                CHANNEL_AUTONOMY,
                "Autonomy",
                NotificationManager.IMPORTANCE_LOW,
            ).apply { description = "Shows that the agent loop is running." },
        )
        manager.createNotificationChannel(
            NotificationChannel(
                CHANNEL_APPROVALS,
                "Approvals",
                NotificationManager.IMPORTANCE_HIGH,
            ).apply { description = "An agent needs your approval before it builds or sends something." },
        )
        manager.createNotificationChannel(
            NotificationChannel(
                CHANNEL_BUILDS,
                "Builds",
                NotificationManager.IMPORTANCE_DEFAULT,
            ).apply { description = "A build artifact is ready to download." },
        )
        manager.createNotificationChannel(
            NotificationChannel(
                CHANNEL_AGENT_MESSAGES,
                "Agent Messages",
                NotificationManager.IMPORTANCE_HIGH,
            ).apply { description = "When an agent sends you a message in chat." },
        )
    }

    private fun base(context: Context, channel: String): NotificationCompat.Builder {
        val intent = Intent(context, MainActivity::class.java).apply {
            flags = Intent.FLAG_ACTIVITY_NEW_TASK or Intent.FLAG_ACTIVITY_CLEAR_TOP
        }
        val pending = PendingIntent.getActivity(
            context,
            0,
            intent,
            PendingIntent.FLAG_UPDATE_CURRENT or PendingIntent.FLAG_IMMUTABLE,
        )
        return NotificationCompat.Builder(context, channel)
            .setSmallIcon(R.drawable.ic_stat_agoria)
            .setContentIntent(pending)
            .setAutoCancel(true)
            .setPriority(
                if (channel == CHANNEL_APPROVALS) NotificationCompat.PRIORITY_HIGH else NotificationCompat.PRIORITY_DEFAULT,
            )
    }

    fun notifyApproval(context: Context, title: String, body: String) {
        if (!NotificationManagerCompat.from(context).areNotificationsEnabled()) return
        val notification = base(context, CHANNEL_APPROVALS)
            .setContentTitle(title)
            .setContentText(body)
            .setStyle(NotificationCompat.BigTextStyle().bigText(body))
            .build()
        NotificationManagerCompat.from(context).notify(title.hashCode(), notification)
    }

    fun notifyBuild(context: Context, title: String, body: String) {
        if (!NotificationManagerCompat.from(context).areNotificationsEnabled()) return
        val notification = base(context, CHANNEL_BUILDS)
            .setContentTitle(title)
            .setContentText(body)
            .build()
        NotificationManagerCompat.from(context).notify(title.hashCode(), notification)
    }

    fun notifyLocal(context: Context, title: String, body: String) {
        if (!NotificationManagerCompat.from(context).areNotificationsEnabled()) return
        val notification = base(context, CHANNEL_AUTONOMY)
            .setContentTitle(title)
            .setContentText(body)
            .build()
        NotificationManagerCompat.from(context).notify((title + body).hashCode(), notification)
    }

    fun cancelApproval(context: Context, title: String) {
        NotificationManagerCompat.from(context).cancel(title.hashCode())
    }

    fun notifyAgentMessage(context: Context, agentName: String, message: String) {
        if (!NotificationManagerCompat.from(context).areNotificationsEnabled()) return
        val notification = base(context, CHANNEL_AGENT_MESSAGES)
            .setContentTitle(agentName)
            .setContentText(message.take(100))
            .setStyle(NotificationCompat.BigTextStyle().bigText(message.take(4000)))
            .build()
        NotificationManagerCompat.from(context).notify(agentName.hashCode(), notification)
    }
}