package com.unvvv.app.ui

import android.os.Handler
import android.os.Looper
import androidx.compose.foundation.background
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.material3.CircularProgressIndicator
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.DisposableEffect
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.key
import androidx.compose.runtime.mutableFloatStateOf
import androidx.compose.runtime.mutableLongStateOf
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.text.style.TextAlign
import androidx.compose.ui.unit.dp
import androidx.compose.ui.viewinterop.AndroidView
import com.unvvv.app.App
import com.unvvv.app.data.Camera
import com.unvvv.app.data.VlcBg
import com.unvvv.app.data.liveMedia
import org.videolan.libvlc.Media
import org.videolan.libvlc.MediaPlayer
import org.videolan.libvlc.util.VLCVideoLayout
import java.io.File

/** Живой поток с автоматическим переподключением. Все блокирующие вызовы VLC — в фоне. */
class LivePlayer(private val cam: Camera, private val sub: Boolean, private val audio: Boolean) {
    val player = MediaPlayer(App.vlc)
    var status by mutableStateOf<String?>("Подключение…")
        private set

    /** Соотношение сторон картинки (ширина/высота) с учётом SAR. */
    var aspect by mutableFloatStateOf(16f / 9f)
        private set
    var muted = true
        set(v) {
            field = v
            runCatching { player.setVolume(if (v) 0 else 100) }
        }
    private val handler = Handler(Looper.getMainLooper())
    @Volatile
    private var released = false
    private var attached = false

    init {
        player.setEventListener { e ->
            when (e.type) {
                MediaPlayer.Event.Vout -> if (e.voutCount > 0) {
                    status = null
                    updateAspect()
                }
                MediaPlayer.Event.ESAdded -> updateAspect()
                MediaPlayer.Event.Playing -> player.setVolume(if (muted) 0 else 100)
                MediaPlayer.Event.EncounteredError, MediaPlayer.Event.EndReached -> retry()
            }
        }
    }

    private fun updateAspect() {
        val t = runCatching { player.currentVideoTrack }.getOrNull() ?: return
        if (t.width <= 0 || t.height <= 0) return
        val sar = if (t.sarNum > 0 && t.sarDen > 0) t.sarNum.toFloat() / t.sarDen else 1f
        aspect = t.width * sar / t.height
    }

    fun attach(layout: VLCVideoLayout) {
        if (attached || released) return
        player.attachViews(layout, null, false, true)
        attached = true
        start()
    }

    private fun start() {
        if (released) return
        val m = liveMedia(cam, sub, audio = audio)
        player.setMedia(m)
        m.release()
        player.play()
    }

    private fun retry() {
        if (released) return
        status = "Нет связи с камерой.\nПереподключение…"
        handler.removeCallbacksAndMessages(null)
        handler.postDelayed({
            if (!released) VlcBg.run {
                synchronized(player) {
                    if (!released) {
                        player.stop()
                        start()
                    }
                }
            }
        }, 3000)
    }

    fun release() {
        released = true
        handler.removeCallbacksAndMessages(null)
        player.setEventListener(null)
        if (attached) runCatching { player.detachViews() }
        val p = player
        VlcBg.run {
            synchronized(p) {
                p.stop()
                p.release()
            }
        }
    }
}

@Composable
fun LiveVideo(
    cam: Camera,
    sub: Boolean,
    modifier: Modifier = Modifier,
    audio: Boolean = false,
    muted: Boolean = true,
    compact: Boolean = false,
    onAspect: (Float) -> Unit = {},
) {
    val lp = remember(cam, sub, audio) { LivePlayer(cam, sub, audio) }
    DisposableEffect(lp) { onDispose { lp.release() } }
    LaunchedEffect(lp, muted) { lp.muted = muted }
    LaunchedEffect(lp, lp.aspect) { onAspect(lp.aspect) }
    Box(modifier.background(Color.Black), contentAlignment = Alignment.Center) {
        key(lp) {
            AndroidView(
                factory = { ctx -> VLCVideoLayout(ctx).also { lp.attach(it) } },
                modifier = Modifier.fillMaxSize(),
            )
        }
        lp.status?.let { s ->
            Column(horizontalAlignment = Alignment.CenterHorizontally, modifier = Modifier.padding(8.dp)) {
                if (s.startsWith("Подключение")) {
                    CircularProgressIndicator(Modifier.size(if (compact) 20.dp else 32.dp), strokeWidth = 2.dp)
                    Spacer(Modifier.height(6.dp))
                }
                Text(
                    s,
                    color = Color.White.copy(alpha = 0.8f),
                    style = if (compact) MaterialTheme.typography.labelSmall else MaterialTheme.typography.bodyMedium,
                    textAlign = TextAlign.Center,
                )
            }
        }
    }
}

/** Плеер файлов записи. */
class FilePlayer {
    val player = MediaPlayer(App.vlc)
    var playing by mutableStateOf(false)
        private set
    var time by mutableLongStateOf(0L)
        private set
    var length by mutableLongStateOf(0L)
        private set
    var ended by mutableStateOf(false)
        private set
    var rate by mutableFloatStateOf(1f)
    private var attached = false
    private var pending: File? = null

    init {
        player.setEventListener { e ->
            when (e.type) {
                MediaPlayer.Event.Playing -> {
                    playing = true
                    player.setRate(rate)
                }
                MediaPlayer.Event.Paused, MediaPlayer.Event.Stopped -> playing = false
                MediaPlayer.Event.TimeChanged -> time = e.timeChanged
                MediaPlayer.Event.LengthChanged -> length = e.lengthChanged
                MediaPlayer.Event.EndReached -> {
                    playing = false
                    ended = true
                }
            }
        }
    }

    fun attach(layout: VLCVideoLayout) {
        if (attached) return
        player.attachViews(layout, null, false, true)
        attached = true
        pending?.let { open(it) }
    }

    fun open(f: File) {
        if (!attached) {
            pending = f
            return
        }
        pending = null
        ended = false
        time = 0
        length = 0
        val m = Media(App.vlc, f.absolutePath)
        player.setMedia(m)
        m.release()
        player.play()
    }

    fun toggle() {
        if (player.isPlaying) player.pause() else player.play()
    }

    fun seek(ms: Long) {
        player.setTime(ms.coerceAtLeast(0))
        time = ms
    }

    fun seekBy(delta: Long) {
        val t = (time + delta).coerceAtLeast(0)
        seek(if (length > 0) t.coerceAtMost(length - 500) else t)
    }

    fun applyRate(r: Float) {
        rate = r
        player.setRate(r)
    }

    fun release() {
        player.setEventListener(null)
        if (attached) runCatching { player.detachViews() }
        val p = player
        VlcBg.run {
            p.stop()
            p.release()
        }
    }
}
