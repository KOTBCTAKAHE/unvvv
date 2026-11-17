package com.unvvv.app.record

import android.app.Notification
import android.app.PendingIntent
import android.app.Service
import android.content.BroadcastReceiver
import android.content.Context
import android.content.Intent
import android.content.pm.ServiceInfo
import android.net.wifi.WifiManager
import android.os.Build
import android.os.IBinder
import android.os.PowerManager
import android.os.SystemClock
import androidx.core.app.NotificationCompat
import androidx.core.app.ServiceCompat
import androidx.core.content.ContextCompat
import com.unvvv.app.App
import com.unvvv.app.R
import com.unvvv.app.data.Camera
import com.unvvv.app.data.CameraRepo
import com.unvvv.app.data.Prefs
import com.unvvv.app.data.liveMedia
import com.unvvv.app.face.FaceWatcher
import com.unvvv.app.ui.MainActivity
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.Job
import kotlinx.coroutines.NonCancellable
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.cancel
import kotlinx.coroutines.currentCoroutineContext
import kotlinx.coroutines.delay
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.combine
import kotlinx.coroutines.isActive
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext
import org.videolan.libvlc.MediaPlayer
import java.util.concurrent.atomic.AtomicBoolean

/**
 * Фоновая запись. Каждая камера пишется отдельным подключением к основному потоку
 * БЕЗ перекодирования (VLC stream output → MPEG-TS), поэтому нагрузка минимальна.
 * Файлы режутся на куски по Prefs.segmentMin минут, старые удаляются по лимиту.
 */
class RecordService : Service() {
    private val scope = CoroutineScope(SupervisorJob() + Dispatchers.Main)
    private val recorders = HashMap<String, Recorder>()
    private val watchers = HashMap<String, FaceWatcher>()
    private var wake: PowerManager.WakeLock? = null
    private var wifiLock: WifiManager.WifiLock? = null

    override fun onBind(intent: Intent?): IBinder? = null

    override fun onCreate() {
        super.onCreate()
        startFg(0)
        wake = getSystemService(PowerManager::class.java)
            .newWakeLock(PowerManager.PARTIAL_WAKE_LOCK, "unv:rec").apply { setReferenceCounted(false); acquire() }
        @Suppress("DEPRECATION")
        wifiLock = applicationContext.getSystemService(WifiManager::class.java)
            ?.createWifiLock(WifiManager.WIFI_MODE_FULL_HIGH_PERF, "unv:rec")
            ?.apply { setReferenceCounted(false); acquire() }
        scope.launch {
            combine(CameraRepo.cameras, manual, eventRec, reload) { cams, man, ev, _ ->
                val now = System.currentTimeMillis()
                cams.filter { it.record || it.id in man || (ev[it.id] ?: 0L) > now } to cams.filter { it.faces }
            }.collect { (rec, faces) -> sync(rec, faces) }
        }
        scope.launch {
            while (true) {
                delay(10_000)
                val now = System.currentTimeMillis()
                val m = eventRec.value.filterValues { it > now }
                if (m.size != eventRec.value.size) eventRec.value = m
            }
        }
    }

    override fun onStartCommand(intent: Intent?, flags: Int, startId: Int): Int {
        startFg(recorders.size, watchers.size)
        return START_STICKY
    }

    private fun sync(want: List<Camera>, faces: List<Camera>) {
        val hd = Prefs.faceHd
        val fids = faces.map { it.id }.toSet()
        watchers.keys.filter { it !in fids }.forEach { watchers.remove(it)?.stop() }
        for (c in faces) {
            val w = watchers[c.id]
            if (w == null || w.camera != c || w.hd != hd) {
                w?.stop()
                watchers[c.id] = FaceWatcher(c, hd).also { it.start() }
            }
        }
        watching.value = fids

        val ids = want.map { it.id }.toSet()
        recorders.keys.filter { it !in ids }.forEach { recorders.remove(it)?.stop() }
        for (c in want) {
            val r = recorders[c.id]
            if (r == null || r.camera != c) {
                r?.stop()
                recorders[c.id] = Recorder(applicationContext, c).also { it.start(scope) }
            }
        }
        manual.value = manual.value.filter { id -> CameraRepo.get(id) != null }.toSet()
        active.value = ids
        if (want.isEmpty() && faces.isEmpty()) stopSelf() else startFg(want.size, faces.size)
    }

