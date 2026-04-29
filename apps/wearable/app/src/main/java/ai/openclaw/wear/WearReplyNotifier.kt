package ai.openclaw.wear

import android.app.NotificationChannel
import android.app.NotificationManager
import android.app.PendingIntent
import android.content.Context
import android.content.Intent
import android.util.Log
import androidx.core.app.NotificationCompat
import androidx.core.app.NotificationManagerCompat
import com.google.android.gms.wearable.MessageEvent
import com.google.android.gms.wearable.WearableListenerService
import org.json.JSONObject

/**
 * Receives chat replies from the phone even when the Wear OS app is not in the
 * foreground and posts a Wear notification so the user sees the agent reply
 * without opening the app. Tapping the notification launches the dial.
 *
 * Wear OS routes `/openclaw/chat/reply` messages to both the foreground
 * `PhoneBridge` (when the main activity is resumed) and this background
 * listener — that's the platform contract. In-app UI always has priority
 * because the notification's content intent re-opens the same activity.
 *
 * Declared in AndroidManifest with an intent filter restricted to the chat
 * reply path so other message events don't wake this service needlessly.
 */
class WearReplyNotifier : WearableListenerService() {

    override fun onMessageReceived(event: MessageEvent) {
        if (event.path != PATH_CHAT_REPLY) return
        val data = String(event.data, Charsets.UTF_8)
        val parsed = runCatching { JSONObject(data) }.getOrNull() ?: return
        if (!parsed.optBoolean("final", false)) return
        val errorMsg = parsed.optString("error", "")
        if (errorMsg.isNotBlank()) return

        val agentId = parsed.optString("agentId", "").ifBlank { "main" }
        val text = parsed.optString("text", "").trim()
        if (text.isEmpty()) return

        ensureChannel()
        val tapIntent = Intent(this, WearMainActivity::class.java).apply {
            flags = Intent.FLAG_ACTIVITY_SINGLE_TOP or Intent.FLAG_ACTIVITY_CLEAR_TOP
            putExtra(EXTRA_AGENT_ID, agentId)
        }
        val pendingIntent = PendingIntent.getActivity(
            this,
            agentId.hashCode(),
            tapIntent,
            PendingIntent.FLAG_UPDATE_CURRENT or PendingIntent.FLAG_IMMUTABLE,
        )

        val notification = NotificationCompat.Builder(this, CHANNEL_ID)
            .setSmallIcon(android.R.drawable.ic_dialog_info)
            .setContentTitle(agentId.replaceFirstChar { it.titlecase() })
            .setContentText(text.take(140))
            .setStyle(NotificationCompat.BigTextStyle().bigText(text.take(400)))
            .setContentIntent(pendingIntent)
            .setAutoCancel(true)
            .setOnlyAlertOnce(true)
            .setCategory(NotificationCompat.CATEGORY_MESSAGE)
            .setPriority(NotificationCompat.PRIORITY_HIGH)
            .build()

        val manager = NotificationManagerCompat.from(this)
        if (!manager.areNotificationsEnabled()) {
            Log.d(TAG, "notifications disabled — skipping $agentId")
            return
        }
        try {
            // Per-agent notification id so successive replies from the same
            // agent update in place instead of stacking, while different
            // agents each keep a distinct notification.
            manager.notify(agentId.hashCode(), notification)
        } catch (e: SecurityException) {
            Log.w(TAG, "notify blocked", e)
        }
    }

    private fun ensureChannel() {
        val mgr = getSystemService(Context.NOTIFICATION_SERVICE) as NotificationManager
        if (mgr.getNotificationChannel(CHANNEL_ID) != null) return
        val channel = NotificationChannel(
            CHANNEL_ID,
            "Agent replies",
            NotificationManager.IMPORTANCE_HIGH,
        ).apply {
            description = "Replies from agents when the app isn't in focus"
            setShowBadge(true)
        }
        mgr.createNotificationChannel(channel)
    }

    companion object {
        private const val TAG = "WearReplyNotifier"
        private const val CHANNEL_ID = "agent-replies"
        private const val PATH_CHAT_REPLY = "/openclaw/chat/reply"
        const val EXTRA_AGENT_ID = "ai.openclaw.wear.EXTRA_AGENT_ID"
    }
}
