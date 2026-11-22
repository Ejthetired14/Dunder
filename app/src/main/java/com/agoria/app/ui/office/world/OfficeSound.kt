package com.agoria.app.ui.office.world

import android.content.Context
import android.content.SharedPreferences
import android.media.AudioAttributes
import android.media.SoundPool
import android.os.SystemClock
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.setValue
import com.agoria.app.R
import java.util.concurrent.ConcurrentHashMap

/**
 * Three tiny sound effects (res/raw): an alert when something needs you, a blip when agents talk,
 * and a click for pixel buttons. On by default at a low volume; the switch is in the directory.
 */
object OfficeSound {
    enum class Fx { ALERT, TALK, CLICK }

    /** Compose state, so the directory switch updates by itself. */
    var enabled by mutableStateOf(true)
        private set

    private var pool: SoundPool? = null
    private var prefs: SharedPreferences? = null
    private val ids = HashMap<Fx, Int>()
    private val ready: MutableSet<Int> = ConcurrentHashMap.newKeySet()
    private val lastPlayed = HashMap<Fx, Long>()

    fun init(context: Context) {
        if (pool != null) return
        val app = context.applicationContext
        prefs = app.getSharedPreferences("dunder_office", Context.MODE_PRIVATE)
        enabled = prefs?.getBoolean("sound_on", true) ?: true
        val attrs = AudioAttributes.Builder()
            .setUsage(AudioAttributes.USAGE_GAME)
            .setContentType(AudioAttributes.CONTENT_TYPE_SONIFICATION)
            .build()
        val sp = SoundPool.Builder().setMaxStreams(3).setAudioAttributes(attrs).build()
        sp.setOnLoadCompleteListener { _, sampleId, status -> if (status == 0) ready.add(sampleId) }
        ids[Fx.ALERT] = sp.load(app, R.raw.office_alert, 1)
        ids[Fx.TALK] = sp.load(app, R.raw.office_talk, 1)
        ids[Fx.CLICK] = sp.load(app, R.raw.office_click, 1)
        pool = sp
    }

    fun setSoundEnabled(on: Boolean) {
        enabled = on
        prefs?.edit()?.putBoolean("sound_on", on)?.apply()
    }

    fun play(fx: Fx) {
        if (!enabled) return
        val id = ids[fx] ?: return
        if (id !in ready) return
        val now = SystemClock.elapsedRealtime()
        if (now - (lastPlayed[fx] ?: 0L) < 250L) return
        lastPlayed[fx] = now
        pool?.play(id, 0.6f, 0.6f, 1, 0, 1f)
    }
}
