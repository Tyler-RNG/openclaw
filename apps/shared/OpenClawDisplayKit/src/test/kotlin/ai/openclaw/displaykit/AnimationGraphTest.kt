package ai.openclaw.displaykit

import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFailsWith
import kotlin.test.assertIs
import kotlin.test.assertNull

class AnimationGraphTest {
    private fun graph(
        transitions: Map<String, TransitionRef> = emptyMap(),
    ) = AnimationGraph(
        defaultState = "neutral",
        animations = mapOf("neutral" to flatAnim(), "thinking" to flatAnim(), "happy" to flatAnim()),
        transitions = transitions,
    )

    private fun flatAnim(): Animation = Animation(
        sequence = FrameSequence(listOf(FrameRef("x")), fps = 12, loop = LoopMode.INFINITE),
    )

    @Test
    fun returnsNullWhenNoTransitionMatches() {
        assertNull(graph().resolveTransition("neutral", "happy"))
    }

    @Test
    fun prefersConcreteOverWildcardMatches() {
        val g = graph(
            transitions = mapOf(
                "*->*" to TransitionRef.Phase("wild.loop"),
                "*->happy" to TransitionRef.Phase("wildhappy.intro"),
                "neutral->*" to TransitionRef.Phase("neutralout.outro"),
                "neutral->happy" to TransitionRef.Phase("direct.intro"),
            ),
        )
        val t = g.resolveTransition("neutral", "happy")
        assertIs<TransitionRef.Phase>(t)
        assertEquals("direct.intro", t.value)
    }

    @Test
    fun fromToWildcardBeatsToFromWildcard() {
        // Rule: `<from>->*` wins over `*-><to>` when both are set.
        val g = graph(
            transitions = mapOf(
                "*->happy" to TransitionRef.Phase("a.intro"),
                "neutral->*" to TransitionRef.Phase("b.outro"),
            ),
        )
        val t = g.resolveTransition("neutral", "happy")
        assertIs<TransitionRef.Phase>(t)
        assertEquals("b.outro", t.value)
    }

    @Test
    fun resolvesBlendTransitionsAsBlendRefs() {
        val g = graph(
            transitions = mapOf(
                "*->happy" to TransitionRef.Crossfade(ms = 150),
            ),
        )
        val t = g.resolveTransition("neutral", "happy")
        assertIs<TransitionRef.Crossfade>(t)
        assertEquals(150, t.ms)
    }

    @Test
    fun parsesTransitionPhaseRefs() {
        assertEquals(
            ResolvedTransition("thinking", Phase.INTRO),
            ResolvedTransition.parse("thinking.intro"),
        )
        assertEquals(
            ResolvedTransition("thinking", Phase.LOOP),
            ResolvedTransition.parse("thinking"),
        )
    }

    @Test
    fun fromManifestPullsOutRequestedMode() {
        val manifest = CharacterManifest(
            version = 1,
            agentId = "ginger",
            modes = listOf("headshot"),
            stateMap = mapOf("neutral" to "neutral"),
            content = mapOf(
                "headshot" to ModeContent(
                    animations = mapOf(
                        "neutral" to flatAnim(),
                        "happy" to flatAnim(),
                    ),
                    transitions = mapOf("*->happy" to TransitionRef.Phase("happy.intro")),
                ),
            ),
            assets = AssetBundle(mapOf("x" to "a/x.png")),
        )
        val g = AnimationGraph.fromManifest(manifest, "headshot")
        assertEquals("neutral", g.defaultState)
        assertEquals(setOf("neutral", "happy"), g.animations.keys)
        assertEquals("happy.intro", (g.transitions["*->happy"] as TransitionRef.Phase).value)
    }

    @Test
    fun fromManifestFailsWhenModeMissing() {
        val manifest = CharacterManifest(
            version = 1,
            agentId = "ginger",
            modes = listOf("headshot"),
            stateMap = mapOf("neutral" to "neutral"),
            content = emptyMap(),
            assets = AssetBundle(emptyMap()),
        )
        assertFailsWith<IllegalArgumentException> {
            AnimationGraph.fromManifest(manifest, "headshot")
        }
    }
}
