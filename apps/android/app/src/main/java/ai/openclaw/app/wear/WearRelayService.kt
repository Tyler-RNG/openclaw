package ai.openclaw.app.wear

import android.util.Log
import com.google.android.gms.wearable.MessageEvent
import com.google.android.gms.wearable.Wearable
import com.google.android.gms.wearable.WearableListenerService
import ai.openclaw.app.NodeApp
import ai.openclaw.app.NodeRuntime
import com.google.android.gms.wearable.Asset
import com.google.android.gms.wearable.PutDataMapRequest
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.launch
import kotlinx.coroutines.tasks.await
import org.json.JSONObject

/**
 * Relays messages from the Wear OS app through the phone's gateway connection.
 *
 * The service is short-lived — Android destroys it right after onMessageReceived
 * returns. Long-running work runs on [WearRelayScope] so it outlives the service.
 */
class WearRelayService : WearableListenerService() {

  override fun onMessageReceived(event: MessageEvent) {
    val source = event.sourceNodeId
    val path = event.path
    val data = String(event.data, Charsets.UTF_8)
    val short = shortNode(source)
    WearRelayLog.incoming(tagFor(path), "$short · ${data.length}B")

    val app = application as? NodeApp
    if (app == null) {
      WearRelayLog.error("relay", "NodeApp unavailable")
      return
    }

    WearRelayScope.launch {
      WearRelayLog.begin()
      try {
        when (path) {
          PATH_PING -> handlePing(app, source)
          PATH_AGENTS -> handleAgents(app, source)
          PATH_CHAT -> handleChat(app, source, data)
          else -> WearRelayLog.warn("relay", "unknown path $path")
        }
      } catch (e: Throwable) {
        Log.e(TAG, "relay error on $path", e)
        WearRelayLog.error(tagFor(path), "${e.javaClass.simpleName}: ${e.message ?: "error"}")
        reply(app, source, PATH_ERROR, JSONObject().put("error", e.message ?: "unknown").toString())
      } finally {
        WearRelayLog.end()
      }
    }
  }

