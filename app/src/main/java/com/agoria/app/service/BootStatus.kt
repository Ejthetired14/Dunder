package com.agoria.app.service

import android.content.Context

/**
 * RUN-5: remembers that the post-reboot autonomy restart failed, so the failure is not silent.
 *
 * Stored in a tiny SharedPreferences file (no Room, so it is safe to read right after boot and
 * from the UI). The reason is a short fixed sentence and never contains secrets. The file is
 * excluded from backup like everything else (SEC-1).
 */
object BootStatus {

    data class Failure(val atMillis: Long, val reason: String)

    private const val PREFS = "agoria_boot_status"
    private const val KEY_AT = "failed_at"
    private const val KEY_REASON = "failed_reason"

    fun recordFailure(context: Context, reason: String) {
        context.applicationContext
            .getSharedPreferences(PREFS, Context.MODE_PRIVATE)
            .edit()
            .putLong(KEY_AT, System.currentTimeMillis())
            .putString(KEY_REASON, reason.take(200))
            .apply()
    }

    /** The last unresolved boot-restart failure, or null if the last restart worked. */
    fun lastFailure(context: Context): Failure? {
        val prefs = context.applicationContext.getSharedPreferences(PREFS, Context.MODE_PRIVATE)
        val at = prefs.getLong(KEY_AT, 0L)
        if (at == 0L) return null
        return Failure(at, prefs.getString(KEY_REASON, "") ?: "")
    }

    fun clear(context: Context) {
        context.applicationContext
            .getSharedPreferences(PREFS, Context.MODE_PRIVATE)
            .edit()
            .clear()
            .apply()
    }
}
