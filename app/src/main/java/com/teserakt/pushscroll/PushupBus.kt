package com.teserakt.pushscroll

import android.content.Context
import android.os.Handler
import android.os.Looper
import java.util.concurrent.CopyOnWriteArrayList

/** In-process hub shared by the camera service, the Shorts controller and the screen. */
object PushupBus {
    const val EV_REP = 1
    const val EV_STATE = 2

    fun interface Listener {
        fun onPushupEvent(event: Int)
    }

    private val main = Handler(Looper.getMainLooper())
    private val listeners = CopyOnWriteArrayList<Listener>()

    @Volatile var running = false
    @Volatile var sessionCount = 0
    @Volatile var total = -1
    @Volatile var status = "Stopped"
    @Volatile var level = 0f
    @Volatile var tracking = false

    fun add(l: Listener) { listeners.addIfAbsent(l) }
    fun remove(l: Listener) { listeners.remove(l) }

    fun emit(event: Int) {
        main.post { for (l in listeners) l.onPushupEvent(event) }
    }

    fun totalOf(context: Context): Int {
        if (total < 0) total = Prefs(context).total
        return total
    }

    fun rep(context: Context) {
        sessionCount++
        total = totalOf(context) + 1
        Prefs(context).total = total
        emit(EV_REP)
    }

    fun resetSession() {
        sessionCount = 0
        emit(EV_STATE)
    }
}
