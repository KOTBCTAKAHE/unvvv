package com.unvvv.app.face

import android.graphics.RectF
import android.os.Handler
import android.os.Looper
import com.unvvv.app.App
import com.unvvv.app.data.Camera
import com.unvvv.app.data.VlcBg
import com.unvvv.app.data.liveMedia
import org.videolan.libvlc.MediaPlayer

/** Фоновое наблюдение за одной камерой: отдельный лёгкий плеер без экрана → кадры → FaceEngine. */
class FaceWatcher(val camera: Camera, val hd: Boolean) {
    private val w = if (hd) 1920 else 1280
    private val h = if (hd) 1080 else 720
    private val main = Handler(Looper.getMainLooper())
    private var player: MediaPlayer? = null
    @Volatile
    private var grabber: FrameGrabber? = null
    @Volatile
    private var content = RectF(0f, 0f, w.toFloat(), h.toFloat())
    @Volatile
    private var stopped = false

    fun start() {
        VlcBg.run {
            val g = FrameGrabber(
                w, h, intervalMs = 800,
                wants = { FaceEngine.canAccept(camera.id) },
                onFrame = { bmp -> FaceEngine.submit(camera, bmp, content) },
            )
            g.start()
            grabber = g
            main.post { if (stopped) g.release() else open(g) }
        }
    }

    private fun open(g: FrameGrabber) {
        val p = MediaPlayer(App.vlc)
        player = p
        val vout = p.vlcVout
        vout.setVideoSurface(g.surface, null)
        vout.setWindowSize(w, h)
        vout.attachViews()
        p.setEventListener { e ->
            when (e.type) {
                MediaPlayer.Event.Vout, MediaPlayer.Event.ESAdded -> updateContent(p)
                MediaPlayer.Event.EncounteredError, MediaPlayer.Event.EndReached -> retry(p)
            }
        }
        VlcBg.run { synchronized(p) { if (!stopped) play(p) } }
    }

    private fun play(p: MediaPlayer) {
        val m = liveMedia(camera, sub = !hd, caching = 1000, audio = false)
        p.setMedia(m)
        m.release()
        p.play()
    }

    private fun retry(p: MediaPlayer) {
        if (stopped) return
        main.removeCallbacksAndMessages(null)
        main.postDelayed({
            if (!stopped) VlcBg.run {
                synchronized(p) {
                    if (!stopped) {
                        p.stop()
                        play(p)
                    }
                }
            }
        }, 5000)
    }

    private fun updateContent(p: MediaPlayer) {
        val t = runCatching { p.currentVideoTrack }.getOrNull() ?: return
        if (t.width <= 0 || t.height <= 0) return
        val sar = if (t.sarNum > 0 && t.sarDen > 0) t.sarNum.toFloat() / t.sarDen else 1f
        val a = t.width * sar / t.height
        val ba = w.toFloat() / h
        content = if (a > ba) {
            val ch = w / a
            RectF(0f, (h - ch) / 2f, w.toFloat(), (h + ch) / 2f)
        } else {
            val cw = h * a
            RectF((w - cw) / 2f, 0f, (w + cw) / 2f, h.toFloat())
        }
    }

    fun stop() {
        stopped = true
        main.removeCallbacksAndMessages(null)
        main.post {
            val p = player
            val g = grabber
            player = null
            p?.setEventListener(null)
            if (p != null) runCatching { p.vlcVout.detachViews() }
            VlcBg.run {
                if (p != null) synchronized(p) {
                    p.stop()
                    p.release()
                }
                g?.release()
            }
        }
    }
}
