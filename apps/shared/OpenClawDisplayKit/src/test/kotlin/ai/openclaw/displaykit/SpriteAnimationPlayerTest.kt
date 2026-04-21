package ai.openclaw.displaykit

import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.ExperimentalCoroutinesApi
import kotlinx.coroutines.test.StandardTestDispatcher
import kotlinx.coroutines.test.TestScope
import kotlinx.coroutines.test.advanceTimeBy
import kotlinx.coroutines.test.advanceUntilIdle
import kotlinx.coroutines.test.runTest
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertNull

@OptIn(ExperimentalCoroutinesApi::class)
class SpriteAnimationPlayerTest {
    // ---- helpers ----

    private fun seq(
        count: Int,
        loop: LoopMode = LoopMode.INFINITE,
        fps: Int = 10, // 100ms / frame → easy to reason about under advanceTimeBy
        holdLastFrame: Boolean = false,
        iterations: Int? = null,
    ) = FrameSequence(
        frames = (0 until count).map { FrameRef("f$it") },
        fps = fps,
        loop = loop,
        holdLastFrame = holdLastFrame,
        iterations = iterations,
    )

    private fun anim(
        sequence: FrameSequence? = null,
        intro: FrameSequence? = null,
        loop: FrameSequence? = null,
        outro: FrameSequence? = null,
    ) = Animation(sequence = sequence, intro = intro, loop = loop, outro = outro)

    private class TestTicker(private val scope: TestScope) : Ticker {
        override fun nowMs(): Long = scope.testScheduler.currentTime
        override suspend fun delay(ms: Long) {
            if (ms > 0L) kotlinx.coroutines.delay(ms)
        }
    }

    private fun TestScope.newPlayer(graph: AnimationGraph): SpriteAnimationPlayer {
        val scopeForPlayer = CoroutineScope(coroutineContext)
        return SpriteAnimationPlayer(graph, TestTicker(this), scopeForPlayer)
    }

    // ---- tests ----

    @Test
    fun playsInfiniteLoopAdvancingOneFramePerTick() = runTest(StandardTestDispatcher()) {
        val g = AnimationGraph(
            defaultState = "neutral",
            animations = mapOf("neutral" to anim(sequence = seq(count = 3, loop = LoopMode.INFINITE))),
            transitions = emptyMap(),
        )
        val player = newPlayer(g)

        advanceTimeBy(10) // kick coroutine past init
        assertEquals("f0", player.currentRef.value?.ref)

        advanceTimeBy(100)
        assertEquals("f1", player.currentRef.value?.ref)

        advanceTimeBy(100)
        assertEquals("f2", player.currentRef.value?.ref)

        advanceTimeBy(100)
        assertEquals("f0", player.currentRef.value?.ref) // wrapped

        player.dispose()
    }

    @Test
    fun onceWithHoldLastFrameFreezesOnFinalFrame() = runTest(StandardTestDispatcher()) {
        val g = AnimationGraph(
            defaultState = "state",
            animations = mapOf(
                "state" to anim(sequence = seq(count = 3, loop = LoopMode.ONCE, holdLastFrame = true)),
            ),
            transitions = emptyMap(),
        )
        val player = newPlayer(g)

        // Advance way past playback.
        advanceTimeBy(10_000)
        assertEquals("f2", player.currentRef.value?.ref)

        player.dispose()
    }

    @Test
    fun onceWithoutHoldClearsAfterLastFrame() = runTest(StandardTestDispatcher()) {
        val g = AnimationGraph(
            defaultState = "state",
            animations = mapOf(
                "state" to anim(sequence = seq(count = 2, loop = LoopMode.ONCE, holdLastFrame = false)),
            ),
            transitions = emptyMap(),
        )
        val player = newPlayer(g)

        advanceTimeBy(10_000)
        assertNull(player.currentRef.value)

        player.dispose()
    }

