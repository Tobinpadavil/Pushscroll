package com.teserakt.pushscroll

import android.content.Context

class Prefs(context: Context) {
    private val sp = context.applicationContext.getSharedPreferences("pushscroll", Context.MODE_PRIVATE)

    var blockManual: Boolean
        get() = sp.getBoolean("block_manual", true)
        set(v) = sp.edit().putBoolean("block_manual", v).apply()

    var lockAllYoutube: Boolean
        get() = sp.getBoolean("lock_all_yt", false)
        set(v) = sp.edit().putBoolean("lock_all_yt", v).apply()

    var sensitivity: Int
        get() = sp.getInt("sensitivity", 50)
        set(v) = sp.edit().putInt("sensitivity", v).apply()

    var total: Int
        get() = sp.getInt("total", 0)
        set(v) = sp.edit().putInt("total", v).apply()
}
