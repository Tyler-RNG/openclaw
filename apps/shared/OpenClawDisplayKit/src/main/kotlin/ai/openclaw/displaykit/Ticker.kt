package ai.openclaw.displaykit

import kotlinx.coroutines.delay as coroutineDelay

/**
 * Wall clock + scheduler injected into the player so tests can drive playback
 * deterministically without real delays. Production code uses [SystemTicker].
 */
interface Ticker {
    /** Current monotonic time in milliseconds. */
    fun nowMs(): Long

    /** Suspend for [ms]; clamped to >= 0 at the implementation. */
    suspend fun delay(ms: Long)
}

/** Default production ticker: backed by [System.currentTimeMillis] + coroutine delay. */
class SystemTicker : Ticker {
    override fun nowMs(): Long = System.currentTimeMillis()
    override suspend fun delay(ms: Long) {
        if (ms > 0L) {
            // Aliased import — an unqualified `delay(ms)` resolves to this
            // enclosing member and self-recurses until StackOverflowError.
            // Tests couldn't catch it because they inject a TestTicker; only
            // the production SystemTicker path ever exercises this call.
            coroutineDelay(ms)
        }
    }
}
