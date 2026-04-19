package ai.openclaw.wear

import android.content.Context
import android.net.Uri
import android.util.Log
import com.google.android.gms.wearable.DataClient
import com.google.android.gms.wearable.DataEvent
import com.google.android.gms.wearable.DataItem
import com.google.android.gms.wearable.DataMapItem
import com.google.android.gms.wearable.Wearable
import ai.openclaw.wear.protocol.WearAsset
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.flow.update
import kotlinx.coroutines.launch
import kotlinx.coroutines.tasks.await
import kotlinx.coroutines.withTimeoutOrNull

/**
 * Watches Wearable DataClient for avatar + TTS assets published by the phone
 * relay. Assets are published under `/openclaw/avatars/<agentId>` and
 * `/openclaw/tts/<assetId>`. The raw bytes are cached in-memory and exposed
 * as StateFlows keyed by the id portion of the path.
 *
 * Because avatars are small and stable, we hold them in RAM for the session.
 * TTS bytes are evicted after playback to keep memory bounded.
 */
class WearAssetStore(private val context: Context) {

    private val dataClient: DataClient = Wearable.getDataClient(context)
    private val scope = CoroutineScope(SupervisorJob() + Dispatchers.IO)

    private val _avatars = MutableStateFlow<Map<String, ByteArray>>(emptyMap())
    val avatars: StateFlow<Map<String, ByteArray>> = _avatars.asStateFlow()

    // Per-agent cache-bust counter. Bumped ONLY when that agent's bytes
    // change — so another agent's DataClient arrival can't invalidate
    // this agent's Coil memory cache and force a GIF re-decode / restart
    // of the currently-playing animation. At startup when all 12 agents'
    // default frames arrive in quick succession, a single global counter
    // caused every visible page's image loader to restart 12 times in a
    // row, reading as stacked / flickering frames.
    private val _avatarVersions = MutableStateFlow<Map<String, Int>>(emptyMap())
    val avatarVersions: StateFlow<Map<String, Int>> = _avatarVersions.asStateFlow()

    // --- Sprite-frames shape (kind: "sprites") ---
    // Per-agent map of { "<state>[/<phase>]/<NN>" → bytes } assembled from
    // DataClient items arriving at `/openclaw/avatars/<id>/frames/...`.
    // Keys match the AvatarRuntime's expected frame-key format so the
    // SpriteFrameSource can look them up 1:1.
    private val _spriteFrames = MutableStateFlow<Map<String, Map<String, ByteArray>>>(emptyMap())
    val spriteFrames: StateFlow<Map<String, Map<String, ByteArray>>> = _spriteFrames.asStateFlow()

    // --- Atlas shape (kind: "atlas") ---
    // Per-agent atlas image bytes + parsed manifest JSON string. The watch's
    // AvatarRuntime parses the manifest to build AnimationDefinitions + the
    // frame rect map, then slices the decoded atlas bitmap per tick.
    private val _atlasImages = MutableStateFlow<Map<String, ByteArray>>(emptyMap())
    val atlasImages: StateFlow<Map<String, ByteArray>> = _atlasImages.asStateFlow()

    private val _atlasManifests = MutableStateFlow<Map<String, String>>(emptyMap())
    val atlasManifests: StateFlow<Map<String, String>> = _atlasManifests.asStateFlow()

    private val _tts = MutableStateFlow<Map<String, ByteArray>>(emptyMap())
    val tts: StateFlow<Map<String, ByteArray>> = _tts.asStateFlow()

    private val listener = DataClient.OnDataChangedListener { events ->
        for (event in events) {
            val path = event.dataItem.uri.path ?: continue
            when (event.type) {
                DataEvent.TYPE_CHANGED -> handleChanged(event.dataItem, path)
                DataEvent.TYPE_DELETED -> handleDeleted(path)
            }
        }
    }

    fun start() {
        dataClient.addListener(listener)
        // Pull any items that already exist on the Data Layer when we start up.
        scope.launch {
            try {
                val avatarUri = Uri.parse("wear://*${WearAsset.DATA_AVATAR_PATH}/")
                dataClient.getDataItems(avatarUri, DataClient.FILTER_PREFIX).await()
                    .forEach { item ->
                        val p = item.uri.path ?: return@forEach
                        handleChanged(item, p)
                    }
            } catch (e: Throwable) {
                Log.w(TAG, "initial avatar sweep failed", e)
            }
        }
    }

    fun stop() {
        dataClient.removeListener(listener)
    }

    /**
     * Wait for the TTS asset with [assetId] to land. Returns null if it
     * doesn't arrive within [timeoutMs]. Removes the cached bytes once
     * returned so the same asset isn't replayed on future events.
     */
    suspend fun awaitTts(assetId: String, timeoutMs: Long = 20_000): ByteArray? {
        val existing = _tts.value[assetId]
        if (existing != null) {
            _tts.update { it - assetId }
            return existing
        }
        val bytes = withTimeoutOrNull(timeoutMs) {
            tts.first { it.containsKey(assetId) }[assetId]
        }
        if (bytes != null) _tts.update { it - assetId }
        return bytes
    }

