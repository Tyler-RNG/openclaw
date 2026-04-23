package ai.openclaw.app.avatar

import android.util.Log
import ai.openclaw.displaykit.CharacterManifestEnvelope
import ai.openclaw.displaykit.android.CharacterManifestJson
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.flow.update
import kotlinx.coroutines.launch
import kotlinx.coroutines.sync.Mutex
import kotlinx.coroutines.sync.withLock
import org.json.JSONObject

/**
 * Phone-side unified fetcher + cache for per-agent CharacterManifest
 * envelopes and their asset bytes. Single source of truth for both
 * consumers of this data on the phone:
 *
 *   - The phone's own AgentDialScreen reads [characterManifests] +
 *     [characterAssets] + [agentStates] directly.
 *   - The wear relay pulls pre-fetched entries from [snapshot] when
 *     publishing to the watch's DataClient, avoiding a second round-trip.
 *
 * Fetch policy is explicit: callers invoke [refresh] with an agent-id list
 * (usually on agents.list refresh / operator connect). An agent already
 * present in the cache at the same manifest revision is left alone;
 * revision bumps trigger a re-fetch of changed asset refs only.
 *
 * Not a singleton — owned by the NodeRuntime scope so its coroutines die
 * with the runtime.
 */
class AgentAvatarSource(
    private val scope: CoroutineScope,
    private val fetchManifest: suspend (agentId: String) -> String?,
    private val fetchAsset: suspend (relativePath: String) -> ByteArray?,
) {
    private val _characterManifests =
        MutableStateFlow<Map<String, CharacterManifestEnvelope>>(emptyMap())
    val characterManifests: StateFlow<Map<String, CharacterManifestEnvelope>> =
        _characterManifests.asStateFlow()

    private val _characterAssets =
        MutableStateFlow<Map<String, Map<String, ByteArray>>>(emptyMap())
    val characterAssets: StateFlow<Map<String, Map<String, ByteArray>>> =
        _characterAssets.asStateFlow()

    private val _agentStates = MutableStateFlow<Map<String, String>>(emptyMap())
    val agentStates: StateFlow<Map<String, String>> = _agentStates.asStateFlow()

    /**
     * Monotonically-versioned state signal per agent. Carries the same name
     * as [agentStates] plus the optional `count` from the parsed
     * `<<<state-N>>>` marker and a [version] that bumps on every dispatch
     * even when the name is unchanged — so the UI's LaunchedEffect re-fires
     * and the animation player replays the state if the model emits the
     * same marker twice in a row.
     */
    private val _agentMarkerSignals = MutableStateFlow<Map<String, AvatarMarkerSignal>>(emptyMap())
    val agentMarkerSignals: StateFlow<Map<String, AvatarMarkerSignal>> =
        _agentMarkerSignals.asStateFlow()

    private val signalVersionSeq = java.util.concurrent.atomic.AtomicLong(0L)

    private val fetchMutex = Mutex()

    /**
     * Kick off a background fetch for each agent. No-ops for agents whose
     * manifest is already cached at the current revision. Returns immediately;
     * the flows update as results land.
     */
    fun refresh(agentIds: List<String>) {
        if (agentIds.isEmpty()) return
        scope.launch {
            fetchMutex.withLock {
                for (agentId in agentIds) {
                    refreshOne(agentId)
                }
            }
        }
    }

    /**
     * Update the current state for an agent. Called by the chat-reply path
     * when an `<<<state>>>` or `<<<state-N>>>` marker fires. Watch still
     * gets its own state signal via DataClient publishAgentState; this
     * feeds the phone's own UI.
     *
     * [count] semantics:
     *   null or 0 → loop the animation until the next state dispatch
     *   N >= 1    → play N times and hold on the last frame
     */
    fun setAgentState(agentId: String, stateName: String, count: Int? = null) {
        _agentStates.update { it + (agentId to stateName) }
        val signal = AvatarMarkerSignal(
            state = stateName,
            count = count,
            version = signalVersionSeq.incrementAndGet(),
        )
        _agentMarkerSignals.update { it + (agentId to signal) }
    }

    /**
     * Snapshot of the current cache for the wear relay to iterate when
     * publishing to the watch. Values are consistent per call; concurrent
     * cache updates between calls are expected and safe.
     */
    fun snapshot(): List<CachedAgent> {
        val manifests = _characterManifests.value
        val assets = _characterAssets.value
        return manifests.map { (agentId, envelope) ->
            CachedAgent(agentId = agentId, envelope = envelope, assetBytes = assets[agentId].orEmpty())
        }
    }

    /**
     * Drop any cached entries for agents no longer in [keepIds]. Called by
     * refresh() after a successful pull so removed agents don't linger.
     */
    fun retainOnly(keepIds: Collection<String>) {
        val keep = keepIds.toSet()
        _characterManifests.update { it.filterKeys { id -> id in keep } }
        _characterAssets.update { it.filterKeys { id -> id in keep } }
        _agentStates.update { it.filterKeys { id -> id in keep } }
    }

    fun clear() {
        _characterManifests.update { emptyMap() }
        _characterAssets.update { emptyMap() }
        _agentStates.update { emptyMap() }
    }

    /**
     * Resolve the default state name for [agentId] from its cached manifest.
     * Mirrors DisplayKit's AnimationGraph.fromManifest default-state logic:
     * first stateMap entry whose value is in content.animations, else the
     * first animation name, else null. Used by the wear relay to reset the
     * watch's avatar back to idle after a reply completes.
     */
    fun defaultStateFor(agentId: String): String? {
        val envelope = _characterManifests.value[agentId] ?: return null
        val manifest = envelope.manifest
        val mode = manifest.modes.firstOrNull { manifest.content.containsKey(it) } ?: return null
        val animations = manifest.content[mode]?.animations ?: return null
        val firstFromMap = manifest.stateMap.entries.firstOrNull { animations.containsKey(it.value) }
        if (firstFromMap != null) return firstFromMap.value
        return animations.keys.firstOrNull()
    }

    // --- internals ---

    private suspend fun refreshOne(agentId: String) {
        val envelopeJson = fetchManifest(agentId) ?: run {
            Log.d(TAG, "manifest skip $agentId (no structured avatar or RPC failed)")
            return
        }
        val envelope = CharacterManifestJson.parse(envelopeJson) ?: run {
            Log.w(TAG, "manifest parse failed $agentId")
            return
        }
        val existing = _characterManifests.value[agentId]
        if (existing != null && existing.revision == envelope.revision) {
            return
        }
        _characterManifests.update { it + (agentId to envelope) }

        // Asset byte fetch. If a ref failed, leave it out — the dial renders
        // nothing until all refs are in, then swaps in when ready.
        val refs = parseAssetRefs(envelopeJson)
        val bytesByRef = mutableMapOf<String, ByteArray>()
        for ((refKey, relPath) in refs) {
            val bytes = fetchAsset(relPath)
            if (bytes != null) {
                bytesByRef[refKey] = bytes
            } else {
                Log.w(TAG, "asset fetch failed $agentId $refKey")
            }
        }
        _characterAssets.update { it + (agentId to bytesByRef) }
        Log.d(TAG, "cached $agentId rev=${envelope.revision} (${bytesByRef.size}/${refs.size} assets)")
    }

    private fun parseAssetRefs(envelopeJson: String): List<Pair<String, String>> {
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

    data class CachedAgent(
        val agentId: String,
        val envelope: CharacterManifestEnvelope,
        val assetBytes: Map<String, ByteArray>,
    )

    /**
     * Versioned per-agent animation signal. [version] bumps on every
     * [setAgentState] call so UI consumers keyed on the signal re-trigger
     * their LaunchedEffect even when the state name is unchanged. [count]
     * is forwarded from the parsed `<<<state-N>>>` marker and governs
     * playback cadence in [SpriteAnimationPlayer].
     */
    data class AvatarMarkerSignal(
        val state: String,
        val count: Int?,
        val version: Long,
    )

    companion object {
        private const val TAG = "AgentAvatarSource"
    }
}
