package com.unvvv.app.data

import android.content.Context
import android.content.SharedPreferences

object Prefs {
    private lateinit var sp: SharedPreferences

    fun init(ctx: Context) {
        sp = ctx.getSharedPreferences("prefs", Context.MODE_PRIVATE)
    }

    /** Лимит места под записи, ГБ. */
    var storageGb: Int
        get() = sp.getInt("storageGb", 16)
        set(v) = sp.edit().putInt("storageGb", v).apply()

    /** Длина одного файла записи, минут. */
    var segmentMin: Int
        get() = sp.getInt("segmentMin", 10)
        set(v) = sp.edit().putInt("segmentMin", v).apply()

    var gridCols: Int
        get() = sp.getInt("gridCols", 2)
        set(v) = sp.edit().putInt("gridCols", v).apply()

    var lastUser: String
        get() = sp.getString("lastUser", "admin") ?: "admin"
        set(v) = sp.edit().putString("lastUser", v).apply()

    var lastPass: String
        get() = sp.getString("lastPass", "") ?: ""
        set(v) = sp.edit().putString("lastPass", v).apply()
}
