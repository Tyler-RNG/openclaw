package ai.openclaw.displaykit

import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.Job
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.awaitCancellation
import kotlinx.coroutines.cancel
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.launch

/**
 * Platform-independent playback engine. One instance per character per mode.
 * Drives [currentRef] forward over time according to the [AnimationGraph]'s
 * animations and transitions; callers materialize frames via their own
 * [FrameSource].
 *
 * Thread safety: [requestState] is safe to call from any thread. Internal
 * state mutations happen on the supplied coroutine scope's dispatcher.
 */
class SpriteAnimationPlayer(
    private val graph: AnimationGraph,
    private val ticker: Ticker = SystemTicker(),
    scope: CoroutineScope? = null,
) {
    private val owned = scope == null
    private val scope: CoroutineScope = scope ?: CoroutineScope(SupervisorJob() + Dispatchers.Default)

    private val _currentRef = MutableStateFlow<FrameRef?>(null)
    /** The frame the caller should be rendering right now. Null = blank. */
    val currentRef: StateFlow<FrameRef?> = _currentRef.asStateFlow()

    private val _currentState = MutableStateFlow(graph.defaultState)
    /** The agent state the player is currently in (post-transition). */
    val currentState: StateFlow<String> = _currentState.asStateFlow()

    private var activeJob: Job? = null

    init {
        activeJob = this.scope.launch {
            playState(graph.defaultState, entering = true)
        }
    }

    /**
     * Request a state change. If the [graph]'s transitions table has a match
     * for `currentState → target`, that transition plays once before the
     * target state's own loop starts.
     *
     * [playCount] semantics (from the client-parsed `<<<state-N>>>` marker):
     *   null or 0 → default: the state's configured loop plays indefinitely
     *               until the next [requestState] cancels it.
     *   N >= 1    → play the loop phase exactly N times, then hold the last
     *               frame indefinitely. Intro (if any) still plays once.
     *
     * When [playCount] is non-null we always replay — even when [target] is
     * already the current state — so a model emitting the same marker twice
     * in a row ("<<<wink-1>>> then <<<wink-1>>>") visibly replays the
     * animation instead of being swallowed as a no-op.
     */
    fun requestState(target: String, playCount: Int? = null): Job {
        // Capture the job reference before reassignment. Reading activeJob
        // from inside the launched block is a race — by the time the block
        // runs, activeJob has been overwritten to `job` itself, so the old
        // job (often the init-spawned infinite loop) would never be cancelled.
        val previousJob = activeJob
        val job = scope.launch {
            val sameState = target == _currentState.value
            if (sameState && (playCount == null || playCount <= 0)) {
                previousJob?.cancelAndJoin()
                return@launch
            }
            val previousState = _currentState.value
            previousJob?.cancelAndJoin()
            if (!sameState) {
                val transition = graph.resolveTransition(previousState, target)
                if (transition is TransitionRef.Phase) {
                    val resolved = ResolvedTransition.parse(transition.value)
                    playPhase(
                        animName = resolved.animation,
                        phase = resolved.phase,
                        loopOverride = LoopMode.ONCE,
                    )
                }
                // Crossfade transitions are currently played as an instant
                // swap; the visual blend is a rendering-side concern the
                // consumer applies when the ref changes.
            }
            playState(target, entering = !sameState, playCountOverride = playCount)
        }
        activeJob = job
        return job
    }

    /** Cancel playback and, if we own it, the internal scope. */
    fun dispose() {
        activeJob?.cancel()
        if (owned) {
            scope.cancel()
        }
    }

    // --- internals ---

    private suspend fun playState(
        state: String,
        entering: Boolean,
        playCountOverride: Int? = null,
    ) {
        _currentState.value = state
        val anim = graph.animations[state] ?: return
        if (entering && anim.intro != null) {
            playPhase(state, Phase.INTRO)
        }
        if (playCountOverride != null && playCountOverride >= 1) {
            playPhaseFinite(state, Phase.LOOP, playCountOverride)
            return
        }
        // Flat states fall through to `effectiveLoop`; phased states play
        // `loop` here. `outro` fires only on requestState() via transitions.
        playPhase(state, Phase.LOOP)
    }

    /**
     * Play the [phase] of [animName] exactly [times] times, then hold the
     * last frame indefinitely (until this coroutine is cancelled by the
     * next [requestState]). Implements the `<<<state-N>>>` N>=1 contract:
     * play N times and pause on the last frame.
     */
    private suspend fun playPhaseFinite(
        animName: String,
        phase: Phase,
        times: Int,
    ) {
        val anim = graph.animations[animName] ?: return
        val seq = when (phase) {
            Phase.INTRO -> anim.intro
            Phase.LOOP -> anim.effectiveLoop
            Phase.OUTRO -> anim.outro
        } ?: return
        if (seq.frames.isEmpty()) return
        val frameDelayMs = (1000L / seq.fps).coerceAtLeast(MIN_FRAME_DELAY_MS)
        repeat(times) {
            for (ref in seq.frames) {
                _currentRef.value = ref
                ticker.delay(frameDelayMs)
            }
        }
        _currentRef.value = seq.frames.last()
        awaitCancellation()
    }

    private suspend fun playPhase(
        animName: String,
        phase: Phase,
        loopOverride: LoopMode? = null,
    ) {
        val anim = graph.animations[animName] ?: return
        val seq = when (phase) {
            Phase.INTRO -> anim.intro
            Phase.LOOP -> anim.effectiveLoop
            Phase.OUTRO -> anim.outro
        } ?: return
        if (seq.frames.isEmpty()) {
            return
        }
        val frameDelayMs = (1000L / seq.fps).coerceAtLeast(MIN_FRAME_DELAY_MS)
        val loop = loopOverride ?: seq.loop

        when (loop) {
            LoopMode.ONCE -> {
                for (ref in seq.frames) {
                    _currentRef.value = ref
                    ticker.delay(frameDelayMs)
                }
                if (!seq.holdLastFrame) {
                    _currentRef.value = null
                }
            }
            LoopMode.PING_PONG -> {
                val cap = seq.iterations ?: Int.MAX_VALUE
                var rounds = 0
                while (rounds < cap) {
                    for (ref in seq.frames) {
                        _currentRef.value = ref
                        ticker.delay(frameDelayMs)
                    }
                    for (i in seq.frames.size - 2 downTo 1) {
                        _currentRef.value = seq.frames[i]
                        ticker.delay(frameDelayMs)
                    }
                    rounds++
                }
            }
            LoopMode.INFINITE -> {
                while (true) {
                    for (ref in seq.frames) {
                        _currentRef.value = ref
                        ticker.delay(frameDelayMs)
                    }
                }
            }
        }
    }

    private suspend fun Job.cancelAndJoin() {
        cancel()
        try {
            join()
        } catch (_: Throwable) {
            // Cancellation unwinds through here; swallow so caller's flow continues.
        }
    }

    companion object {
        private const val MIN_FRAME_DELAY_MS = 16L
    }
}
