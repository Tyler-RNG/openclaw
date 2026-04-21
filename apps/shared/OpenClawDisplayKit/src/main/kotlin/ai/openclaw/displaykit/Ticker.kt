package ai.openclaw.displaykit

import kotlinx.coroutines.delay

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
            delay(ms)
        }
    }
}
