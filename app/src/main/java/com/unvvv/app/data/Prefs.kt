package com.unvvv.app.data

import android.content.Context
import android.content.SharedPreferences

object Prefs {
    private lateinit var sp: SharedPreferences

    fun init(ctx: Context) {
        sp = ctx.getSharedPreferences("prefs", Context.MODE_PRIVATE)
    }

    private fun int(k: String, d: Int) = sp.getInt(k, d)
    private fun putInt(k: String, v: Int) = sp.edit().putInt(k, v).apply()
    private fun bool(k: String, d: Boolean) = sp.getBoolean(k, d)
    private fun putBool(k: String, v: Boolean) = sp.edit().putBoolean(k, v).apply()

    /** Лимит места под записи, ГБ. */
    var storageGb: Int
        get() = int("storageGb", 16)
        set(v) = putInt("storageGb", v)

    /** Длина одного файла записи, минут. */
    var segmentMin: Int
        get() = int("segmentMin", 10)
        set(v) = putInt("segmentMin", v)

    var gridCols: Int
        get() = int("gridCols", 2)
        set(v) = putInt("gridCols", v)

    var lastUser: String
        get() = sp.getString("lastUser", "admin") ?: "admin"
        set(v) = sp.edit().putString("lastUser", v).apply()

    var lastPass: String
        get() = sp.getString("lastPass", "") ?: ""
        set(v) = sp.edit().putString("lastPass", v).apply()

    /** 0 — минимальная задержка, 1 — баланс, 2 — максимально плавно. */
    var latency: Int
        get() = int("latency", 1)
        set(v) = putInt("latency", v)

    fun cachingMs(): Int = when (latency) {
        0 -> 150
        2 -> 1200
        else -> 500
    }

    // ---- Распознавание лиц ----

    /** Порог сходства лиц (косинус), 0.40…0.80. Выше — строже. */
    var faceThreshold: Float
        get() = sp.getFloat("faceThreshold", 0.60f)
        set(v) = sp.edit().putFloat("faceThreshold", v).apply()

    var notifyUnknown: Boolean
        get() = bool("notifyUnknown", true)
        set(v) = putBool("notifyUnknown", v)

    var notifyKnown: Boolean
        get() = bool("notifyKnown", true)
        set(v) = putBool("notifyKnown", v)

    /** Включать запись камеры на 2 минуты при незнакомом лице. */
    var recordOnUnknown: Boolean
        get() = bool("recordOnUnknown", false)
        set(v) = putBool("recordOnUnknown", v)

    /** Анализировать основной поток (точнее, дальше видит, но больше нагрузка). */
    var faceHd: Boolean
        get() = bool("faceHd", false)
        set(v) = putBool("faceHd", v)
}
