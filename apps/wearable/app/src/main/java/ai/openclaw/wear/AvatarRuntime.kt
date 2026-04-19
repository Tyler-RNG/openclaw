package ai.openclaw.wear

import android.graphics.Bitmap
import android.graphics.BitmapFactory
import android.graphics.Rect
import android.util.Log
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.cancel
import kotlinx.coroutines.delay
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.launch
import org.json.JSONObject

/**
 * Unified sprite/atlas avatar playback runtime for the watch. One instance
 * per agent; owns a state machine, frame source, and tick loop. Callers
 * (AgentDialScreen) observe [currentBitmap] and render it each frame — the
 * runtime handles fps, loop modes (infinite / once+holdLastFrame / ping-pong),
 * phased states (intro → loop → outro), and declarative transitions.
 *
 * Two frame sources are supported, differing only in where a named frame's
 * pixels come from:
 *
 *  - [SpriteFrameSource] — each frame is its own Bitmap, indexed by key
 *    "<state>[/<phase>]/<NN>". Fed by per-frame DataClient items under
 *    `/openclaw/avatars/<id>/frames/...`.
 *  - [AtlasFrameSource] — one Bitmap (the atlas image) + a frame rect map
 *    from the manifest. Each frame is a sub-bitmap cropped at tick time.
 *
 * See docs/avatars/formats.md for the on-wire schema and format spec.
 */
class AvatarRuntime(
    private val agentId: String,
    private val frameSource: FrameSource,
    private val definitions: AnimationDefinitions,
) {
    private val scope = CoroutineScope(SupervisorJob() + Dispatchers.Default)
    private val _currentBitmap = MutableStateFlow<Bitmap?>(null)
    val currentBitmap: StateFlow<Bitmap?> = _currentBitmap.asStateFlow()

    private var currentState: String = definitions.defaultState
    private var currentPhase: String? = null
    private var activeJob: kotlinx.coroutines.Job? = null

    init {
        scope.launch { playState(definitions.defaultState, entering = true) }
    }

    /** Request a state change. Applies any matching transition animation. */
    fun requestState(targetState: String) {
        if (targetState == currentState && currentPhase == null) return
        activeJob?.cancel()
        activeJob = scope.launch {
            val transitionRef = definitions.resolveTransition(currentState, targetState)
            if (transitionRef != null) {
                val (tState, tPhase) = transitionRef
                playPhase(tState, tPhase, loopOverride = "once")
            }
            playState(targetState, entering = true)
        }
    }

    fun dispose() {
        activeJob?.cancel()
        scope.cancel()
    }

    private suspend fun playState(state: String, entering: Boolean) {
        currentState = state
        val anim = definitions.animations[state] ?: return
        if (entering && anim.intro != null) {
            playPhase(state, "intro")
        }
        playPhase(state, "loop")
        // `outro` fires only on explicit requestState() transition via the
        // transitions table, not as part of entering a state.
    }

    private suspend fun playPhase(
        state: String,
        phase: String,
        loopOverride: String? = null,
    ) {
        currentPhase = phase
        val anim = definitions.animations[state] ?: return
        val phaseData = when (phase) {
            "intro" -> anim.intro
            "loop" -> anim.loop
            "outro" -> anim.outro
            else -> null
        } ?: return
        val frames = phaseData.frameKeys
        if (frames.isEmpty()) return
        val fps = phaseData.fps
        val frameDelayMs = (1000L / fps).coerceAtLeast(16L)
        val loopMode = loopOverride ?: phaseData.loop

        when (loopMode) {
            "once" -> {
                for (key in frames) {
                    renderFrame(key) ?: continue
                    delay(frameDelayMs)
                }
                if (phaseData.holdLastFrame) {
                    // Leave the last rendered bitmap in place. No further ticks.
                } else {
                    _currentBitmap.value = null
                }
            }
            "ping-pong" -> {
                val cap = phaseData.iterations ?: Int.MAX_VALUE
                var rounds = 0
                while (rounds < cap) {
                    for (key in frames) {
                        renderFrame(key) ?: continue
                        delay(frameDelayMs)
                    }
                    for (i in frames.size - 2 downTo 1) {
                        renderFrame(frames[i]) ?: continue
                        delay(frameDelayMs)
                    }
                    rounds++
                }
            }
            else -> { // "infinite"
                while (true) {
                    for (key in frames) {
                        renderFrame(key) ?: continue
                        delay(frameDelayMs)
                    }
                }
            }
        }
    }

    private fun renderFrame(key: String): Bitmap? {
        val bitmap = frameSource.frame(key) ?: return null
        _currentBitmap.value = bitmap
        return bitmap
    }

    companion object {
        private const val TAG = "AvatarRuntime"
    }
}

