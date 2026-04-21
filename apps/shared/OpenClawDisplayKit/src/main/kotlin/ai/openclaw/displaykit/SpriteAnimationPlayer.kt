package ai.openclaw.displaykit

import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.Job
import kotlinx.coroutines.SupervisorJob
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
     * target state's own loop starts. No-op when already in [target].
     */
    fun requestState(target: String): Job {
        val job = scope.launch {
            if (target == _currentState.value) {
                return@launch
            }
            val previous = _currentState.value
            activeJob?.cancelAndJoin()
            val transition = graph.resolveTransition(previous, target)
            if (transition is TransitionRef.Phase) {
                val resolved = ResolvedTransition.parse(transition.value)
                playPhase(
                    animName = resolved.animation,
                    phase = resolved.phase,
                    loopOverride = LoopMode.ONCE,
                )
            }
            // Crossfade transitions are currently played as an instant swap;
            // the visual blend is a rendering-side concern the consumer
            // applies when the ref changes.
            playState(target, entering = true)
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

    private suspend fun playState(state: String, entering: Boolean) {
        _currentState.value = state
        val anim = graph.animations[state] ?: return
        if (entering && anim.intro != null) {
            playPhase(state, Phase.INTRO)
        }
        // Flat states fall through to `effectiveLoop`; phased states play
        // `loop` here. `outro` fires only on requestState() via transitions.
        playPhase(state, Phase.LOOP)
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
