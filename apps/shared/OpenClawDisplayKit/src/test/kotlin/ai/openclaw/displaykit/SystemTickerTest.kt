package ai.openclaw.displaykit

import kotlinx.coroutines.runBlocking
import kotlin.test.Test
import kotlin.test.assertTrue

/**
 * Regression test for the member-shadowing bug in SystemTicker.delay(): an
 * unqualified call to `delay(ms)` inside the overridden method resolves to
 * the same member and self-recurses until StackOverflowError. The bug is
 * production-only because the rest of the suite injects TestTicker, so
 * this file exists explicitly to exercise the real class with real time.
 *
 * Keep short delays here (single-digit ms) so the suite stays fast.
 */
class SystemTickerTest {
    @Test
    fun systemTickerDelayDoesNotSelfRecurse() = runBlocking {
        val ticker = SystemTicker()
        val start = ticker.nowMs()
        ticker.delay(5L)
        val elapsed = ticker.nowMs() - start
        // We only care that it returns without StackOverflowError. Lower bound
        // is loose because the scheduler isn't guaranteed-punctual; upper
        // bound guards against a delay that accidentally dropped into a busy
        // loop one day.
        assertTrue(elapsed in 0..500, "unexpected elapsed=$elapsed ms for a 5ms delay")
    }

    @Test
    fun systemTickerDelayZeroOrNegativeIsNoop() = runBlocking {
        val ticker = SystemTicker()
        val start = ticker.nowMs()
        ticker.delay(0L)
        ticker.delay(-1L)
        val elapsed = ticker.nowMs() - start
        assertTrue(elapsed < 100, "noop delays shouldn't actually sleep, got $elapsed ms")
    }
}
