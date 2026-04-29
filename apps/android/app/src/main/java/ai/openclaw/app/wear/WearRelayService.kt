package ai.openclaw.app.wear

import android.util.Log
import com.google.android.gms.wearable.MessageEvent
import com.google.android.gms.wearable.Wearable
import com.google.android.gms.wearable.WearableListenerService
import ai.openclaw.app.NodeApp
import ai.openclaw.app.NodeRuntime
import ai.openclaw.spritecore.client.AvatarMarkerParser
import ai.openclaw.app.diag.PhoneDeepLog
import ai.openclaw.app.protocol.WearAsset
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
    PhoneDeepLog.incoming(tagFor(path), "$path ${data.length}B from $short :: ${data.take(160)}")

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
    // After the agent-list reply returns, publish each agent's
    // CharacterManifest + asset bytes asynchronously. The watch's
    // CharacterAvatar composable prefers this path; legacy sprite/atlas
    // paths still fire from rewriteAvatars() so rendering keeps working
    // even if the gateway RPC errors or a single ref fails to fetch.
    publishCharacterManifests(app, transformed)
  }

  /**
   * For each agent in [agentsListJson], call the gateway's
   * node.getCharacterManifest RPC, publish the manifest envelope as a text
   * DataItem at `/openclaw/avatars/<id>/character-manifest`, then publish
   * each referenced asset's bytes at
   * `/openclaw/avatars/<id>/character-assets/<refKey>`. Agents without a
   * structured avatar (plain URL, no avatar, unsupported kind) are skipped
   * silently — the RPC returns an error the watch can't act on anyway.
   *
   * All work is best-effort; a failure on one agent or one ref does not
   * abort the loop. Counters log a per-refresh summary so operators can
   * tell at a glance whether the new path is wired.
   */
  private suspend fun publishCharacterManifests(app: NodeApp, agentsListJson: String) {
    val runtime = app.peekRuntime() ?: return
    val root = try { JSONObject(agentsListJson) } catch (_: Throwable) { return }
    val agentsArr = root.optJSONArray("agents") ?: return
    var manifests = 0
    var skipped = 0
    var assetsPublished = 0
    var assetsFailed = 0
    for (i in 0 until agentsArr.length()) {
      val obj = agentsArr.optJSONObject(i) ?: continue
      val agentId = obj.optString("id", "").trim().ifEmpty { continue }
      val envelopeJson = runtime.wearRelayCharacterManifest(agentId)
      if (envelopeJson == null) {
        skipped++
        continue
      }
      if (!publishCharacterManifestEnvelope(app, agentId, envelopeJson)) {
        skipped++
        continue
      }
      manifests++
      val refs = parseManifestAssetRefs(envelopeJson)
      for ((refKey, relPath) in refs) {
        val bytes = runtime.wearRelayAssetBytes(relPath)
        if (bytes == null) {
          assetsFailed++
          continue
        }
        if (putDataItemBytes(app, WearAsset.characterManifestAssetPath(agentId, refKey), bytes, guessMimeFromUrl(relPath))) {
          assetsPublished++
        } else {
          assetsFailed++
        }
      }
    }
    WearRelayLog.info(
      "agents",
      "manifests=$manifests skip=$skipped assets=$assetsPublished fail=$assetsFailed",
    )
  }

  /** Publish the {manifest, revision} JSON envelope as a text DataItem. */
  private suspend fun publishCharacterManifestEnvelope(
    app: NodeApp,
    agentId: String,
    envelopeJson: String,
  ): Boolean {
    return try {
      val request = PutDataMapRequest.create(WearAsset.characterManifestPath(agentId)).apply {
        dataMap.putString("manifest", envelopeJson)
        dataMap.putLong("ts", System.currentTimeMillis())
      }.asPutDataRequest().setUrgent()
      com.google.android.gms.wearable.Wearable.getDataClient(app).putDataItem(request).await()
      true
    } catch (e: Throwable) {
      WearRelayLog.warn("agents", "manifest put $agentId: ${e.javaClass.simpleName}")
      false
    }
  }

  /** Extract `manifest.assets.refs` as `{refKey → relativePath}` from the envelope JSON. */
  private fun parseManifestAssetRefs(envelopeJson: String): List<Pair<String, String>> {
    return try {
      val root = JSONObject(envelopeJson)
      val manifest = root.optJSONObject("manifest") ?: return emptyList()
      val assets = manifest.optJSONObject("assets") ?: return emptyList()
      val refs = assets.optJSONObject("refs") ?: return emptyList()
      val out = mutableListOf<Pair<String, String>>()
      val it = refs.keys()
      while (it.hasNext()) {
        val k = it.next()
        val v = refs.optString(k, "").takeIf { it.isNotBlank() } ?: continue
        out.add(k to v)
      }
      out
    } catch (_: Throwable) {
      emptyList()
    }
  }

  /**
   * Rewrites each agent's plain-URL avatar to a `wear-asset:avatar:<agentId>`
   * reference and publishes the bytes as a DataClient Asset at
   * `/openclaw/avatars/<agentId>`. Only runs for agents whose `identity.avatar`
   * / `identity.avatarUrl` is a static URL — structured (atlas) avatars flow
   * through [publishCharacterManifests] instead, which already writes the
   * atlas image + manifest envelope to the character-manifest paths.
   */
  private suspend fun rewriteAvatars(rawJson: String, app: NodeApp): String {
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
            else -> null
          }
          if (key != null) {
            val value = identity.optString(key)
            val fetched = fetchStateBytes(app, value)
            if (fetched != null) {
              val (bytes, mime) = fetched
              if (putAvatarAsset(app, agentId, bytes, mime)) {
                identity.put(key, WearAsset.buildAvatarRef(agentId))
                published++
              } else {
                failed++
              }
            } else if (!value.startsWith("data:")) {
              failed++
            }
          }
        }
        if (published > 0 || failed > 0) {
          WearRelayLog.info("agents", "plain avatars: $published via asset, $failed failed")
        }
        root.toString()
      } catch (_: Throwable) {
        rawJson
      }
    }
  }

  /**
   * Resolve a state `file` reference (data URL, absolute path, http URL, or
   * gateway-relative path) into raw bytes + mime. Used both for initial
   * default-frame publish during agent list rewrite and mid-reply state swaps.
   * Returns null if the ref can't be resolved or the fetch failed; callers log
   * the outcome in their own context.
   */
  private suspend fun fetchStateBytes(app: NodeApp, ref: String): Pair<ByteArray, String>? {
    val dataPlane = app.peekRuntime()?.wearRelayDataPlane()
    val token = app.peekRuntime()?.wearRelayAuthToken()
    return kotlinx.coroutines.withContext(kotlinx.coroutines.Dispatchers.IO) {
      when {
        ref.startsWith("data:") -> null
        ref.startsWith("file://") || ref.startsWith("/") -> localPathToBytes(ref)
        ref.startsWith("http://") || ref.startsWith("https://") -> fetchUrlAsBytes(ref)
        dataPlane != null -> buildDataPlaneAssetUrl(dataPlane, token, ref)?.let { fetchUrlAsBytes(it) }
        else -> null
      }
    }
  }

  /**
   * Read `identity.avatarStates` (emitted by the gateway from plugin config)
   * and stash the descriptor so subsequent chat turns can fire GIF swaps and
   * inject the instruction. Safe no-op if the agent isn't states-configured.
   *
   * Logs enough to tell WHY capture failed (missing field, missing default,
   * invalid entries) so setup issues are diagnosable from the relay panel
   * alone.
   */
  /**
   * Publish arbitrary bytes at an arbitrary DataClient path as a DataMap
   * containing { data: Asset, mime, ts }. Same schema as putAvatarAsset but
   * without the hard-coded /openclaw/avatars/<agentId> path so we can stamp
   * sprite frames and atlas pieces at their own subpaths.
   */
  private suspend fun putDataItemBytes(
    app: NodeApp,
    path: String,
    bytes: ByteArray,
    mime: String,
  ): Boolean {
    return try {
      val asset = Asset.createFromBytes(bytes)
      val request = PutDataMapRequest.create(path).apply {
        dataMap.putAsset("data", asset)
        dataMap.putString("mime", mime)
        dataMap.putLong("ts", System.currentTimeMillis())
      }.asPutDataRequest().setUrgent()
      com.google.android.gms.wearable.Wearable.getDataClient(app).putDataItem(request).await()
      PhoneDeepLog.outgoing("data", "putDataItem $path ${bytes.size}B mime=$mime")
      true
    } catch (e: Throwable) {
      WearRelayLog.warn("agents", "put $path: ${e.javaClass.simpleName}")
      PhoneDeepLog.error("data", "putDataItem $path failed: ${e.javaClass.simpleName}")
      false
    }
  }

  /**
   * Publishes a {state, ts} DataItem so the watch can drive AvatarRuntime
   * state swaps without needing per-state bytes on the wire. Fires for
   * every agent kind (sprite/atlas consume this; legacy also gets a signal
   * but continues to swap via the separate byte-push path).
   */
  private suspend fun publishAgentState(app: NodeApp, agentId: String, stateName: String) {
    try {
      val request = PutDataMapRequest.create(WearAsset.avatarStatePath(agentId)).apply {
        dataMap.putString("state", stateName)
        dataMap.putLong("ts", System.currentTimeMillis())
      }.asPutDataRequest().setUrgent()
      com.google.android.gms.wearable.Wearable.getDataClient(app).putDataItem(request).await()
      PhoneDeepLog.outgoing("state", "${WearAsset.avatarStatePath(agentId)} → $stateName")
    } catch (e: Throwable) {
      WearRelayLog.warn("chat", "state signal $agentId→$stateName: ${e.javaClass.simpleName}")
      PhoneDeepLog.error("state", "$agentId→$stateName: ${e.javaClass.simpleName}")
    }
  }

  /** Publishes the avatar bytes as a DataClient Asset. Returns true on success. */
  private suspend fun putAvatarAsset(app: NodeApp, agentId: String, bytes: ByteArray, mime: String): Boolean {
    return try {
      val asset = Asset.createFromBytes(bytes)
      val request = PutDataMapRequest.create(WearAsset.avatarDataPath(agentId)).apply {
        dataMap.putAsset("data", asset)
        dataMap.putString("mime", mime)
        dataMap.putLong("ts", System.currentTimeMillis())
      }.asPutDataRequest().setUrgent()
      com.google.android.gms.wearable.Wearable.getDataClient(app).putDataItem(request).await()
      PhoneDeepLog.outgoing("avatar", "${WearAsset.avatarDataPath(agentId)} ${bytes.size}B mime=$mime")
      true
    } catch (e: Throwable) {
      WearRelayLog.warn("agents", "asset put $agentId: ${e.javaClass.simpleName}")
      PhoneDeepLog.error("avatar", "$agentId: ${e.javaClass.simpleName}")
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
    val base = "${dataPlane.baseUrl}/openclaw-assets/$encoded"
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

    // "Thinking" lifecycle cue: the gateway doesn't fire an avatar state
    // change on run start, so we imitate it from the phone. If the agent's
    // manifest declares a `thinking` state, DisplayKit swaps to it on
    // dispatch; otherwise the watch's SpriteAnimationPlayer no-ops. Model
    // markers emitted mid-reply override the thinking frame naturally
    // (last-write-wins on the state signal).
    val dispatchStateChange: suspend (String) -> Unit = { stateName ->
      publishAgentState(app, agentId, stateName)
      // Mirror the swap into the phone's own AgentAvatarSource cache so the
      // phone's AgentDialScreen re-ticks its player without needing a
      // DataClient round-trip on the same device.
      app.peekRuntime()?.agentAvatarSource?.setAgentState(agentId, stateName)
    }

    WearRelayLog.info("chat", "$agentId avatar: thinking (dispatch)")
    WearRelayScope.launch { dispatchStateChange("thinking") }

    var seq = 0

    // Per-turn marker de-dupe. Markers arriving mid-stream via onGrowingDelta
    // and then again on the final part must only dispatch once per turn. The
    // growing-text parser is stateful so it naturally doesn't re-emit markers
    // across deltas — the dedupe set protects against the final part's
    // (already-parsed-by-NodeRuntime) markers duplicating on top.
    val markersDispatched = mutableSetOf<String>()
    val dispatchMarkerIfNew: (String) -> Unit = dispatch@{ stateName ->
      if (!markersDispatched.add(stateName)) return@dispatch
      WearRelayScope.launch { dispatchStateChange(stateName) }
    }
    // Parse `[avatar:<state>]` markers as they stream in so state swaps
    // happen mid-reply rather than waiting for the final part. Runs for
    // every agent — the manifest determines whether the resulting state
    // signal actually matches a known animation.
    val growingMarkerParser = AvatarMarkerParser()

    val error = runtime.wearRelayChatStream(
      agentId,
      text,
      onPart = { part ->
      // NodeRuntime already stripped markers from `part.text` and handed
      // us the pre-parsed list on `avatarMarkers`. Re-parsing would find
      // nothing; use what's already there.
      for (stateName in part.avatarMarkers) {
        dispatchMarkerIfNew(stateName)
      }

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
        // Newer watches (Phase 5) read `audioSegments` and play per-emotion
        // audio in sequence. Older builds ignore the field and use the
        // top-level audio* fields above (which carry the first segment).
        val segments = part.audioSegments
        if (!segments.isNullOrEmpty() && segments.size > 1) {
          val arr = org.json.JSONArray()
          for (seg in segments) {
            val segObj = JSONObject().apply {
              put("text", seg.text)
              if (seg.emotion != null) put("emotion", seg.emotion)
              when {
                seg.audioAssetRef != null -> {
                  put("audioAssetRef", seg.audioAssetRef)
                  put("audioMime", seg.audioMime ?: "audio/mpeg")
                }
                seg.audioUrl != null -> {
                  put("audioUrl", seg.audioUrl)
                  put("audioMime", seg.audioMime ?: "audio/mpeg")
                }
                seg.audioBase64 != null -> {
                  put("audioBase64", seg.audioBase64)
                  put("audioMime", seg.audioMime ?: "audio/mpeg")
                }
              }
            }
            arr.put(segObj)
          }
          put("audioSegments", arr)
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

      // Reset to the agent's default state on the final part so the
      // resting pose matches the idle avatar between runs. Default comes
      // from the cached CharacterManifest on the phone's AgentAvatarSource
      // (which mirrors DisplayKit's AnimationGraph.fromManifest logic).
      if (part.isFinal) {
        val defaultState = app.peekRuntime()?.agentAvatarSource?.defaultStateFor(agentId)
        if (defaultState != null) {
          WearRelayScope.launch { dispatchStateChange(defaultState) }
        }
      }
    },
    onGrowingDelta = { delta ->
      val parsed = growingMarkerParser.push(delta)
      for (marker in parsed.markers) {
        dispatchMarkerIfNew(marker.state)
      }
    },
    )

    if (error != null) {
      reply(app, nodeId, PATH_CHAT_REPLY, JSONObject().apply {
        put("agentId", agentId)
        put("seq", seq)
        put("final", true)
        put("error", error)
      }.toString())
      WearRelayLog.error("chat", error)
      // Reset avatar on error/timeout too — otherwise a stalled gateway
      // leaves the watch on the "thinking" frame forever.
      val defaultState = app.peekRuntime()?.agentAvatarSource?.defaultStateFor(agentId)
      if (defaultState != null) {
        WearRelayScope.launch { dispatchStateChange(defaultState) }
      }
    }
  }

  private suspend fun reply(app: NodeApp, nodeId: String, path: String, data: String) {
    val payload = clampToDataLayerCap(path, data)
    val clampedNote = if (payload.length != data.length) " (clamped from ${data.length}B)" else ""
    try {
      Wearable.getMessageClient(app).sendMessage(nodeId, path, payload.toByteArray(Charsets.UTF_8)).await()
      WearRelayLog.outgoing(tagFor(path), "${shortNode(nodeId)} · ${payload.length}B")
      PhoneDeepLog.outgoing(
        tagFor(path),
        "$path ${payload.length}B → ${shortNode(nodeId)}$clampedNote :: ${payload.take(160)}",
      )
    } catch (e: Throwable) {
      Log.e(TAG, "reply failed $path", e)
      WearRelayLog.error(tagFor(path), "send failed: ${e.message ?: "unknown"}")
      PhoneDeepLog.error(tagFor(path), "$path send failed: ${e.message ?: "unknown"}")
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

    // Chat reply: strip audio (watch falls back to local TTS). Handles
    // both the single-blob legacy fields and the per-emotion
    // `audioSegments` array introduced in Phase 4.
    if (path == PATH_CHAT_REPLY) {
      return try {
        val obj = JSONObject(data)
        val segments = obj.optJSONArray("audioSegments")
        val hadSegmentAudio = segments?.let {
          var any = false
          for (i in 0 until it.length()) {
            val seg = it.optJSONObject(i) ?: continue
            if (seg.has("audioBase64")) {
              seg.remove("audioBase64")
              seg.remove("audioMime")
              seg.put("audioStripped", true)
              any = true
            }
          }
          any
        } ?: false
        val hadTopAudio = obj.has("audioBase64")
        if (hadTopAudio) {
          obj.remove("audioBase64")
          obj.remove("audioMime")
          obj.put("audioStripped", true)
        }
        if (!hadSegmentAudio && !hadTopAudio) {
          WearRelayLog.warn("chat", "reply ${size / 1000}KB > cap, no audio to strip")
          return data
        }
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