/**
 * Resolved animation + transition table for one agent. Both sprite and atlas
 * paths project into this structure so the runtime can stay format-agnostic.
 */
data class AnimationDefinitions(
    val defaultState: String,
    val animations: Map<String, StateAnimation>,
    /** key: "<from>-><to>" with `*` wildcards allowed on either side. */
    val transitions: Map<String, TransitionTarget>,
) {
    /**
     * Matches `from→to` against the transitions table with `*` wildcards.
     * Specificity order: concrete→concrete > concrete→* > *→concrete > *→*.
     */
    fun resolveTransition(from: String, to: String): Pair<String, String>? {
        val keys = listOf("$from->$to", "$from->*", "*->$to", "*->*")
        for (k in keys) {
            val target = transitions[k]
            if (target != null) return target.state to target.phase
        }
        return null
    }
}

data class StateAnimation(
    val intro: PhaseData?,
    val loop: PhaseData?,
    val outro: PhaseData?,
)

data class PhaseData(
    val frameKeys: List<String>,
    val fps: Int,
    val loop: String, // "infinite" | "once" | "ping-pong"
    val holdLastFrame: Boolean,
    val iterations: Int?,
)

/** Transition target: which state + phase the runtime should play before settling. */
data class TransitionTarget(val state: String, val phase: String)

/** Source of frame bitmaps. Two impls cover the sprites and atlas formats. */
interface FrameSource {
    /** Return the bitmap for a frame key like "neutral/03" or "thinking.intro/01". */
    fun frame(key: String): Bitmap?
}

/**
 * Sprite-frames frame source. Bytes per frame key are prefetched from the
 * phone via DataClient; this class just decodes + caches the Bitmap on first
 * access. Keys match the wire path suffix: "<state>[/<phase>]/<NN>" with
 * zero-padded numeric index.
 */
class SpriteFrameSource : FrameSource {
    private val bytesByKey = mutableMapOf<String, ByteArray>()
    private val bitmapByKey = mutableMapOf<String, Bitmap>()

    fun put(key: String, bytes: ByteArray) {
        bytesByKey[key] = bytes
        bitmapByKey.remove(key) // invalidate decoded cache on byte refresh
    }

    override fun frame(key: String): Bitmap? {
        bitmapByKey[key]?.let { return it }
        val bytes = bytesByKey[key] ?: return null
        return try {
            val bitmap = BitmapFactory.decodeByteArray(bytes, 0, bytes.size)
            if (bitmap != null) bitmapByKey[key] = bitmap
            bitmap
        } catch (e: Throwable) {
            Log.w(TAG, "decode failed for $key", e)
            null
        }
    }

    fun keys(): Set<String> = bytesByKey.keys

    companion object {
        private const val TAG = "SpriteFrameSource"
    }
}

/**
 * Atlas frame source. One Bitmap (the full atlas image) + a frame rect map
 * from the manifest. Each frame is returned as a sub-bitmap via
 * `Bitmap.createBitmap(src, x, y, w, h)`. Results are cached so slicing
 * happens once per unique key.
 */
class AtlasFrameSource(
    private val atlas: Bitmap,
    private val rects: Map<String, Rect>,
) : FrameSource {
    private val cache = mutableMapOf<String, Bitmap>()

    override fun frame(key: String): Bitmap? {
        cache[key]?.let { return it }
        val rect = rects[key] ?: return null
        return try {
            val slice = Bitmap.createBitmap(atlas, rect.left, rect.top, rect.width(), rect.height())
            cache[key] = slice
            slice
        } catch (e: Throwable) {
            Log.w(TAG, "slice failed for $key", e)
            null
        }
    }

    companion object {
        private const val TAG = "AtlasFrameSource"
    }
}

/**
 * Parse a sprite-frame avatar JSON descriptor (the `avatarSprites` field on
 * the agent row, schema documented in docs/avatars/formats.md) into the
 * format-agnostic AnimationDefinitions the runtime consumes.
 */
