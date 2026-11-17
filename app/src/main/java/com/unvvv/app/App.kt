package com.unvvv.app

import android.app.Application
import android.app.NotificationChannel
import android.app.NotificationManager
import com.unvvv.app.data.CameraRepo
import com.unvvv.app.data.Prefs
import org.videolan.libvlc.LibVLC

class App : Application() {
    lateinit var libVlc: LibVLC
        private set

    override fun onCreate() {
        super.onCreate()
        instance = this
        Prefs.init(this)
        CameraRepo.init(this)
        libVlc = LibVLC(
            this,
            arrayListOf(
                "--rtsp-tcp",
                "--network-caching=300",
                "--clock-jitter=0",
                "--clock-synchro=0",
                "--drop-late-frames",
                "--skip-frames",
                "--no-stats",
                "--no-sub-autodetect-file",
            )
        )
        getSystemService(NotificationManager::class.java).createNotificationChannel(
            NotificationChannel(CHANNEL_REC, "Запись камер", NotificationManager.IMPORTANCE_LOW)
        )
    }

    companion object {
        const val CHANNEL_REC = "rec"
        lateinit var instance: App
            private set
        val vlc: LibVLC get() = instance.libVlc
    }
}
