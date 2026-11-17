package com.unvvv.app

import android.app.Application
import android.app.NotificationChannel
import android.app.NotificationManager
import com.unvvv.app.data.CameraRepo
import com.unvvv.app.data.Prefs
import com.unvvv.app.face.FaceDb
import com.unvvv.app.face.FaceEngine
import org.videolan.libvlc.LibVLC

class App : Application() {
    lateinit var libVlc: LibVLC
        private set

    override fun onCreate() {
        super.onCreate()
        instance = this
        Prefs.init(this)
        CameraRepo.init(this)
        FaceDb.init(this)
        FaceEngine.init(this)
        libVlc = LibVLC(
            this,
            arrayListOf(
                "--rtsp-tcp",
                "--network-caching=500",
                "--drop-late-frames",
                "--skip-frames",
                "--no-stats",
                "--no-sub-autodetect-file",
            )
        )
        val nm = getSystemService(NotificationManager::class.java)
        nm.createNotificationChannel(NotificationChannel(CHANNEL_REC, "Запись и наблюдение", NotificationManager.IMPORTANCE_LOW))
        nm.createNotificationChannel(NotificationChannel(CHANNEL_FACES, "Лица", NotificationManager.IMPORTANCE_HIGH))
    }

    companion object {
        const val CHANNEL_REC = "rec"
        const val CHANNEL_FACES = "faces"
        lateinit var instance: App
            private set
        val vlc: LibVLC get() = instance.libVlc
    }
}