fun parseSpritesDefinitions(json: JSONObject): AnimationDefinitions {
    val defaultState = json.optString("default", "neutral")
    val statesJson = json.optJSONObject("states") ?: return AnimationDefinitions(
        defaultState,
        emptyMap(),
        emptyMap(),
    )
    val animations = mutableMapOf<String, StateAnimation>()
    val stateNames = statesJson.keys()
    while (stateNames.hasNext()) {
        val state = stateNames.next()
        val stateCfg = statesJson.optJSONObject(state) ?: continue
        // Phased: has an object at `loop` (intro/outro optional).
        val isPhased = stateCfg.optJSONObject("loop") != null
        val anim = if (isPhased) {
            StateAnimation(
                intro = phaseFromJson(state, "intro", stateCfg.optJSONObject("intro")),
                loop = phaseFromJson(state, "loop", stateCfg.optJSONObject("loop")),
                outro = phaseFromJson(state, "outro", stateCfg.optJSONObject("outro")),
            )
        } else {
            StateAnimation(
                intro = null,
                loop = phaseFromJson(state, null, stateCfg),
                outro = null,
            )
        }
        animations[state] = anim
    }
    val transitions = parseTransitions(json.optJSONObject("transitions"))
    return AnimationDefinitions(defaultState, animations, transitions)
}

/**
 * Parse an atlas manifest JSON (schema in docs/avatars/formats.md) into
 * AnimationDefinitions. Frame rects go into the atlas frame source; this
 * function only builds the animation timing + transitions structure.
 */
fun parseAtlasDefinitions(manifestJson: JSONObject, defaultState: String): AnimationDefinitions {
    val animsJson = manifestJson.optJSONObject("animations") ?: return AnimationDefinitions(
        defaultState,
        emptyMap(),
        emptyMap(),
    )
    val animations = mutableMapOf<String, StateAnimation>()
    val stateNames = animsJson.keys()
    while (stateNames.hasNext()) {
        val state = stateNames.next()
        val entry = animsJson.optJSONObject(state) ?: continue
        val isPhased = entry.optJSONObject("loop") != null
        val anim = if (isPhased) {
            StateAnimation(
                intro = phaseFromAtlasJson(entry.optJSONObject("intro")),
                loop = phaseFromAtlasJson(entry.optJSONObject("loop")),
                outro = phaseFromAtlasJson(entry.optJSONObject("outro")),
            )
        } else {
            StateAnimation(
                intro = null,
                loop = phaseFromAtlasJson(entry),
                outro = null,
            )
        }
        animations[state] = anim
    }
    val transitions = parseTransitions(manifestJson.optJSONObject("transitions"))
    return AnimationDefinitions(defaultState, animations, transitions)
}

private fun phaseFromJson(state: String, phase: String?, cfg: JSONObject?): PhaseData? {
    if (cfg == null) return null
    val count = cfg.optInt("count", 0)
    if (count <= 0) return null
    val fps = cfg.optInt("fps", 12)
    val loop = cfg.optString("loop", "infinite")
    val hold = cfg.optBoolean("holdLastFrame", false)
    val iterations = if (cfg.has("iterations")) cfg.optInt("iterations", 0).takeIf { it > 0 } else null
    val digits = if (count >= 100) 3 else 2
    val keys = (0 until count).map { i ->
        val padded = i.toString().padStart(digits, '0')
        if (phase != null) "$state.$phase/$padded" else "$state/$padded"
    }
    return PhaseData(keys, fps, loop, hold, iterations)
}

private fun phaseFromAtlasJson(cfg: JSONObject?): PhaseData? {
    if (cfg == null) return null
    val framesArr = cfg.optJSONArray("frames") ?: return null
    val keys = (0 until framesArr.length()).mapNotNull { framesArr.optString(it, null) }
    if (keys.isEmpty()) return null
    val fps = cfg.optInt("fps", 12)
    val loop = cfg.optString("loop", "infinite")
    val hold = cfg.optBoolean("holdLastFrame", false)
    val iterations = if (cfg.has("iterations")) cfg.optInt("iterations", 0).takeIf { it > 0 } else null
    return PhaseData(keys, fps, loop, hold, iterations)
}

private fun parseTransitions(obj: JSONObject?): Map<String, TransitionTarget> {
    if (obj == null) return emptyMap()
    val out = mutableMapOf<String, TransitionTarget>()
    val keys = obj.keys()
    while (keys.hasNext()) {
        val pattern = keys.next()
        val value = obj.opt(pattern)
        val target = when (value) {
            is String -> {
                // "thinking.intro" → state="thinking", phase="intro".
                val dot = value.indexOf('.')
                if (dot < 0) TransitionTarget(value, "loop") else TransitionTarget(
                    value.substring(0, dot),
                    value.substring(dot + 1),
                )
            }
            // Blend objects are recognized by the schema but the runtime
            // currently plays them as an instant swap — crossfade blending
            // lands in a follow-up pass (needs Compose alpha animation).
            is JSONObject -> continue
            else -> continue
        }
        out[pattern] = target
    }
    return out
}
