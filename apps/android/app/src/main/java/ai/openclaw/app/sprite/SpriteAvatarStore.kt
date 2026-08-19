package ai.openclaw.app.sprite

import ai.openclaw.spritecore.client.CharacterManifestEnvelope
import ai.openclaw.spritecore.client.CharacterManifestJson
import ai.openclaw.spritecore.client.characterManifestBytesReady
import java.net.HttpURLConnection
import java.net.URL
import java.net.URLEncoder
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.sync.Mutex
import kotlinx.coroutines.sync.withLock
import kotlinx.coroutines.withContext
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.JsonPrimitive
import kotlinx.serialization.json.booleanOrNull
import kotlinx.serialization.json.contentOrNull
import kotlinx.serialization.json.jsonObject
import kotlinx.serialization.json.jsonPrimitive

/**
 * Phone-side source of animated multi-state avatars.
 *
 * The SpriteCore gateway plugin (`extensions/sprite-core`) owns the data:
 * `node.getCharacterManifest` returns a ready-to-render manifest for an agent,
 * and the manifest's asset refs resolve against `GET /openclaw-assets/<path>`.
 * This store fetches both, caches the pair per agent, and tracks the agent's
 * current animation state so the renderer stays a pure function of that state.
 *
 * Everything here degrades to "no sprite": a missing plugin, an agent without
 * an atlas avatar, or a partial asset bundle all leave [bundles] without an
 * entry, and callers fall back to the static [ai.openclaw.app.ui.design.ClawAgentAvatar].
 */
class SpriteAvatarStore(
  /** Performs a gateway RPC, returning the payload JSON, or null when unavailable. */
  private val request: suspend (method: String, paramsJson: String?) -> String?,
  /** Supplies the operator auth token used for non-public asset reads. */
  private val authToken: suspend () -> String?,
) {
  /** A manifest plus the asset bytes it references. Only complete bundles are published. */
  data class Bundle(
    val envelope: CharacterManifestEnvelope,
    val assetBytes: Map<String, ByteArray>,
  )

  private val _bundles = MutableStateFlow<Map<String, Bundle>>(emptyMap())
  val bundles: StateFlow<Map<String, Bundle>> = _bundles.asStateFlow()

  private val _states = MutableStateFlow<Map<String, String>>(emptyMap())

  /** Current animation state per agent, driven by `avatar.state.change` events. */
  val states: StateFlow<Map<String, String>> = _states.asStateFlow()

  private val loadLock = Mutex()
  private val attempted = mutableSetOf<String>()

  @Volatile private var dataPlane: DataPlane? = null

  private data class DataPlane(val baseUrl: String, val publicAssets: Boolean)

  /**
   * Records a state change for [agentId]. Fed from the gateway's
   * `avatar.state.change` event, which the gateway emits after stripping
   * `[avatar:<state>]` markers out of assistant text.
   */
  fun onAvatarStateChange(agentId: String, state: String) {
    val trimmedId = agentId.trim()
    val trimmedState = state.trim()
    if (trimmedId.isEmpty() || trimmedState.isEmpty()) return
    _states.value = _states.value + (trimmedId to trimmedState)
  }

  /** Drops every cached bundle and state. Call on disconnect so a reconnect refetches. */
  suspend fun reset() {
    loadLock.withLock {
      attempted.clear()
      dataPlane = null
      _bundles.value = emptyMap()
      _states.value = emptyMap()
    }
  }

  /**
   * Ensures a bundle for [agentId] is loaded, fetching it once per connection.
   * Safe to call repeatedly from composition; failures are remembered so a
   * missing plugin does not produce an RPC per recomposition.
   */
  suspend fun ensureLoaded(agentId: String) {
    val id = agentId.trim()
    if (id.isEmpty()) return
    loadLock.withLock {
      if (id in attempted || _bundles.value.containsKey(id)) return
      attempted += id
    }
    val bundle = runCatching { loadBundle(id) }.getOrNull() ?: return
    _bundles.value = _bundles.value + (id to bundle)
  }

  private suspend fun loadBundle(agentId: String): Bundle? {
    val manifestJson =
      request("node.getCharacterManifest", """{"agentId":${quote(agentId)}}""") ?: return null
    val envelope = runCatching { CharacterManifestJson.parse(manifestJson) }.getOrNull() ?: return null

    val plane = resolveDataPlane() ?: return null
    val token = if (plane.publicAssets) null else authToken()
    val assets = mutableMapOf<String, ByteArray>()
    for ((refKey, relativePath) in envelope.manifest.assets.refs) {
      val bytes = fetchAsset(plane, relativePath, token) ?: continue
      assets[refKey] = bytes
    }
    // A half-downloaded bundle would render as missing frames, so publish only
    // when the SDK agrees every referenced asset is present.
    if (!characterManifestBytesReady(envelope, assets)) return null
    return Bundle(envelope = envelope, assetBytes = assets)
  }

  /** Reads the plugin's advertised asset base URL from `sprite-core.agents`. */
  private suspend fun resolveDataPlane(): DataPlane? {
    dataPlane?.let { return it }
    val payload = request("sprite-core.agents", null) ?: return null
    val root =
      runCatching { kotlinx.serialization.json.Json.parseToJsonElement(payload).jsonObject }
        .getOrNull() ?: return null
    val baseUrl =
      (root["publicBaseUrl"] as? JsonPrimitive)?.contentOrNull?.trim()?.trimEnd('/')
        ?.takeIf { it.isNotEmpty() } ?: return null
    val publicAssets = readPublicAssets(root)
    return DataPlane(baseUrl = baseUrl, publicAssets = publicAssets).also { dataPlane = it }
  }

  private fun readPublicAssets(root: JsonObject): Boolean =
    runCatching {
      (root["assets"] as? JsonObject)?.get("publicAssets")?.jsonPrimitive?.booleanOrNull == true
    }.getOrDefault(false)

  private suspend fun fetchAsset(
    plane: DataPlane,
    relativePath: String,
    token: String?,
  ): ByteArray? {
    val clean = relativePath.trimStart('/')
    if (clean.isEmpty()) return null
    val encoded =
      clean.split('/').joinToString("/") { segment ->
        URLEncoder.encode(segment, Charsets.UTF_8.name()).replace("+", "%20")
      }
    val url = "${plane.baseUrl}/openclaw-assets/$encoded"
    return withContext(Dispatchers.IO) {
      var conn: HttpURLConnection? = null
      try {
        conn = (URL(url).openConnection() as HttpURLConnection).apply {
          connectTimeout = 10_000
          readTimeout = 20_000
          requestMethod = "GET"
        }
        // The plugin's HTTP auth reads `Authorization: Bearer <token>` only;
        // query params are ignored, so a non-public asset 401s without this.
        if (!token.isNullOrEmpty()) {
          conn.setRequestProperty("Authorization", "Bearer $token")
        }
        conn.connect()
        if (conn.responseCode != HttpURLConnection.HTTP_OK) return@withContext null
        conn.inputStream.use { it.readBytes() }
      } catch (_: Throwable) {
        null
      } finally {
        conn?.disconnect()
      }
    }
  }

  private fun quote(value: String): String = JsonPrimitive(value).toString()
}
