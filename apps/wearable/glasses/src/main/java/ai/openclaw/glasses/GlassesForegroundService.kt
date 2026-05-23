package ai.openclaw.glasses

import android.app.Notification
import android.app.Service
import android.content.Intent
import android.os.IBinder
import androidx.core.app.NotificationCompat

/**
 * Foreground service that keeps the BLE link to the Frame alive while the
 * activity is backgrounded. Holds a connected-device notification so Android
 * 14+ doesn't reap the BLE stack.
 *
 * GlassesViewModel binds + unbinds this service; the service itself owns no
 * GlassesClient state today — that's a follow-up when we hoist the client
 * lifecycle out of the activity.
 */
class GlassesForegroundService : Service() {
    override fun onBind(intent: Intent?): IBinder? = null

    override fun onStartCommand(intent: Intent?, flags: Int, startId: Int): Int {
        startForeground(NOTIFICATION_ID, buildNotification())
        return START_STICKY
    }

    private fun buildNotification(): Notification =
        NotificationCompat.Builder(this, GlassesApp.FOREGROUND_CHANNEL_ID)
            .setContentTitle("OpenClaw Glasses")
            .setContentText("Connected to Brilliant Frame")
            .setSmallIcon(android.R.drawable.stat_sys_data_bluetooth)
            .setOngoing(true)
            .build()

    companion object {
        private const val NOTIFICATION_ID = 0x6c61 // 'la'
    }
}
