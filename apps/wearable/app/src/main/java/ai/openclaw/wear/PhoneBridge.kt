package ai.openclaw.wear

import android.content.Context
import android.util.Log
import com.google.android.gms.wearable.MessageClient
import com.google.android.gms.wearable.MessageEvent
import com.google.android.gms.wearable.Wearable
import kotlinx.coroutines.CompletableDeferred
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.tasks.await
import kotlinx.coroutines.withContext
import kotlinx.coroutines.withTimeoutOrNull
import org.json.JSONArray
import org.json.JSONObject
import java.util.concurrent.ConcurrentHashMap

/**
 * Talks to the OpenClaw phone relay over the Wearable Data Layer.
 *
 * One-shot paths (ping, agents) use [pending] for request/response pairing.
 * Chat uses a separate streaming handler because a single `chat` send can
 * produce multiple `chat/reply` messages before the final one.
 */
class PhoneBridge(private val context: Context) : MessageClient.OnMessageReceivedListener {

    private val messageClient = Wearable.getMessageClient(context)
    private val nodeClient = Wearable.getNodeClient(context)

    @Volatile
    var phoneNodeId: String? = null
        private set

    private val pending = ConcurrentHashMap<String, CompletableDeferred<String>>()

    /**
     * Multiple agents may be streaming at once — each registers its own
     * handler here, keyed by agentId. Incoming /openclaw/chat/reply messages
     * include an `agentId` field which routes them to the right handler.
     */
    private val chatReplyHandlers = ConcurrentHashMap<String, (String) -> Unit>()

    fun startListening() {
        messageClient.addListener(this)
    }

    fun stopListening() {
        messageClient.removeListener(this)
    }

    override fun onMessageReceived(event: MessageEvent) {
        val data = String(event.data, Charsets.UTF_8)
        Log.d(TAG, "onMessageReceived path=${event.path} len=${data.length}")

        if (event.path == PATH_CHAT_REPLY) {
            val agentId = try {
                JSONObject(data).optString("agentId", "").takeIf { it.isNotBlank() }
            } catch (_: Throwable) { null }
            val handler = agentId?.let { chatReplyHandlers[it] }
            if (handler != null) {
                handler(data)
            } else {
                Log.w(TAG, "Chat reply with no active handler for agent=$agentId")
            }
            return
        }

        val deferred = pending.remove(event.path)
        if (deferred != null) {
            deferred.complete(data)
        } else {
            Log.w(TAG, "No pending deferred for path=${event.path}")
        }
    }

    suspend fun discoverNodes(): List<NodeInfo> = withContext(Dispatchers.IO) {
        try {
            nodeClient.connectedNodes.await().map { node ->
                NodeInfo(id = node.id, displayName = node.displayName, isNearby = node.isNearby)
            }
        } catch (e: Throwable) {
            Log.e(TAG, "discoverNodes failed", e)
            emptyList()
        }
    }

    /** Find the connected phone node. Returns null if no phone is reachable. */
    suspend fun findPhone(): String? {
        val nodes = discoverNodes()
        val phone = nodes.firstOrNull { it.isNearby } ?: nodes.firstOrNull()
        phoneNodeId = phone?.id
        return phone?.id
    }

    /** Ping the phone and return gateway connection status. */
    suspend fun ping(): PingResult {
        val nodeId = phoneNodeId ?: return PingResult(false, "Phone not found")
        try {
            withContext(Dispatchers.IO) {
                messageClient.sendMessage(nodeId, PATH_PING, ByteArray(0)).await()
            }
        } catch (e: Throwable) {
            Log.e(TAG, "ping send failed", e)
            return PingResult(false, "Send failed: ${e.message}")
        }

        val response = waitForResponse(PATH_STATUS, timeoutMs = 8_000)
            ?: return PingResult(false, "No reply after 8s (message sent OK)")

        return try {
            val json = JSONObject(response)
            PingResult(
                connected = json.optBoolean("connected", false),
                status = json.optString("status", "Unknown"),
                serverName = json.optString("serverName", null),
            )
        } catch (_: Throwable) {
            PingResult(false, "Bad response format")
        }
    }