    private fun startFg(count: Int, faceCount: Int = 0) {
        val pi = PendingIntent.getActivity(
            this, 0, Intent(this, MainActivity::class.java),
            PendingIntent.FLAG_IMMUTABLE or PendingIntent.FLAG_UPDATE_CURRENT
        )
        val n: Notification = NotificationCompat.Builder(this, App.CHANNEL_REC)
            .setSmallIcon(R.drawable.ic_rec)
            .setContentTitle(if (count > 0) "Идёт запись" else "Наблюдение")
            .setContentText(
                listOfNotNull(
                    if (count > 0) "запись: $count" else null,
                    if (faceCount > 0) "распознавание лиц: $faceCount" else null,
                ).joinToString(" · ").ifEmpty { "Подготовка…" }.replaceFirstChar { it.uppercase() }
            )
            .setOngoing(true)
            .setContentIntent(pi)
            .setPriority(NotificationCompat.PRIORITY_LOW)
            .build()
        val type = if (Build.VERSION.SDK_INT >= 34) ServiceInfo.FOREGROUND_SERVICE_TYPE_SPECIAL_USE else 0
        ServiceCompat.startForeground(this, 1, n, type)
    }

    override fun onDestroy() {
        recorders.values.forEach { it.stop() }
        recorders.clear()
        watchers.values.forEach { it.stop() }
        watchers.clear()
        active.value = emptySet()
        watching.value = emptySet()
        scope.cancel()
        runCatching { wake?.release() }
        runCatching { wifiLock?.release() }
        super.onDestroy()
    }

    companion object {
        /** Камеры, включённые на запись вручную (кнопкой ● в просмотре). */
        val manual = MutableStateFlow<Set<String>>(emptySet())

        /** Камеры, которые сейчас пишутся. */
        val active = MutableStateFlow<Set<String>>(emptySet())

        /** Камеры с активным распознаванием лиц. */
        val watching = MutableStateFlow<Set<String>>(emptySet())

        /** Запись по событию: id камеры → до какого времени писать. */
        val eventRec = MutableStateFlow<Map<String, Long>>(emptyMap())

        /** Перезапуск наблюдения (например, после смены качества анализа). */
        val reload = MutableStateFlow(0)

        fun recordFor(ctx: Context, camId: String, ms: Long) {
            val until = System.currentTimeMillis() + ms
            eventRec.value = eventRec.value + (camId to maxOf(until, eventRec.value[camId] ?: 0L))
            ensure(ctx)
        }

        fun ensure(ctx: Context) {
            val cams = CameraRepo.cameras.value
            val need = cams.any { it.record || it.faces } || manual.value.isNotEmpty() || eventRec.value.isNotEmpty()
            if (need) {
                runCatching {
                    ContextCompat.startForegroundService(ctx, Intent(ctx, RecordService::class.java))
                }
            }
        }

        fun toggleManual(ctx: Context, id: String) {
            manual.value = if (id in manual.value) manual.value - id else manual.value + id
            ensure(ctx)
        }

        fun stopAll() {
            manual.value = emptySet()
            eventRec.value = emptyMap()
            CameraRepo.setAllRecord(false)
        }
    }
}

private class Recorder(private val ctx: Context, val camera: Camera) {
    private var job: Job? = null

    fun start(scope: CoroutineScope) {
        job = scope.launch { loop() }
    }

    fun stop() {
        job?.cancel()
    }

    private suspend fun loop() {
        while (currentCoroutineContext().isActive) {
            val file = Storage.newFile(ctx, camera)
            val player = MediaPlayer(App.vlc)
            val failed = AtomicBoolean(false)
            player.setEventListener { e ->
                if (e.type == MediaPlayer.Event.EncounteredError || e.type == MediaPlayer.Event.EndReached) failed.set(true)
            }
            try {
                val m = liveMedia(camera, sub = false, caching = 1500, audio = false)
                m.addOption(":sout=#std{access=file,mux=ts,dst=${file.absolutePath}}")
                m.addOption(":sout-keep")
                player.setMedia(m)
                m.release()
                player.play()
                val end = SystemClock.elapsedRealtime() + Prefs.segmentMin.coerceAtLeast(1) * 60_000L
                var lastSize = -1L
                var stall = 0
                while (!failed.get() && SystemClock.elapsedRealtime() < end) {
                    delay(2000)
                    val s = file.length()
                    stall = if (s == lastSize) stall + 1 else 0
                    lastSize = s
                    if (stall >= 10) failed.set(true) // 20 секунд без данных — переподключаемся
                }
            } finally {
                withContext(NonCancellable + Dispatchers.IO) {
                    player.setEventListener(null)
                    runCatching { player.stop() }
                    runCatching { player.release() }
                    if (file.length() < 8 * 1024) file.delete()
                    runCatching { Storage.cleanup(ctx) }
                }
            }
            if (failed.get()) delay(5000)
        }
    }
}

class BootReceiver : BroadcastReceiver() {
    override fun onReceive(context: Context, intent: Intent) {
        if (intent.action == Intent.ACTION_BOOT_COMPLETED) RecordService.ensure(context)
    }
}
