package ai.openclaw.wear

import android.content.Context
import android.util.Log
import ai.openclaw.spritecore.client.CharacterManifestEnvelope
import ai.openclaw.spritecore.client.CharacterManifestJson
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

    /**
     * Current avatar state per agent, driven by `/openclaw/avatars/<id>/state`
     * DataItems the phone publishes on every `[avatar:<name>]` marker
     * dispatch. Consumed by CharacterAvatar → SpriteAnimationPlayer.requestState.
     * Unknown/stale state names are harmless — the player silently ignores them.
     */
    private val _agentStates = MutableStateFlow<Map<String, String>>(emptyMap())
    val agentStates: StateFlow<Map<String, String>> = _agentStates.asStateFlow()

    /**
     * Most recent state the PHONE dispatched via DataClient, kept separate
     * from [_agentStates] because local per-segment overrides also land in
     * [_agentStates] during segmented playback. [restoreDispatchedState]
     * reads this to restore the phone's view (which includes the default
     * "reset to idle" the phone fires on `isFinal`) once playback finishes.
     */
    private val _phoneDispatchedStates = MutableStateFlow<Map<String, String>>(emptyMap())

    /**
     * Locally override the avatar state for [agentId] — used by the wear
     * audio router to sync avatar animation to per-emotion audio segments
     * during segmented playback. The phone also dispatches states via
     * DataClient; last-write-wins on the flow. Safe to call from any
     * thread — [MutableStateFlow.update] is atomic.
     *
     * This does NOT touch [_phoneDispatchedStates]; that tracks only
     * phone-dispatched state so [restoreDispatchedState] can undo local
     * per-segment overrides cleanly.
     */
    fun setLocalAgentState(agentId: String, stateName: String) {
        if (agentId.isBlank() || stateName.isBlank()) return
        _agentStates.update { it + (agentId to stateName) }
    }

    /**
     * Re-apply the last state the phone dispatched via DataClient for
     * [agentId]. Called by the wear audio router after segmented playback
     * completes so the avatar returns to whatever the phone set (typically
     * the agent's default/idle state, dispatched on `isFinal`). Silently
     * no-ops when we haven't yet seen a phone-dispatched state for the agent.
     */
    fun restoreDispatchedState(agentId: String) {
        if (agentId.isBlank()) return
        val dispatched = _phoneDispatchedStates.value[agentId] ?: return
        _agentStates.update { it + (agentId to dispatched) }
    }

    // Phone publishes a JSON envelope at
    //   /openclaw/avatars/<id>/character-manifest
    // and each asset ref's bytes at
    //   /openclaw/avatars/<id>/character-assets/<refKey>
    // Watch parses both into the SDK's CharacterManifestEnvelope + a
    // per-ref byte map and feeds SpriteAnimationPlayer. This is the single
    // avatar rendering path for structured (sprites/atlas/states) agents.
    private val _characterManifests =
        MutableStateFlow<Map<String, CharacterManifestEnvelope>>(emptyMap())
    val characterManifests: StateFlow<Map<String, CharacterManifestEnvelope>> =
        _characterManifests.asStateFlow()

    private val _characterAssets =
        MutableStateFlow<Map<String, Map<String, ByteArray>>>(emptyMap())
    val characterAssets: StateFlow<Map<String, Map<String, ByteArray>>> =
        _characterAssets.asStateFlow()

    private val _tts = MutableStateFlow<Map<String, ByteArray>>(emptyMap())
    val tts: StateFlow<Map<String, ByteArray>> = _tts.asStateFlow()

    private val listener = DataClient.OnDataChangedListener { events ->
        for (event in events) {
            val path = event.dataItem.uri.path ?: continue
            when (event.type) {
                DataEvent.TYPE_CHANGED -> {
                    // State-signal path is a tiny JSON DataMap, no Asset —
                    // handled synchronously before falling through to the
                    // asset-bearing branches.
                    val stateMatch = Regex("${Regex.escape(WearAsset.DATA_AVATAR_PATH)}/([^/]+)/state").matchEntire(path)
                    if (stateMatch != null) {
                        val agentId = stateMatch.groupValues[1]
                        val dm = runCatching { DataMapItem.fromDataItem(event.dataItem).dataMap }.getOrNull()
                        val stateName = dm?.getString("state")?.takeIf { it.isNotBlank() }
                        if (stateName != null) {
                            _agentStates.update { it + (agentId to stateName) }
                            _phoneDispatchedStates.update { it + (agentId to stateName) }
                            Log.d(TAG, "state $agentId → $stateName")
                        }
                    } else {
                        handleChanged(event.dataItem, path)
                    }
                }
                DataEvent.TYPE_DELETED -> handleDeleted(path)
            }
        }
    }

    fun start() {
        dataClient.addListener(listener)
        // Intentionally NOT sweeping existing DataLayer items here. The GMS
        // DataLayer persists phone-authored items across watch app uninstalls
        // and reboots, which means a sweep would rehydrate stale data that
        // bypasses a fresh gateway fetch. We only react to live TYPE_CHANGED /
        // TYPE_DELETED events from the phone instead, so the watch is empty
        // until the phone publishes during this session.
        //
        // TODO: revisit pairing this with a phone-side delete-then-put (or
        // MessageClient-based "resend current state" handshake) so the watch
        // repopulates reliably even when payload bytes would dedupe identically
        // in GMS. For now any stale items in DataLayer are simply ignored.
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
        // Character-manifest envelope path: DataMap carries the JSON string
        // directly (no Asset indirection), since it's bounded text payload.
        if (WearAsset.isCharacterManifestPath(path)) {
            val dm = runCatching { DataMapItem.fromDataItem(item).dataMap }.getOrNull() ?: return
            val id = path.removePrefix("${WearAsset.DATA_AVATAR_PATH}/").substringBefore('/')
            val jsonText = dm.getString("manifest") ?: return
            val envelope = CharacterManifestJson.parse(jsonText)
            if (envelope != null) {
                _characterManifests.update { it + (id to envelope) }
                Log.d(TAG, "manifest $id rev=${envelope.revision}")
            } else {
                Log.w(TAG, "manifest parse failed for $id")
            }
            return
        }
        val dm = runCatching { DataMapItem.fromDataItem(item).dataMap }.getOrNull() ?: return
        val asset = dm.getAsset("data") ?: return
        scope.launch {
            try {
                val fd = dataClient.getFdForAsset(asset).await()
                val bytes = fd.inputStream.use { it.readBytes() }
                when {
                    // Character-asset byte path (sprite-core SDK):
                    //   /openclaw/avatars/<agentId>/character-assets/<refKey>
                    // refKey may contain slashes (sprite frame keys) or be flat (atlas image).
                    WearAsset.parseCharacterAssetPath(path) != null -> {
                        val (id, refKey) = WearAsset.parseCharacterAssetPath(path)!!
                        _characterAssets.update { current ->
                            val per = current[id]?.toMutableMap() ?: mutableMapOf()
                            per[refKey] = bytes
                            current + (id to per)
                        }
                        _avatarVersions.update { it + (id to ((it[id] ?: 0) + 1)) }
                        Log.d(TAG, "char-asset $id $refKey (${bytes.size}B)")
                    }
                    // Plain-URL avatar bytes (legacy `kind: undefined` agents — the
                    // phone rewrites identity.avatarUrl to wear-asset:avatar:<id>
                    // and publishes the bytes here).
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
            WearAsset.isCharacterManifestPath(path) -> {
                val id = path.removePrefix("${WearAsset.DATA_AVATAR_PATH}/").substringBefore('/')
                _characterManifests.update { it - id }
                _characterAssets.update { it - id }
            }
            WearAsset.parseCharacterAssetPath(path) != null -> {
                val (id, refKey) = WearAsset.parseCharacterAssetPath(path)!!
                _characterAssets.update { current ->
                    val per = current[id] ?: return@update current
                    val updated = per - refKey
                    if (updated.isEmpty()) current - id else current + (id to updated)
                }
            }
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

    companion object {
        private const val TAG = "WearAssetStore"
    }
}
