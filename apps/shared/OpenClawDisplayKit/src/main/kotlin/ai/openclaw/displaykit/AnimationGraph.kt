package ai.openclaw.displaykit

/**
 * Resolved animation table + transition graph for a single mode of a single
 * character. Both sprite and atlas manifests project into this shape so the
 * player stays format-agnostic.
 *
 * Build via [fromManifest] to pull a mode's content out of a server-synthesized
 * [CharacterManifest], or construct directly for tests.
 */
data class AnimationGraph(
    val defaultState: String,
    val animations: Map<String, Animation>,
    val transitions: Map<String, TransitionRef>,
) {
    /**
     * Resolve a state→state transition against the transitions table using
     * wildcard pattern matching. Specificity order (most→least specific):
     *
     *   "<from>-><to>" → "<from>->*" → "*-><to>" → "*->*"
     *
     * Returns null when nothing matches; the caller then swaps instantly.
     */
    fun resolveTransition(from: String, to: String): TransitionRef? {
        val keys = listOf("$from->$to", "$from->*", "*->$to", "*->*")
        for (k in keys) {
            transitions[k]?.let { return it }
        }
        return null
    }

    companion object {
        /**
         * Extract a single mode's animation graph from a character manifest.
         * The default state is taken from [stateMap] — the first key that maps
         * to an animation present in [mode]'s content — or fails if no
         * animation is present.
         */
        fun fromManifest(manifest: CharacterManifest, mode: String): AnimationGraph {
            val content = manifest.content[mode]
                ?: throw IllegalArgumentException(
                    "manifest has no content for mode '$mode'. Available: ${manifest.content.keys}",
                )
            val default = resolveDefaultState(manifest.stateMap, content.animations)
            return AnimationGraph(
                defaultState = default,
                animations = content.animations,
                transitions = content.transitions ?: emptyMap(),
            )
        }

        private fun resolveDefaultState(
            stateMap: Map<String, String>,
            animations: Map<String, Animation>,
        ): String {
            // stateMap maps agent-state → animation name. Prefer the first
            // agent-state whose animation exists in this mode; otherwise fall
            // back to any animation name we have.
            val firstFromMap = stateMap.entries.firstOrNull { animations.containsKey(it.value) }
            if (firstFromMap != null) {
                return firstFromMap.value
            }
            return animations.keys.firstOrNull()
                ?: throw IllegalArgumentException("manifest mode has no animations")
        }
    }
}

/**
 * A transition target resolved for playback: which animation + phase to play
 * once before entering the target state's own loop. Used by the player when a
 * [TransitionRef.Phase] fires on state change.
 */
data class ResolvedTransition(val animation: String, val phase: Phase) {
    companion object {
        /** Parse `"thinking.intro"` into `(thinking, intro)`. Unqualified → loop. */
        fun parse(ref: String): ResolvedTransition {
            val dot = ref.indexOf('.')
            return if (dot < 0) {
                ResolvedTransition(ref, Phase.LOOP)
            } else {
                ResolvedTransition(ref.substring(0, dot), Phase.fromWire(ref.substring(dot + 1)))
            }
        }
    }
}

/** The three phases of a phased animation; flat animations use [LOOP]. */
enum class Phase(val wire: String) {
    INTRO("intro"),
    LOOP("loop"),
    OUTRO("outro"),
    ;

    companion object {
        fun fromWire(value: String): Phase =
            entries.firstOrNull { it.wire == value }
                ?: throw IllegalArgumentException("unknown phase: $value")
    }
}