    @Test
    fun pingPongBouncesAndCapsAtIterations() = runTest(StandardTestDispatcher()) {
        val g = AnimationGraph(
            defaultState = "state",
            animations = mapOf(
                "state" to anim(
                    sequence = seq(count = 3, loop = LoopMode.PING_PONG, iterations = 1),
                ),
            ),
            transitions = emptyMap(),
        )
        val player = newPlayer(g)

        val seen = mutableListOf<String>()
        advanceTimeBy(10)
        fun capture() {
            val ref = player.currentRef.value?.ref
            if (ref != null && (seen.isEmpty() || seen.last() != ref)) {
                seen.add(ref)
            }
        }

        // Walk through: f0, f1, f2, f1 (bounce), then stops (iterations=1 → one round-trip).
        capture()
        repeat(30) {
            advanceTimeBy(100)
            capture()
        }

        // After the bounce finishes the loop exits; the last ref stays visible.
        // Minimum expected ordering within the round trip.
        val indexF0 = seen.indexOf("f0")
        val indexF2 = seen.indexOf("f2")
        val indexF1Bounce = seen.lastIndexOf("f1")
        assertEquals(0, indexF0)
        assert(indexF2 > indexF0)
        assert(indexF1Bounce > indexF2) { "expected ping-pong to return to f1 after f2, got $seen" }

        player.dispose()
    }

    @Test
    fun enteringAStateWithIntroPlaysIntroThenLoop() = runTest(StandardTestDispatcher()) {
        val g = AnimationGraph(
            defaultState = "thinking",
            animations = mapOf(
                "thinking" to anim(
                    intro = seq(count = 2, loop = LoopMode.ONCE),
                    loop = seq(count = 2, loop = LoopMode.INFINITE),
                ),
            ),
            transitions = emptyMap(),
        )
        val player = newPlayer(g)

        val order = mutableListOf<String>()
        advanceTimeBy(10)
        order.add(player.currentRef.value!!.ref) // f0 intro
        advanceTimeBy(100)
        order.add(player.currentRef.value!!.ref) // f1 intro
        advanceTimeBy(100)
        order.add(player.currentRef.value!!.ref) // f0 loop
        advanceTimeBy(100)
        order.add(player.currentRef.value!!.ref) // f1 loop
        advanceTimeBy(100)
        order.add(player.currentRef.value!!.ref) // f0 loop (cycled)

        assertEquals(listOf("f0", "f1", "f0", "f1", "f0"), order)

        player.dispose()
    }

    @Test
    fun requestStatePlaysTransitionPhaseBeforeEnteringTargetState() = runTest(StandardTestDispatcher()) {
        val g = AnimationGraph(
            defaultState = "neutral",
            animations = mapOf(
                "neutral" to anim(sequence = seq(count = 1, loop = LoopMode.INFINITE)),
                "thinking" to anim(
                    intro = seq(count = 1, loop = LoopMode.ONCE),
                    loop = seq(count = 1, loop = LoopMode.INFINITE),
                ),
            ),
            transitions = mapOf("*->thinking" to TransitionRef.Phase("thinking.intro")),
        )
        val player = newPlayer(g)
        // Kick past init so the neutral loop has rendered its first frame.
        // advanceUntilIdle() would hang here — default state is an infinite loop.
        advanceTimeBy(10)
        val before = player.currentRef.value?.ref
        assertEquals("f0", before)

        player.requestState("thinking")
        advanceTimeBy(10)
        // First thing played on transition is thinking.intro f0 (via transition ref).
        assertEquals("f0", player.currentRef.value?.ref)
        // Advance past the transition's ONCE playback (100ms @ 10fps) into the
        // target state's own loop; target state is now "thinking".
        advanceTimeBy(300)
        assertEquals("thinking", player.currentState.value)

        player.dispose()
    }

    @Test
    fun requestSameStateIsNoop() = runTest(StandardTestDispatcher()) {
        val g = AnimationGraph(
            defaultState = "neutral",
            animations = mapOf("neutral" to anim(sequence = seq(count = 1, loop = LoopMode.INFINITE))),
            transitions = mapOf("*->*" to TransitionRef.Phase("neutral.intro")),
        )
        val player = newPlayer(g)
        // Default state is infinite; advanceTimeBy is the only safe way to
        // kick past init without hanging on the perpetual frame loop.
        advanceTimeBy(10)
        val before = player.currentState.value

        player.requestState(before)
        advanceTimeBy(10)

        assertEquals(before, player.currentState.value)
        player.dispose()
    }
}
