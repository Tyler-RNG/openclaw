package ai.openclaw.wear

import android.app.Notification
import android.app.NotificationChannel
import android.app.NotificationManager
import android.app.Service
import android.content.Intent
import android.os.IBinder

class WearForegroundService : Service() {
    override fun onCreate() {
        super.onCreate()
        val channel = NotificationChannel(CHANNEL_ID, "OpenClaw Voice", NotificationManager.IMPORTANCE_LOW)
        getSystemService(NotificationManager::class.java).createNotificationChannel(channel)
        val notification =
            Notification.Builder(this, CHANNEL_ID)
                .setContentTitle("OpenClaw")
                .setContentText("Voice line active")
                .setSmallIcon(android.R.drawable.ic_btn_speak_now)
                .build()
        startForeground(NOTIFICATION_ID, notification)
    }

    override fun onStartCommand(intent: Intent?, flags: Int, startId: Int): Int = START_STICKY

    override fun onBind(intent: Intent?): IBinder? = null

    companion object {
        private const val CHANNEL_ID = "openclaw_wear"
        private const val NOTIFICATION_ID = 1
    }
}