    /** Request agent list from the phone's gateway connection. */
    suspend fun getAgents(): List<Agent> {
        val nodeId = phoneNodeId ?: return emptyList()
        val response = sendAndWait(nodeId, PATH_AGENTS, "", PATH_AGENTS_RESULT, timeoutMs = 10_000)
            ?: return emptyList()
        return try {
            val json = JSONObject(response)
            if (json.has("error")) return emptyList()
            val agents = json.optJSONArray("agents") ?: return emptyList()
            (0 until agents.length()).mapNotNull { i ->
                val obj = agents.optJSONObject(i) ?: return@mapNotNull null
                val id = obj.optString("id", "").ifEmpty { return@mapNotNull null }
                val name = obj.optString("name", "").ifEmpty { id }
                val identity = obj.optJSONObject("identity")
                val title = identity?.optString("title", null)?.takeIf { it.isNotBlank() }
                val emoji = identity?.optString("emoji", null)
                val theme = identity?.optString("theme", null)
                val avatarUrl = identity?.optString("avatarUrl", null)
                    ?: identity?.optString("avatar", null)
                Agent(
                    id = id,
                    name = name,
                    title = title,
                    emoji = emoji,
                    theme = theme,
                    avatarUrl = avatarUrl,
                )
            }
        } catch (_: Throwable) {
            emptyList()
        }
    }

    /**
     * Send a chat message and stream replies via [onReply]. Suspends until
     * the final reply (or error/timeout). Multiple agents may call this
     * concurrently — each gets its own handler keyed by [agentId].
     */
    suspend fun chatStream(
        agentId: String,
        text: String,
        onReply: (ChatReply) -> Unit,
    ): String? {
        val nodeId = phoneNodeId ?: return "Phone not connected"
        val payload = JSONObject().apply {
            put("agentId", agentId)
            put("text", text)
        }.toString()

        val completion = CompletableDeferred<String?>()

        chatReplyHandlers[agentId] = { data ->
            try {
                val json = JSONObject(data)
                val err = json.optString("error", null)?.takeIf { it.isNotBlank() }
                val replyText = json.optString("text", "")
                val isFinal = json.optBoolean("final", false)
                val audioUrl = json.optString("audioUrl", null)?.takeIf { it.isNotBlank() }
                val audioBase64 = json.optString("audioBase64", null)?.takeIf { it.isNotBlank() }
                val audioAssetRef = json.optString("audioAssetRef", null)?.takeIf { it.isNotBlank() }
                val audioMime = json.optString("audioMime", null)?.takeIf { it.isNotBlank() }
                val segments = parseAudioSegments(json.optJSONArray("audioSegments"))

                if (err != null) {
                    completion.complete(err)
                } else {
                    onReply(
                        ChatReply(
                            text = replyText,
                            isFinal = isFinal,
                            audioUrl = audioUrl,
                            audioBase64 = audioBase64,
                            audioAssetRef = audioAssetRef,
                            audioMime = audioMime,
                            audioSegments = segments,
                        ),
                    )
                    if (isFinal) completion.complete(null)
                }
            } catch (e: Throwable) {
                completion.complete("Bad reply format: ${e.message}")
            }
        }

        try {
            withContext(Dispatchers.IO) {
                messageClient.sendMessage(nodeId, PATH_CHAT, payload.toByteArray(Charsets.UTF_8)).await()
            }
        } catch (e: Throwable) {
            chatReplyHandlers.remove(agentId)
            return "Send failed: ${e.message}"
        }

        try {
            withTimeoutOrNull(150_000) { completion.await() }
            return if (completion.isCompleted) {
                completion.getCompleted()
            } else {
                "Timed out waiting for reply (150s)"
            }
        } finally {
            chatReplyHandlers.remove(agentId)
        }
    }

