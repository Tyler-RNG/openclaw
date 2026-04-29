package ai.openclaw.wear

import android.content.Context
import android.speech.tts.TextToSpeech
import android.speech.tts.UtteranceProgressListener
import android.util.Log
import java.util.Locale
import java.util.UUID
import java.util.concurrent.atomic.AtomicReference
import kotlinx.coroutines.CompletableDeferred
import kotlinx.coroutines.withTimeoutOrNull

/**
 * Thin wrapper around Android's [TextToSpeech] for the watch fallback path.
 *
 * - Initializes asynchronously (TextToSpeech has a callback-driven init).
 * - Exposes [speak] as a suspending call that waits for the utterance to
 *   finish or error out.
 * - Accepts a per-call speed hint so emotion directives that carry a
 *   `speed` field (Phase 4 / SpriteCore `emotions[state].directive.speed`)
 *   can tweak playback rate. Other emotion fields don't map to
 *   Android's built-in TTS and are ignored on this path.
 */
internal class LocalTtsEngine(context: Context) {
    private val tts: TextToSpeech
    @Volatile private var ready: Boolean = false
    private val activeUtterance = AtomicReference<Pair<String, CompletableDeferred<Boolean>>?>()

    init {
        tts = TextToSpeech(context.applicationContext) { status ->
            if (status == TextToSpeech.SUCCESS) {
                tts.language = Locale.US
                tts.setOnUtteranceProgressListener(object : UtteranceProgressListener() {
                    override fun onStart(utteranceId: String?) { /* no-op */ }

                    override fun onDone(utteranceId: String?) {
                        completeActive(utteranceId, true)
                    }

                    @Deprecated("Deprecated in API 21, still required by the AOSP abstract class")
                    override fun onError(utteranceId: String?) {
                        completeActive(utteranceId, false)
                    }

                    override fun onError(utteranceId: String?, errorCode: Int) {
                        completeActive(utteranceId, false)
                    }
                })
                ready = true
            } else {
                ready = false
                Log.w(TAG, "TextToSpeech init failed status=$status")
            }
        }
    }

    /**
     * Speak [text] with an optional playback-rate override. Suspends until
     * the utterance completes (done / error / timeout). Returns true if the
     * engine reported onDone; false if it failed to start, errored, or
     * timed out.
     */
    suspend fun speak(text: String, speed: Double? = null, timeoutMs: Long = 30_000): Boolean {
        if (!ready) return false
        val trimmed = text.trim()
        if (trimmed.isEmpty()) return true

        val id = "wear-tts-${UUID.randomUUID()}"
        val deferred = CompletableDeferred<Boolean>()
        // Cancel any previous utterance waiter so a rapid speak() sequence
        // doesn't leave orphan deferreds hanging.
        activeUtterance.getAndSet(id to deferred)?.second?.complete(false)

        val clampedSpeed = (speed ?: 1.0).coerceIn(0.25, 4.0).toFloat()
        tts.setSpeechRate(clampedSpeed)

        val queueResult = tts.speak(trimmed, TextToSpeech.QUEUE_FLUSH, null, id)
        if (queueResult != TextToSpeech.SUCCESS) {
            activeUtterance.compareAndSet(id to deferred, null)
            return false
        }

        return withTimeoutOrNull(timeoutMs) { deferred.await() } ?: run {
            activeUtterance.compareAndSet(id to deferred, null)
            false
        }
    }

    /** Stop any in-flight utterance. Safe to call when not speaking. */
    fun stop() {
        runCatching { tts.stop() }
        activeUtterance.getAndSet(null)?.second?.complete(false)
    }

    fun shutdown() {
        stop()
        runCatching { tts.shutdown() }
    }

    private fun completeActive(utteranceId: String?, success: Boolean) {
        val active = activeUtterance.get() ?: return
        if (active.first != utteranceId) return
        if (activeUtterance.compareAndSet(active, null)) {
            active.second.complete(success)
        }
    }

    companion object {
        private const val TAG = "LocalTtsEngine"
    }
}
