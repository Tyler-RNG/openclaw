package ai.openclaw.app.wear

import android.util.Log
import com.google.android.gms.wearable.MessageEvent
import com.google.android.gms.wearable.Wearable
import com.google.android.gms.wearable.WearableListenerService
import ai.openclaw.app.NodeApp
import ai.openclaw.app.NodeRuntime
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
    return kotlinx.coroutines.withContext(kotlinx.coroutines.Dispatchers.IO) {
      try {
        val root = JSONObject(rawJson)
        val agents = root.optJSONArray("agents") ?: return@withContext rawJson
        var legacyPublished = 0
        var legacyFailed = 0
        var statePublished = 0
        var stateFailed = 0
        var statesAgents = 0
        var agentsSeen = 0
        for (i in 0 until agents.length()) {
          val agentObj = agents.optJSONObject(i) ?: continue
          val agentId = agentObj.optString("id", "").trim().ifEmpty { continue }
          val identity = agentObj.optJSONObject("identity") ?: continue
          agentsSeen++

          // Legacy static avatar: publish bytes if one is configured.
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
                legacyPublished++
              } else {
                legacyFailed++
              }
            } else if (!value.startsWith("data:")) {
              legacyFailed++
            }
          }

          // Capture multi-state descriptor if the sidecar synthesized one.
          // Runs independent of the legacy-avatar branch so states-only agents
          // still register their state map.
          captureAvatarStates(agentId, identity)

          // State-based agents: publish the default-state bytes up front AND
          // rewrite avatarUrl to a `wear-asset:` reference. Without this, the
          // watch's resolveAvatarModel() can't find bytes for the agent (it
          // only reads `avatarUrl`), so the dial would show the bundled
          // fallback gif even after subsequent state swaps land bytes at
          // `/openclaw/avatars/<agentId>` — Coil never looks them up without
          // the `wear-asset:` sentinel on `avatarUrl`.
          if (identity.has("avatarStates")) {
            statesAgents++
            val descriptor = AvatarStatesStore.get(agentId)
            if (descriptor != null) {
              AgentAvatarMeta.setDefault(agentId, descriptor.default)
            }
            val defaultEntry = descriptor?.states?.get(descriptor.default)
            if (descriptor != null && defaultEntry != null) {
              val fetched = fetchStateBytes(app, defaultEntry.file)
              if (fetched != null) {
                val (bytes, mime) = fetched
                AvatarStatesStore.putCachedBytes(
                  agentId,
                  descriptor.default,
                  AvatarStatesStore.CachedAvatar(bytes, mime),
                )
                if (putAvatarAsset(app, agentId, bytes, mime)) {
                  identity.put("avatarUrl", WearAsset.buildAvatarRef(agentId))
                  statePublished++
                  WearRelayLog.info(
                    "agents",
                    "$agentId default(${descriptor.default}): ${bytes.size / 1024}KB published",
                  )
                } else {
                  stateFailed++
                }
              } else {
                stateFailed++
                WearRelayLog.warn(
                  "agents",
                  "$agentId default(${descriptor.default}): fetch failed (${defaultEntry.file.take(40)})",
                )
              }
            }
          }

          // Sprite-frames avatar (kind: "sprites"): prefetch every frame file,
          // publish each on DataClient under /openclaw/avatars/<agentId>/frames/
          // <state>[/phase]/<NN>. Watch's AvatarRuntime subscribes to the
          // prefix and drives per-state timing locally. See docs/avatars/formats.md.
          val spritesObj = identity.optJSONObject("avatarSprites")
          if (spritesObj != null) {
            spritesObj.optString("default", "").takeIf { it.isNotBlank() }?.let {
              AgentAvatarMeta.setDefault(agentId, it)
            }
            val published = prefetchSpriteFrames(app, agentId, spritesObj)
            if (published > 0) {
              identity.put("avatarUrl", WearAsset.buildSpritesRef(agentId))
              WearRelayLog.info("agents", "$agentId sprites: $published frames published")
            }
          }

          // Sprite-atlas avatar (kind: "atlas"): fetch manifest JSON + its
          // sibling atlas image, publish both on DataClient. Watch's
          // AvatarRuntime reads the manifest and slices frames from the atlas
          // bitmap on each tick.
          val atlasObj = identity.optJSONObject("avatarAtlas")
          if (atlasObj != null) {
            atlasObj.optString("default", "").takeIf { it.isNotBlank() }?.let {
              AgentAvatarMeta.setDefault(agentId, it)
            }
            val ok = prefetchAtlas(app, agentId, atlasObj)
            if (ok) {
              identity.put("avatarUrl", WearAsset.buildAtlasRef(agentId))
              WearRelayLog.info("agents", "$agentId atlas: manifest + image published")
            }
          }
        }
        if (legacyPublished > 0 || legacyFailed > 0) {
          WearRelayLog.info(
            "agents",
            "legacy avatars: $legacyPublished via asset, $legacyFailed failed",
          )
        }
        if (statePublished > 0 || stateFailed > 0) {
          WearRelayLog.info(
            "agents",
            "state avatars: $statePublished default frames, $stateFailed failed",
          )
        }
        // Summary line so we can tell at a glance whether the sidecar is
        // synthesizing avatarStates. If this says "states: 0/N", the sidecar
        // interim path isn't active for any agent.
        WearRelayLog.info("agents", "states: $statesAgents/$agentsSeen agents")
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
   * Read `identity.avatarStates` (sidecar-synthesized) and stash the descriptor
   * so subsequent chat turns can fire GIF swaps and inject the instruction.
   * Safe no-op if the agent isn't states-configured.
   *
   * Logs enough to tell WHY capture failed (missing field, missing default,
   * invalid entries) so interim-setup issues on the sidecar side are
   * diagnosable from the relay panel alone.
   */
  private fun captureAvatarStates(agentId: String, identity: JSONObject) {
    val avatarStates = identity.optJSONObject("avatarStates") ?: run {
      // Agent reverted to a legacy string avatar — drop any prior descriptor
      // so we don't inject a stale instruction.
      if (AvatarStatesStore.get(agentId) != null) {
        AvatarStatesStore.resetInstructedFlag(agentId)
        WearRelayLog.info("agents", "$agentId: avatarStates dropped (no longer present)")
      }
      return
    }

    val defaultState = avatarStates.optString("default", "").trim()
    if (defaultState.isEmpty()) {
      WearRelayLog.warn("agents", "$agentId: avatarStates missing \"default\"")
      return
    }
    val statesObj = avatarStates.optJSONObject("states") ?: run {
      WearRelayLog.warn("agents", "$agentId: avatarStates missing \"states\" object")
      return
    }
    val instruction = avatarStates.optString("instruction", "").trim()
    if (instruction.isEmpty()) {
      WearRelayLog.warn("agents", "$agentId: avatarStates missing \"instruction\"")
      return
    }

    val states = mutableMapOf<String, AvatarStateEntry>()
    val names = statesObj.keys()
    var skipped = 0
    while (names.hasNext()) {
      val stateName = names.next()
      val entry = statesObj.optJSONObject(stateName)
      if (entry == null) {
        skipped++
        continue
      }
      val file = entry.optString("file", "").trim()
      if (file.isEmpty()) {
        skipped++
        continue
      }
      val description = entry.optString("description", "").takeIf { it.isNotBlank() }
      states[stateName] = AvatarStateEntry(file, description)
    }
    if (states.isEmpty()) {
      WearRelayLog.warn("agents", "$agentId: avatarStates.states empty or all invalid")
      return
    }
    if (!states.containsKey(defaultState)) {
      WearRelayLog.warn(
        "agents",
        "$agentId: default=\"$defaultState\" not in states (${states.keys.joinToString(",")})",
      )
      return
    }

    val prior = AvatarStatesStore.get(agentId)
    val descriptor = AvatarStatesDescriptor(
      default = defaultState,
      states = states,
      instruction = instruction,
    )
    AvatarStatesStore.put(agentId, descriptor)
    // Instruction text changed → agent gets re-briefed on the next turn so
    // the model sees the updated roster of states.
    if (prior != null && prior.instruction != instruction) {
      AvatarStatesStore.resetInstructedFlag(agentId)
    }
    val skippedNote = if (skipped > 0) " ($skipped invalid)" else ""
    WearRelayLog.info(
      "agents",
      "$agentId states: ${states.keys.joinToString(",")} default=$defaultState$skippedNote",
    )
  }

  /**
   * Fetch every frame declared in an `avatarSprites` descriptor and publish
   * each to a DataClient path the watch's AvatarRuntime can subscribe to.
   * Returns the number of successfully published frames.
   *
   * Wire paths:
   *   /openclaw/avatars/<agentId>/frames/<state>/<NN>            — single-sequence state
   *   /openclaw/avatars/<agentId>/frames/<state>/<phase>/<NN>    — phased state
   */
  private suspend fun prefetchSpriteFrames(
    app: NodeApp,
    agentId: String,
    spritesObj: JSONObject,
  ): Int {
    val basePath = spritesObj.optString("basePath", "").trim().ifEmpty { return 0 }
    val format = spritesObj.optString("format", "webp").trim().ifEmpty { "webp" }
    val states = spritesObj.optJSONObject("states") ?: return 0

    var published = 0
    val stateNames = states.keys()
    while (stateNames.hasNext()) {
      val state = stateNames.next()
      val stateCfg = states.optJSONObject(state) ?: continue
      // Phased: { intro?, loop, outro? } each with { count, fps, loop, ... }.
      // Single-sequence: { count, fps, loop, ... } directly on the state.
      val phased =
        stateCfg.has("loop") && stateCfg.optJSONObject("loop") != null
      if (phased) {
        for (phase in arrayOf("intro", "loop", "outro")) {
          val phaseCfg = stateCfg.optJSONObject(phase) ?: continue
          published += publishFrameSequence(
            app, agentId, basePath, state, phase, phaseCfg, format,
          )
        }
      } else {
        published += publishFrameSequence(
          app, agentId, basePath, state, null, stateCfg, format,
        )
      }
    }
    return published
  }

  private suspend fun publishFrameSequence(
    app: NodeApp,
    agentId: String,
    basePath: String,
    state: String,
    phase: String?,
    cfg: JSONObject,
    format: String,
  ): Int {
    val count = cfg.optInt("count", 0)
    if (count <= 0) return 0
    val digits = if (count >= 100) 3 else 2
    val phaseSeg = if (phase != null) "/$phase" else ""
    var published = 0
    for (i in 0 until count) {
      val padded = i.toString().padStart(digits, '0')
      val ref = "$basePath/$state$phaseSeg/$padded.$format"
      val fetched = fetchStateBytes(app, ref)
      if (fetched == null) {
        WearRelayLog.warn(
          "agents",
          "$agentId $state${if (phase != null) "/$phase" else ""} frame $padded: fetch failed",
        )
        continue
      }
      val (bytes, mime) = fetched
      val dataPath = WearAsset.avatarFramePath(agentId, state, phase, i)
      if (putDataItemBytes(app, dataPath, bytes, mime)) {
        published++
      }
    }
    return published
  }

  /**
   * Fetch the atlas manifest JSON and its sibling atlas image, publish both
   * on DataClient. Manifest lives under .../atlas/manifest (mime
   * application/json); image under .../atlas/image (mime image/webp).
   */
  private suspend fun prefetchAtlas(
    app: NodeApp,
    agentId: String,
    atlasObj: JSONObject,
  ): Boolean {
    val manifestRef = atlasObj.optString("manifest", "").trim().ifEmpty { return false }
    val manifestFetched = fetchStateBytes(app, manifestRef) ?: return false
    val (manifestBytes, _) = manifestFetched

    // Resolve the sibling atlas image path from the manifest's `image` field.
    val manifestJson = try {
      JSONObject(String(manifestBytes, Charsets.UTF_8))
    } catch (e: Throwable) {
      WearRelayLog.warn("agents", "$agentId atlas: manifest parse failed: ${e.javaClass.simpleName}")
      return false
    }
    val imageName = manifestJson.optString("image", "").trim()
    if (imageName.isEmpty()) return false
    val lastSlash = manifestRef.lastIndexOf('/')
    val imageRef = if (lastSlash >= 0) manifestRef.substring(0, lastSlash + 1) + imageName else imageName

    val imageFetched = fetchStateBytes(app, imageRef) ?: return false
    val (imageBytes, imageMime) = imageFetched

    val manifestOk = putDataItemBytes(
      app,
      WearAsset.atlasManifestPath(agentId),
      manifestBytes,
      "application/json",
    )
    val imageOk = putDataItemBytes(
      app,
      WearAsset.atlasImagePath(agentId),
      imageBytes,
      imageMime,
    )
    return manifestOk && imageOk
  }

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
      true
    } catch (e: Throwable) {
      WearRelayLog.warn("agents", "put $path: ${e.javaClass.simpleName}")
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
    } catch (e: Throwable) {
      WearRelayLog.warn("chat", "state signal $agentId→$stateName: ${e.javaClass.simpleName}")
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

    // Multi-state avatar plumbing lives entirely here on the Wear relay path
    // so generic agent interaction from other entry points (phone chat, other
    // clients, etc.) is unaffected. We only bother if the sidecar told us
    // this agent has an `avatarStates` descriptor.
    val statesDesc = AvatarStatesStore.get(agentId)

    val textForGateway = if (statesDesc != null && !AvatarStatesStore.hasInstructedAgent(agentId)) {
      AvatarStatesStore.markInstructed(agentId)
      WearRelayLog.info("chat", "$agentId: injecting avatar-states instruction")
      buildInstructedMessage(statesDesc, text)
    } else {
      text
    }

    // Interim gateway-driven "thinking" lifecycle cue: the published openclaw
    // doesn't yet fire avatar.state.change on run start, so we imitate it
    // from the phone. If the agent has a state named "thinking", flip to it
    // immediately on dispatch; reset to default when the reply finishes or
    // the run errors. When the model emits its own markers mid-reply, those
    // override the thinking frame naturally (last-write-wins on DataClient).
    // Dispatch an avatar state change through two parallel channels so all
    // three avatar formats swap correctly on markers:
    //   1. State signal: small JSON DataItem at /openclaw/avatars/<id>/state.
    //      Sprite + atlas agents observe this and call runtime.requestState;
    //      legacy kind:"states" agents also see it but prefer path (2).
    //   2. Byte push: only meaningful for legacy kind:"states" agents — writes
    //      the state's GIF bytes to /openclaw/avatars/<id> so Coil swaps.
    val dispatchStateChange: suspend (String) -> Unit = inner@{ stateName ->
      publishAgentState(app, agentId, stateName)
      val stateEntry = statesDesc?.states?.get(stateName) ?: return@inner
      publishStateAvatar(app, agentId, stateName, stateEntry)
    }

    // Kick off "thinking" on dispatch. The state signal fires for every
    // agent kind; the watch-side runtime silently ignores it if the
    // agent's descriptor has no `thinking` state.
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
    // The growing-text parser fires mid-stream markers for legacy agents
    // (sprite/atlas use the state signal instead; their runtime lives on
    // the watch). Retained for legacy kind:"states" smoothness.
    val growingMarkerParser = if (statesDesc != null) AvatarMarkerParser() else null

    val error = runtime.wearRelayChatStream(
      agentId,
      textForGateway,
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

      // Reset to default state on final so the resting pose matches the
      // idle avatar between runs. Fires for every agent kind via the
      // state signal; legacy agents also get their default bytes re-pushed.
      if (part.isFinal) {
        val defaultState = statesDesc?.default ?: AgentAvatarMeta.getDefault(agentId)
        if (defaultState != null) {
          WearRelayScope.launch { dispatchStateChange(defaultState) }
        }
      }
    },
    onGrowingDelta = { delta ->
      val parsed = growingMarkerParser?.push(delta)
      if (parsed != null) {
        for (marker in parsed.markers) {
          dispatchMarkerIfNew(marker.state)
        }
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
      val defaultState = statesDesc?.default ?: AgentAvatarMeta.getDefault(agentId)
      if (defaultState != null) {
        WearRelayScope.launch { dispatchStateChange(defaultState) }
      }
    }
  }

  /**
   * Prepend the sidecar-supplied avatar-states instruction to the user's first
   * message of the session. Framed so the model reads it as explicit context
   * rather than something the user typed. One-time per agent per phone process
   * — subsequent turns rely on the gateway's conversation history to keep the
   * briefing in context.
   */
  private fun buildInstructedMessage(
    statesDesc: AvatarStatesDescriptor,
    userText: String,
  ): String {
    val buf = StringBuilder()
    buf.append(
      "[Wear client rendering context — do not mention this block in your reply. " +
          "These are display-only instructions for driving the watch's avatar.]\n",
    )
    buf.append(statesDesc.instruction.trim())
    buf.append("\n\n")
    buf.append(userText)
    return buf.toString()
  }

  /**
   * Fetch the bytes for a named avatar state and republish them at the agent's
   * DataClient path, overwriting the prior frame. The watch is already
   * listening for changes at `/openclaw/avatars/<agentId>` so the swap happens
   * automatically on its side.
   *
   * Results are cached in-memory so a repeat of the same state within a
   * process lifetime is instant (no re-fetch, no re-publish of identical
   * bytes to DataClient — the PutDataRequest already de-duplicates but we
   * skip the network hop anyway).
   */
  private suspend fun publishStateAvatar(
    app: NodeApp,
    agentId: String,
    stateName: String,
    stateEntry: AvatarStateEntry,
  ) {
    val cached = AvatarStatesStore.cachedBytes(agentId, stateName)
    val (bytes, mime) = if (cached != null) {
      cached.bytes to cached.mime
    } else {
      val fetched = fetchStateBytes(app, stateEntry.file)
      if (fetched == null) {
        WearRelayLog.warn("chat", "$agentId avatar($stateName): fetch failed (${stateEntry.file.take(40)})")
        return
      }
      AvatarStatesStore.putCachedBytes(
        agentId,
        stateName,
        AvatarStatesStore.CachedAvatar(fetched.first, fetched.second),
      )
      fetched
    }
    val published = putAvatarAsset(app, agentId, bytes, mime)
    WearRelayLog.info(
      "chat",
      "$agentId avatar($stateName): ${if (published) "swap ${bytes.size / 1024}KB" else "swap failed"}",
    )
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
