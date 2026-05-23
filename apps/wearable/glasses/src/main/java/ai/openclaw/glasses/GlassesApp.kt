package ai.openclaw.glasses

import android.app.Application
import android.app.NotificationChannel
import android.app.NotificationManager

class GlassesApp : Application() {
    override fun onCreate() {
        super.onCreate()
        val nm = getSystemService(NotificationManager::class.java)
        nm?.createNotificationChannel(
            NotificationChannel(
                FOREGROUND_CHANNEL_ID,
                "Glasses link",
                NotificationManager.IMPORTANCE_LOW,
            ),
        )
    }

    companion object {
        const val FOREGROUND_CHANNEL_ID = "glasses-link"
    }
}
