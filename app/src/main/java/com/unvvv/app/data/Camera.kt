package com.unvvv.app.data

import android.content.Context
import android.content.SharedPreferences
import android.net.Uri
import com.unvvv.app.App
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import org.json.JSONArray
import org.json.JSONObject
import org.videolan.libvlc.Media
import java.util.UUID

data class Camera(
    val id: String = UUID.randomUUID().toString(),
    val name: String,
    val host: String,
    val port: Int = 554,
    val user: String = "admin",
    val pass: String = "",
    /** Номер канала (для NVR). У отдельной камеры — 1. */
    val channel: Int = 1,
    /** Свой RTSP-адрес, если стандартный адрес UNV не подходит. */
    val customUrl: String = "",
    /** Постоянная запись. */
    val record: Boolean = false,
    /** Распознавание лиц в фоне. */
    val faces: Boolean = false,
) {
    /** sub = true — дополнительный (лёгкий) поток, false — основной. */
    fun url(sub: Boolean): String {
        val c = customUrl.trim()
        if (c.isNotEmpty()) {
            return if (sub) c.replace("/s0/", "/s1/").replace("/media/video1", "/media/video2") else c
        }
        return "rtsp://$host:$port/unicast/c$channel/s${if (sub) 1 else 0}/live"
    }

    fun toJson(): JSONObject = JSONObject()
        .put("id", id).put("name", name).put("host", host).put("port", port)
        .put("user", user).put("pass", pass).put("channel", channel)
        .put("customUrl", customUrl).put("record", record).put("faces", faces)

    companion object {
        fun fromJson(o: JSONObject) = Camera(
            id = o.getString("id"),
            name = o.optString("name", "Камера"),
            host = o.optString("host", ""),
            port = o.optInt("port", 554),
            user = o.optString("user", "admin"),
            pass = o.optString("pass", ""),
            channel = o.optInt("channel", 1),
            customUrl = o.optString("customUrl", ""),
            record = o.optBoolean("record", false),
            faces = o.optBoolean("faces", false),
        )
    }
}

/** Готовит VLC Media для живого RTSP-потока камеры. */
fun liveMedia(cam: Camera, sub: Boolean, caching: Int = Prefs.cachingMs(), audio: Boolean = false): Media {
    val m = Media(App.vlc, Uri.parse(cam.url(sub)))
    if (cam.user.isNotEmpty()) {
        m.addOption(":rtsp-user=${cam.user}")
        m.addOption(":rtsp-pwd=${cam.pass}")
    }
    m.addOption(":network-caching=$caching")
    if (!audio) m.addOption(":no-audio")
    m.addOption(":live-caching=$caching")
    m.setHWDecoderEnabled(true, false)
    return m
}

/** Остановка/освобождение плееров VLC — блокирующие вызовы, поэтому не в главном потоке. */
object VlcBg {
    private val exec = java.util.concurrent.Executors.newFixedThreadPool(2) { r ->
        Thread(r, "vlc-bg").apply { isDaemon = true }
    }

    fun run(block: () -> Unit) {
        exec.execute { runCatching(block) }
    }
}

object CameraRepo {
    private lateinit var sp: SharedPreferences
    private val _cameras = MutableStateFlow<List<Camera>>(emptyList())
    val cameras: StateFlow<List<Camera>> = _cameras

    fun init(ctx: Context) {
        sp = ctx.getSharedPreferences("cameras", Context.MODE_PRIVATE)
        _cameras.value = runCatching {
            val arr = JSONArray(sp.getString("list", "[]"))
            (0 until arr.length()).map { Camera.fromJson(arr.getJSONObject(it)) }
        }.getOrDefault(emptyList())
    }

    @Synchronized
    private fun save(list: List<Camera>) {
        _cameras.value = list
        val arr = JSONArray()
        list.forEach { arr.put(it.toJson()) }
        sp.edit().putString("list", arr.toString()).apply()
    }

    fun get(id: String) = _cameras.value.firstOrNull { it.id == id }

    fun upsert(c: Camera) {
        val l = _cameras.value.toMutableList()
        val i = l.indexOfFirst { it.id == c.id }
        if (i >= 0) l[i] = c else l.add(c)
        save(l)
    }

    fun addAll(list: List<Camera>) = save(_cameras.value + list)

    fun remove(id: String) = save(_cameras.value.filterNot { it.id == id })

    fun setAllRecord(on: Boolean) = save(_cameras.value.map { it.copy(record = on) })
}
