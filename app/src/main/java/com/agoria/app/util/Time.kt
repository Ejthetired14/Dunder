package com.agoria.app.util

import java.text.SimpleDateFormat
import java.util.Date
import java.util.Locale

object Time {

    fun now(): Long = System.currentTimeMillis()

    fun clock(ts: Long): String =
        SimpleDateFormat("HH:mm", Locale.getDefault()).format(Date(ts))

    fun dateTime(ts: Long): String =
        SimpleDateFormat("MMM d, HH:mm", Locale.getDefault()).format(Date(ts))

    fun dayLabel(ts: Long): String {
        val cal = java.util.Calendar.getInstance()
        val today = cal.get(java.util.Calendar.DAY_OF_YEAR)
        cal.timeInMillis = ts
        val then = cal.get(java.util.Calendar.DAY_OF_YEAR)
        val fmt = SimpleDateFormat("MMM d", Locale.getDefault())
        return when (today - then) {
            0 -> "Today"
            1 -> "Yesterday"
            else -> fmt.format(Date(ts))
        }
    }

    fun relativeTime(ts: Long): String {
        val diff = now() - ts
        if (diff < 60_000) return "just now"
        if (diff < 3_600_000) return "${diff / 60_000}m ago"
        if (diff < 86_400_000) return "${diff / 3_600_000}h ago"
        if (diff < 604_800_000) return "${diff / 86_400_000}d ago"
        return SimpleDateFormat("MMM d", Locale.getDefault()).format(Date(ts))
    }

    fun iso(ts: Long): String =
        SimpleDateFormat("yyyy-MM-dd'T'HH:mm:ss'Z'", Locale.US).format(Date(ts))
}