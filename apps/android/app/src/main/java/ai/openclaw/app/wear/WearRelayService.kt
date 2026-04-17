package ai.openclaw.app.wear

import android.util.Log
import com.google.android.gms.wearable.MessageEvent
import com.google.android.gms.wearable.Wearable
import com.google.android.gms.wearable.WearableListenerService
import ai.openclaw.app.NodeApp
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.launch
import kotlinx.coroutines.tasks.await
import org.json.JSONObject

/**
 * Listens for messages from the OpenClaw Wear OS app and relays them
 * through the phone's existing gateway connection.
 *
 * IMPORTANT: WearableListenerService is short-lived. Android creates it
 * for each message and destroys it immediately after onMessageReceived
 * returns. Long-running work (like chat) must run on a scope that
 * outlives this service — we use [WearRelayScope] for that.
 */
class WearRelayService : WearableListenerService() {

  override fun onCreate() {
    super.onCreate()
    WearRelayLog.log("Service created")
  }

  override fun onDestroy() {
    WearRelayLog.log("Service destroyed")
    super.onDestroy()
  }

  override fun onMessageReceived(event: MessageEvent) {
    val sourceNodeId = event.sourceNodeId
    val data = String(event.data, Charsets.UTF_8)
    Log.d(TAG, "onMessageReceived path=${event.path} from=$sourceNodeId len=${data.length}")
    WearRelayLog.incoming(event.path, sourceNodeId)

    val app = application as? NodeApp
    if (app == null) {
      WearRelayLog.error("NodeApp not available")
      return
    }

    // Use the app-level scope so work survives service destruction.
    WearRelayScope.launch {
      try {
        when (event.path) {
          PATH_PING -> handlePing(app, sourceNodeId)
          PATH_AGENTS -> handleAgents(app, sourceNodeId)
          PATH_CHAT -> handleChat(app, sourceNodeId, data)
          else -> {
            Log.w(TAG, "Unknown path: ${event.path}")
            WearRelayLog.error("Unknown path: ${event.path}")
          }
        }
      } catch (e: Throwable) {
        Log.e(TAG, "Error handling ${event.path}", e)
        WearRelayLog.error("${e.javaClass.simpleName}: ${e.message}")
        reply(app, sourceNodeId, PATH_ERROR, JSONObject().put("error", e.message ?: "unknown").toString())
      }
    }
  }

  private suspend fun handlePing(app: NodeApp, nodeId: String) {
    val runtime = app.peekRuntime()
    val connected = runtime?.isConnected?.value ?: false
    val status = runtime?.statusText?.value ?: "Phone app not running"
    val serverName = runtime?.serverName?.value

    WearRelayLog.log(
      if (connected) "Ping: gateway=$serverName" else "Ping: not connected ($status)",
    )

    val response = JSONObject().apply {
      put("connected", connected)
      put("status", status)
      if (serverName != null) put("serverName", serverName)
    }
    reply(app, nodeId, PATH_STATUS, response.toString())
  }

  private suspend fun handleAgents(app: NodeApp, nodeId: String) {
    val runtime = app.peekRuntime()
    if (runtime == null) {
      WearRelayLog.error("Agents: runtime null")
      reply(app, nodeId, PATH_AGENTS_RESULT, JSONObject().put("error", "runtime not available").toString())
      return
    }
    val rawJson = runtime.wearRelayAgentsList()
    if (rawJson == null) {
      WearRelayLog.error("Agents: gateway not connected")
      reply(app, nodeId, PATH_AGENTS_RESULT, JSONObject().put("error", "not connected to gateway").toString())
      return
    }
    WearRelayLog.log("Agents: sent list to watch")
    reply(app, nodeId, PATH_AGENTS_RESULT, rawJson)
  }

  private suspend fun handleChat(app: NodeApp, nodeId: String, data: String) {
    val runtime = app.peekRuntime()
    if (runtime == null) {
      WearRelayLog.error("Chat: runtime null")
      reply(app, nodeId, PATH_CHAT_RESPONSE, JSONObject().put("error", "runtime not available").toString())
      return
    }

    val json = JSONObject(data)
    val agentId = json.optString("agentId", "main")
    val text = json.optString("text", "")
    WearRelayLog.log("Chat: \"$text\" -> $agentId")

    reply(app, nodeId, PATH_CHAT_STATE, JSONObject().put("state", "thinking").put("agentId", agentId).toString())

    val result = runtime.wearRelayChatSend(agentId, text)

    val response = JSONObject().apply {
      put("agentId", agentId)
      if (result.text != null) {
        put("text", result.text)
        WearRelayLog.log("Chat: response ${result.text.length} chars")
      } else {
        val errorMsg = result.error ?: "Unknown error"
        put("error", errorMsg)
        WearRelayLog.error("Chat: $errorMsg")
      }
    }
    reply(app, nodeId, PATH_CHAT_RESPONSE, response.toString())
  }

  private suspend fun reply(app: NodeApp, nodeId: String, path: String, data: String) {
    try {
      Wearable.getMessageClient(app).sendMessage(nodeId, path, data.toByteArray(Charsets.UTF_8)).await()
      WearRelayLog.outgoing(path, nodeId)
    } catch (e: Throwable) {
      Log.e(TAG, "Failed to reply to $nodeId on $path", e)
      WearRelayLog.error("Send failed: $path - ${e.message}")
    }
  }

  companion object {
    private const val TAG = "WearRelay"
    const val PATH_PING = "/openclaw/ping"
    const val PATH_STATUS = "/openclaw/status"
    const val PATH_AGENTS = "/openclaw/agents"
    const val PATH_AGENTS_RESULT = "/openclaw/agents/result"
    const val PATH_CHAT = "/openclaw/chat"
    const val PATH_CHAT_STATE = "/openclaw/chat/state"
    const val PATH_CHAT_RESPONSE = "/openclaw/chat/response"
    const val PATH_ERROR = "/openclaw/error"
  }
}

/**
 * Application-level coroutine scope for relay work that must outlive
 * the ephemeral WearableListenerService.
 */
object WearRelayScope : CoroutineScope {
  override val coroutineContext = SupervisorJob() + Dispatchers.IO
}
