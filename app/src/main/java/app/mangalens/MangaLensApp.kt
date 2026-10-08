package app.mangalens

import android.app.Application
import android.app.NotificationChannel
import android.app.NotificationManager
import app.mangalens.ocr.ModelInstaller

class MangaLensApp : Application() {

    companion object {
        const val CHANNEL_ID = "mangalens"
    }

    override fun onCreate() {
        super.onCreate()
        ModelInstaller.prefetch(this)
        val nm = getSystemService(NotificationManager::class.java)
        nm.createNotificationChannel(
            NotificationChannel(CHANNEL_ID, "Traduction de l’écran", NotificationManager.IMPORTANCE_LOW)
        )
    }
}