    private fun handleChanged(item: DataItem, path: String) {
        val dm = runCatching { DataMapItem.fromDataItem(item).dataMap }.getOrNull() ?: return
        val asset = dm.getAsset("data") ?: return
        scope.launch {
            try {
                val fd = dataClient.getFdForAsset(asset).await()
                val bytes = fd.inputStream.use { it.readBytes() }
                when {
                    // Sprite-frame path:
                    //   /openclaw/avatars/<agentId>/frames/<state>/<NN>
                    //   /openclaw/avatars/<agentId>/frames/<state>/<phase>/<NN>
                    path.matches(Regex("${Regex.escape(WearAsset.DATA_AVATAR_PATH)}/[^/]+/frames/.+")) -> {
                        val rest = path.removePrefix("${WearAsset.DATA_AVATAR_PATH}/")
                        val firstSlash = rest.indexOf('/')
                        val id = rest.substring(0, firstSlash)
                        val afterFrames = rest.substring(firstSlash + "/frames/".length)
                        val frameKey = spriteFrameKeyFromPath(afterFrames)
                        _spriteFrames.update { current ->
                            val per = current[id]?.toMutableMap() ?: mutableMapOf()
                            per[frameKey] = bytes
                            current + (id to per)
                        }
                        _avatarVersions.update { it + (id to ((it[id] ?: 0) + 1)) }
                        Log.d(TAG, "sprite $id $frameKey (${bytes.size}B)")
                    }
                    // Atlas image path.
                    path.matches(Regex("${Regex.escape(WearAsset.DATA_AVATAR_PATH)}/[^/]+/atlas/image")) -> {
                        val id = path.split("/")[3] // /openclaw/avatars/<id>/atlas/image
                        _atlasImages.update { it + (id to bytes) }
                        _avatarVersions.update { it + (id to ((it[id] ?: 0) + 1)) }
                        Log.d(TAG, "atlas image $id (${bytes.size}B)")
                    }
                    // Atlas manifest path (JSON text payload).
                    path.matches(Regex("${Regex.escape(WearAsset.DATA_AVATAR_PATH)}/[^/]+/atlas/manifest")) -> {
                        val id = path.split("/")[3]
                        val manifestJson = String(bytes, Charsets.UTF_8)
                        _atlasManifests.update { it + (id to manifestJson) }
                        _avatarVersions.update { it + (id to ((it[id] ?: 0) + 1)) }
                        Log.d(TAG, "atlas manifest $id (${bytes.size}B)")
                    }
                    path.startsWith("${WearAsset.DATA_AVATAR_PATH}/") -> {
                        val id = path.removePrefix("${WearAsset.DATA_AVATAR_PATH}/")
                        _avatars.update { it + (id to bytes) }
                        _avatarVersions.update { it + (id to ((it[id] ?: 0) + 1)) }
                        Log.d(TAG, "avatar $id loaded (${bytes.size}B)")
                    }
                    path.startsWith("${WearAsset.DATA_TTS_PATH}/") -> {
                        val id = path.removePrefix("${WearAsset.DATA_TTS_PATH}/")
                        _tts.update { it + (id to bytes) }
                        Log.d(TAG, "tts $id loaded (${bytes.size}B)")
                    }
                }
            } catch (e: Throwable) {
                Log.w(TAG, "asset load failed for $path", e)
            }
        }
    }

    private fun handleDeleted(path: String) {
        when {
            path.startsWith("${WearAsset.DATA_AVATAR_PATH}/") -> {
                val id = path.removePrefix("${WearAsset.DATA_AVATAR_PATH}/")
                _avatars.update { it - id }
                _avatarVersions.update { it - id }
            }
            path.startsWith("${WearAsset.DATA_TTS_PATH}/") -> {
                val id = path.removePrefix("${WearAsset.DATA_TTS_PATH}/")
                _tts.update { it - id }
            }
        }
    }

    /**
     * Convert a sprite-frame DataClient subpath into the AvatarRuntime's
     * frame-key format. Accepts:
     *   "neutral/03"             → "neutral/03"
     *   "thinking/intro/02"      → "thinking.intro/02"
     * The runtime keys phased states with a dot-separated phase, not a slash.
     */
    private fun spriteFrameKeyFromPath(afterFrames: String): String {
        val parts = afterFrames.split('/')
        return when (parts.size) {
            2 -> afterFrames                                  // <state>/<NN>
            3 -> "${parts[0]}.${parts[1]}/${parts[2]}"        // <state>/<phase>/<NN>
            else -> afterFrames                               // fall back verbatim
        }
    }

    companion object {
        private const val TAG = "WearAssetStore"
    }
}