  private suspend fun handlePing(app: NodeApp, nodeId: String) {
    val runtime = app.peekRuntime()
    val connected = runtime?.isConnected?.value ?: false
    val status = runtime?.statusText?.value ?: "Phone app not running"
    val serverName = runtime?.serverName?.value

    val summary = if (connected) "gateway=${serverName ?: "?"}" else status
    WearRelayLog.info("ping", summary)

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
      reply(app, nodeId, PATH_AGENTS_RESULT, JSONObject().put("error", "runtime unavailable").toString())
      WearRelayLog.error("agents", "runtime unavailable")
      return
    }
    val rawJson = runtime.wearRelayAgentsList()
    if (rawJson == null) {
      reply(app, nodeId, PATH_AGENTS_RESULT, JSONObject().put("error", "gateway not connected").toString())
      WearRelayLog.error("agents", "gateway not connected")
      return
    }
    val transformed = rewriteAvatars(rawJson, app)
    reply(app, nodeId, PATH_AGENTS_RESULT, transformed)
    WearRelayLog.info("agents", "list sent")
  }

  /**
   * Rewrites each agent's avatar to a `wear-asset:avatar:<agentId>` reference
   * and simultaneously publishes the original bytes as a Wearable DataClient
   * Asset at `/openclaw/avatars/<agentId>`. DataClient has no 100 KB cap and
   * transfers binary efficiently, so animated GIFs come through intact.
   * Phone is on Tailscale so it can fetch the sidecar; watch isn't, so it
   * relies on the Data Layer.
   */
  private suspend fun rewriteAvatars(rawJson: String, app: NodeApp): String {
    val dataPlane = app.peekRuntime()?.wearRelayDataPlane()
    val token = app.peekRuntime()?.wearRelayAuthToken()
    return kotlinx.coroutines.withContext(kotlinx.coroutines.Dispatchers.IO) {
      try {
        val root = JSONObject(rawJson)
        val agents = root.optJSONArray("agents") ?: return@withContext rawJson
        var published = 0
        var failed = 0
        for (i in 0 until agents.length()) {
          val agentObj = agents.optJSONObject(i) ?: continue
          val agentId = agentObj.optString("id", "").trim().ifEmpty { continue }
          val identity = agentObj.optJSONObject("identity") ?: continue
          val key = when {
            identity.optString("avatarUrl", "").isNotBlank() -> "avatarUrl"
            identity.optString("avatar", "").isNotBlank() -> "avatar"
            else -> continue
          }
          val value = identity.optString(key)
          val fetched: Pair<ByteArray, String>? = when {
            value.startsWith("data:") -> null
            value.startsWith("file://") || value.startsWith("/") -> localPathToBytes(value)
            value.startsWith("http://") || value.startsWith("https://") -> fetchUrlAsBytes(value)
            dataPlane != null -> buildDataPlaneAssetUrl(dataPlane, token, value)?.let { fetchUrlAsBytes(it) }
            else -> null
          }
          if (fetched != null) {
            val (bytes, mime) = fetched
            if (putAvatarAsset(app, agentId, bytes, mime)) {
              identity.put(key, "wear-asset:avatar:$agentId")
              published++
            } else {
              failed++
            }
          } else if (!value.startsWith("data:")) {
            failed++
          }
        }
        if (published > 0 || failed > 0) {
          WearRelayLog.info("agents", "avatars: $published via asset, $failed failed")
        }
        root.toString()
      } catch (_: Throwable) {
        rawJson
      }
    }
  }

  /** Publishes the avatar bytes as a DataClient Asset. Returns true on success. */
  private suspend fun putAvatarAsset(app: NodeApp, agentId: String, bytes: ByteArray, mime: String): Boolean {
    return try {
      val asset = Asset.createFromBytes(bytes)
      val request = PutDataMapRequest.create("/openclaw/avatars/$agentId").apply {
        dataMap.putAsset("data", asset)
        dataMap.putString("mime", mime)
        dataMap.putLong("ts", System.currentTimeMillis())
      }.asPutDataRequest().setUrgent()
      com.google.android.gms.wearable.Wearable.getDataClient(app).putDataItem(request).await()
      true
    } catch (e: Throwable) {
      WearRelayLog.warn("agents", "asset put $agentId: ${e.javaClass.simpleName}")
      false
    }
  }

  private fun localPathToBytes(url: String): Pair<ByteArray, String>? {
    val path = when {
      url.startsWith("file://") -> url.removePrefix("file://")
      url.startsWith("/") -> url
      else -> return null
    }
    return try {
      val file = java.io.File(path)
      if (!file.exists() || !file.canRead()) return null
      if (file.length() > MAX_AVATAR_BYTES) return null
      val mime = when (file.extension.lowercase()) {
        "gif" -> "image/gif"
        "png" -> "image/png"
        "jpg", "jpeg" -> "image/jpeg"
        "webp" -> "image/webp"
        else -> "image/gif"
      }
      file.readBytes() to mime
    } catch (_: Throwable) {
      null
    }
  }

  private fun fetchUrlAsBytes(urlStr: String): Pair<ByteArray, String>? {
    val host = try { java.net.URL(urlStr).host } catch (_: Throwable) { "?" }
    // Per-URL logs are diagnostic-level — summary goes to the panel instead.
    Log.d(TAG, "fetch ${urlStr.substringBefore('?')}")
    return try {
      val url = java.net.URL(urlStr)
      val conn = url.openConnection() as java.net.HttpURLConnection
      conn.connectTimeout = 5_000
      conn.readTimeout = 15_000
      conn.requestMethod = "GET"
      conn.setRequestProperty("Accept", "image/*")
      val code = conn.responseCode
      if (code != 200) {
        WearRelayLog.warn("agents", "$host HTTP $code")
        conn.disconnect()
        return null
      }
      val rawMime = conn.contentType?.substringBefore(';')?.trim()?.takeIf { it.startsWith("image/") }
        ?: guessMimeFromUrl(urlStr)
      val bytes = conn.inputStream.use { it.readBytes() }
      conn.disconnect()
      if (bytes.size > MAX_AVATAR_BYTES) {
        WearRelayLog.warn("agents", "avatar ${bytes.size / 1000}KB > raw cap")
        return null
      }
      bytes to rawMime
    } catch (e: java.net.UnknownHostException) {
      WearRelayLog.warn("agents", "DNS: can't resolve $host")
      null
    } catch (e: java.net.SocketTimeoutException) {
      WearRelayLog.warn("agents", "timeout reaching $host")
      null
    } catch (e: java.net.ConnectException) {
      WearRelayLog.warn("agents", "refused by $host: ${e.message?.take(30)}")
      null
    } catch (e: javax.net.ssl.SSLException) {
      WearRelayLog.warn("agents", "SSL/cert: ${e.javaClass.simpleName}")
      null
    } catch (e: Throwable) {
      WearRelayLog.warn("agents", "${e.javaClass.simpleName}: ${e.message?.take(40)}")
      null
    }
  }

  private fun guessMimeFromUrl(url: String): String {
    val clean = url.substringBefore('?').lowercase()
    return when {
      clean.endsWith(".png") -> "image/png"
      clean.endsWith(".jpg") || clean.endsWith(".jpeg") -> "image/jpeg"
      clean.endsWith(".webp") -> "image/webp"
      clean.endsWith(".svg") -> "image/svg+xml"
      else -> "image/gif"
    }
  }

  private fun buildDataPlaneAssetUrl(
    dataPlane: NodeRuntime.WearDataPlane,
    token: String?,
    relativePath: String,
  ): String? {
    val clean = relativePath.trimStart('/')
    if (clean.isEmpty()) return null
    val encoded = clean.split('/').joinToString("/") { segment ->
      java.net.URLEncoder.encode(segment, Charsets.UTF_8.name()).replace("+", "%20")
    }
    val base = "${dataPlane.baseUrl}/assets/$encoded"
    return if (!dataPlane.publicAssets && !token.isNullOrEmpty()) {
      val tokenEnc = java.net.URLEncoder.encode(token, Charsets.UTF_8.name())
      "$base?token=$tokenEnc"
    } else {
      base
    }
  }

  private suspend fun handleChat(app: NodeApp, nodeId: String, data: String) {
    val runtime = app.peekRuntime()
    if (runtime == null) {
      reply(app, nodeId, PATH_CHAT_REPLY, JSONObject()
        .put("final", true)
        .put("error", "runtime unavailable")
        .toString())
      WearRelayLog.error("chat", "runtime unavailable")
      return
    }

    val json = JSONObject(data)
    val agentId = json.optString("agentId", "main")
    val text = json.optString("text", "")
    WearRelayLog.info("chat", "$agentId ← ${preview(text)}")

    reply(app, nodeId, PATH_CHAT_STATE, JSONObject().put("state", "thinking").put("agentId", agentId).toString())

    var seq = 0
    val error = runtime.wearRelayChatStream(agentId, text) { part ->
      val msg = JSONObject().apply {
        put("agentId", agentId)
        put("seq", seq++)
        put("text", part.text)
        put("final", part.isFinal)
        when {
          part.audioAssetRef != null -> {
            put("audioAssetRef", part.audioAssetRef)
            put("audioMime", part.audioMime ?: "audio/mpeg")
          }
          part.audioUrl != null -> {
            put("audioUrl", part.audioUrl)
            put("audioMime", part.audioMime ?: "audio/mpeg")
          }
          part.audioBase64 != null -> {
            put("audioBase64", part.audioBase64)
            put("audioMime", part.audioMime ?: "audio/mpeg")
          }
        }
      }
      reply(app, nodeId, PATH_CHAT_REPLY, msg.toString())
      val kind = if (part.isFinal) "final" else "interim"
      val audioTag = when {
        part.audioAssetRef != null -> " +asset"
        part.audioUrl != null -> " +url"
        part.audioBase64 != null -> " +b64"
        else -> ""
      }
      WearRelayLog.info("chat", "$agentId → $kind ${part.text.length}ch$audioTag")
    }

    if (error != null) {
      reply(app, nodeId, PATH_CHAT_REPLY, JSONObject().apply {
        put("agentId", agentId)
        put("seq", seq)
        put("final", true)
        put("error", error)
      }.toString())
      WearRelayLog.error("chat", error)
    }
  }

  private suspend fun reply(app: NodeApp, nodeId: String, path: String, data: String) {
    val payload = clampToDataLayerCap(path, data)
    try {
      Wearable.getMessageClient(app).sendMessage(nodeId, path, payload.toByteArray(Charsets.UTF_8)).await()
      WearRelayLog.outgoing(tagFor(path), "${shortNode(nodeId)} · ${payload.length}B")
    } catch (e: Throwable) {
      Log.e(TAG, "reply failed $path", e)
      WearRelayLog.error(tagFor(path), "send failed: ${e.message ?: "unknown"}")
    }
  }

  /**
   * The Wearable Data Layer hard-caps `MessageClient.sendMessage` payloads at
   * ~100 KB. ElevenLabs audio pushes past this once base64-encoded, which is
   * why big final replies used to vanish and the watch would hit its 150s
   * timeout. If we're above the cap, strip the audio so at least the text
   * lands — the watch will speak it locally.
   */
  private fun clampToDataLayerCap(path: String, data: String): String {
    val size = data.toByteArray(Charsets.UTF_8).size
    if (size <= DATA_LAYER_MSG_CAP_BYTES) return data

    // Chat reply: strip audio (watch falls back to local TTS).
    if (path == PATH_CHAT_REPLY) {
      return try {
        val obj = JSONObject(data)
        if (!obj.has("audioBase64")) {
          WearRelayLog.warn("chat", "reply ${size / 1000}KB > cap, no audio to strip")
          return data
        }
        obj.remove("audioBase64")
        obj.remove("audioMime")
        obj.put("audioStripped", true)
        WearRelayLog.warn("chat", "audio ${size / 1000}KB > cap — text-only, local TTS")
        obj.toString()
      } catch (_: Throwable) { data }
    }

    // Agents list: strip avatars (watch falls back to default icon).
    // Data Layer will silently drop the whole message otherwise.
    if (path == PATH_AGENTS_RESULT) {
      return try {
        val root = JSONObject(data)
        val agents = root.optJSONArray("agents") ?: return data
        var stripped = 0
        for (i in 0 until agents.length()) {
          val identity = agents.optJSONObject(i)?.optJSONObject("identity") ?: continue
          if (identity.has("avatar") || identity.has("avatarUrl")) {
            identity.remove("avatar")
            identity.remove("avatarUrl")
            stripped++
          }
        }
        if (stripped > 0) {
          WearRelayLog.warn("agents", "payload ${size / 1000}KB > cap — stripped $stripped avatar(s)")
        }
        root.toString()
      } catch (_: Throwable) { data }
    }

    WearRelayLog.warn(tagFor(path), "payload ${size / 1000}KB > cap — sending anyway")
    return data
  }

  private fun tagFor(path: String): String = when (path) {
    PATH_PING, PATH_STATUS -> "ping"
    PATH_AGENTS, PATH_AGENTS_RESULT -> "agents"
    PATH_CHAT, PATH_CHAT_STATE, PATH_CHAT_REPLY -> "chat"
    PATH_ERROR -> "error"
    else -> "relay"
  }

  private fun preview(text: String): String {
    val cleaned = text.trim().replace(Regex("\\s+"), " ")
    return if (cleaned.length <= 32) "\"$cleaned\"" else "\"${cleaned.take(32)}…\""
  }

  companion object {
    private const val TAG = "WearRelay"
    private const val MAX_AVATAR_BYTES = 1_000_000L
    private const val DATA_LAYER_MSG_CAP_BYTES = 90_000
    const val PATH_PING = "/openclaw/ping"
    const val PATH_STATUS = "/openclaw/status"
    const val PATH_AGENTS = "/openclaw/agents"
    const val PATH_AGENTS_RESULT = "/openclaw/agents/result"
    const val PATH_CHAT = "/openclaw/chat"
    const val PATH_CHAT_STATE = "/openclaw/chat/state"
    const val PATH_CHAT_REPLY = "/openclaw/chat/reply"
    const val PATH_ERROR = "/openclaw/error"
  }
}

object WearRelayScope : CoroutineScope {
  override val coroutineContext = SupervisorJob() + Dispatchers.IO
}