    private fun parseAudioSegments(arr: JSONArray?): List<ChatAudioSegment>? {
        if (arr == null || arr.length() == 0) return null
        val out = ArrayList<ChatAudioSegment>(arr.length())
        for (i in 0 until arr.length()) {
            val obj = arr.optJSONObject(i) ?: continue
            out.add(
                ChatAudioSegment(
                    text = obj.optString("text", ""),
                    emotion = obj.optString("emotion", null)?.takeIf { it.isNotBlank() },
                    audioUrl = obj.optString("audioUrl", null)?.takeIf { it.isNotBlank() },
                    audioBase64 = obj.optString("audioBase64", null)?.takeIf { it.isNotBlank() },
                    audioAssetRef = obj.optString("audioAssetRef", null)?.takeIf { it.isNotBlank() },
                    audioMime = obj.optString("audioMime", null)?.takeIf { it.isNotBlank() },
                ),
            )
        }
        return out.takeIf { it.isNotEmpty() }
    }

    private suspend fun sendAndWait(
        nodeId: String,
        sendPath: String,
        data: String,
        responsePath: String,
        timeoutMs: Long,
    ): String? {
        val deferred = CompletableDeferred<String>()
        pending[responsePath] = deferred
        return try {
            withContext(Dispatchers.IO) {
                messageClient.sendMessage(nodeId, sendPath, data.toByteArray(Charsets.UTF_8)).await()
            }
            withTimeoutOrNull(timeoutMs) { deferred.await() }
        } catch (e: Throwable) {
            Log.e(TAG, "sendAndWait failed for $sendPath", e)
            pending.remove(responsePath)
            null
        }
    }

    private suspend fun waitForResponse(path: String, timeoutMs: Long): String? {
        val deferred = CompletableDeferred<String>()
        pending[path] = deferred
        return try {
            withTimeoutOrNull(timeoutMs) { deferred.await() }
        } catch (_: Throwable) {
            pending.remove(path)
            null
        }
    }

    data class NodeInfo(val id: String, val displayName: String, val isNearby: Boolean)
    data class PingResult(val connected: Boolean, val status: String, val serverName: String? = null)
    data class Agent(
        val id: String,
        val name: String,
        val title: String? = null,
        val emoji: String? = null,
        val theme: String? = null,
        val avatarUrl: String? = null,
    )
    data class ChatReply(
        val text: String,
        val isFinal: Boolean,
        val audioUrl: String? = null,
        val audioBase64: String? = null,
        val audioAssetRef: String? = null,
        val audioMime: String? = null,
        /**
         * Per-emotion audio segments shipped by the Phase-4 phone relay
         * (`NodeRuntime.buildWearAudioSegments`). When present and longer
         * than one entry, the watch plays each segment in order with its
         * emotion driving avatar state transitions. Older phone builds omit
         * the field; the watch falls back to the top-level single-blob audio.
         */
        val audioSegments: List<ChatAudioSegment>? = null,
    )

    /**
     * One emotion-tagged segment of the assistant's TTS reply. `emotion`
     * is the avatar state that preceded this segment (or null for the
     * leading segment before any `<<<state>>>` marker).
     */
    data class ChatAudioSegment(
        val text: String,
        val emotion: String? = null,
        val audioUrl: String? = null,
        val audioBase64: String? = null,
        val audioAssetRef: String? = null,
        val audioMime: String? = null,
    )

    companion object {
        private const val TAG = "PhoneBridge"
        private const val PATH_PING = "/openclaw/ping"
        private const val PATH_STATUS = "/openclaw/status"
        private const val PATH_AGENTS = "/openclaw/agents"
        private const val PATH_AGENTS_RESULT = "/openclaw/agents/result"
        private const val PATH_CHAT = "/openclaw/chat"
        private const val PATH_CHAT_REPLY = "/openclaw/chat/reply"
    }
}
