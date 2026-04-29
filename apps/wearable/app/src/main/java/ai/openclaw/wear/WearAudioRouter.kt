package ai.openclaw.wear

import android.content.Context
import android.media.MediaPlayer
import android.util.Base64
import android.util.Log
import java.io.File
import kotlinx.coroutines.CompletableDeferred
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext
import kotlinx.coroutines.withTimeoutOrNull

/**
 * Single TTS playback entry point for the wear app.
 *
 * Before this class existed, `WearViewModel` carried three near-identical
 * playback paths — `playAssetAudio`, `playStreamedAudio`, `playElevenLabsAudio` —
 * each spinning up its own [MediaPlayer] lifecycle and falling back to
 * local [LocalTtsEngine] independently. The logic was ~160 LOC of duplicated
 * prepare/start/error/release plumbing.
 *
 * This class collapses them:
 * - One [MediaPlayer] lifecycle at a time, owned by the router.
 * - [play] is `suspend`: it returns when playback completes (or falls
 *   back to local TTS), so segmented replies from `audioSegments` can be
 *   awaited sequentially.
 * - Resolution order for a segment source falls through the list in
 *   [play] — inline / URL / asset / local TTS / silence.
 */
internal sealed interface WearPlaybackSource {
    /**
     * Audio delivered as a DataClient asset reference. The ref format is
     * `wear-asset:tts:<assetId>`; the router resolves it by awaiting the
     * `WearAssetStore` TTS cache.
     */
    data class DataClientAsset(
        val ref: String,
        val mime: String,
        val fallbackText: String,
    ) : WearPlaybackSource

    /**
     * Audio the MediaPlayer can stream directly (e.g. a gateway
     * `/stream/tts` URL the watch can reach).
     */
    data class StreamingUrl(val url: String, val mime: String) : WearPlaybackSource

    /**
     * Audio bytes inlined in the chat-reply message (base64-decoded).
     */
    data class InlineBytes(val bytes: ByteArray, val mime: String) : WearPlaybackSource

    /** Plain text speak via on-device [android.speech.tts.TextToSpeech]. */
    data class LocalTts(val text: String, val speed: Double? = null) : WearPlaybackSource
}

internal class WearAudioRouter(
    private val context: Context,
    private val assetStore: WearAssetStore,
    private val localTts: LocalTtsEngine,
) {
    private var mediaPlayer: MediaPlayer? = null

    /**
     * Play [source], suspending until playback ends. When a [MediaPlayer]-
     * backed source fails (missing asset, stream error, decode error), the
     * router falls back to [fallbackText] via [LocalTtsEngine] when
     * provided; otherwise returns `false`.
     *
     * Thread-safety: only safe for one concurrent caller. The watch UI
     * funnels playback through the ViewModel which enforces a single
     * active utterance at a time.
     */
    suspend fun play(
        source: WearPlaybackSource,
        fallbackText: String? = null,
    ): Boolean {
        return when (source) {
            is WearPlaybackSource.InlineBytes -> playInlineBytes(source, fallbackText)
            is WearPlaybackSource.StreamingUrl -> playStreamingUrl(source, fallbackText)
            is WearPlaybackSource.DataClientAsset -> playDataClientAsset(source)
            is WearPlaybackSource.LocalTts -> {
                // Phone shipped text without audio — either ElevenLabs failed
                // on the phone side or the reply is intentionally text-only.
                // Either way this is the explicit offline fallback path.
                Log.w(TAG, "local android tts (no audio from phone): chars=${source.text.length}")
                localTts.speak(source.text, source.speed)
            }
        }
    }

    /** Stop the active MediaPlayer (and local TTS). Safe when idle. */
    fun stop() {
        runCatching { mediaPlayer?.stop() }
        mediaPlayer?.release()
        mediaPlayer = null
        localTts.stop()
    }

    private suspend fun playInlineBytes(
        source: WearPlaybackSource.InlineBytes,
        fallbackText: String?,
    ): Boolean {
        val tmpFile = try {
            val ext = mimeExtension(source.mime)
            val file = File.createTempFile("tts-inline", ext, context.cacheDir)
            file.writeBytes(source.bytes)
            file
        } catch (e: Throwable) {
            Log.w(TAG, "inline bytes temp-file failed: ${e.message}")
            return fallbackToLocal(fallbackText)
        }
        return playFile(tmpFile.absolutePath, cleanup = { tmpFile.delete() }, fallbackText = fallbackText)
    }

    private suspend fun playStreamingUrl(
        source: WearPlaybackSource.StreamingUrl,
        fallbackText: String?,
    ): Boolean = playFile(source.url, cleanup = null, fallbackText = fallbackText, prepareAsync = true)

    private suspend fun playDataClientAsset(
        source: WearPlaybackSource.DataClientAsset,
    ): Boolean {
        val assetId = source.ref.removePrefix("wear-asset:tts:")
        val bytes = assetStore.awaitTts(assetId, timeoutMs = 20_000)
        if (bytes == null) {
            Log.w(TAG, "tts asset $assetId never arrived; falling back to local TTS")
            return fallbackToLocal(source.fallbackText)
        }
        val tmpFile = try {
            val ext = mimeExtension(source.mime)
            val file = File.createTempFile("tts-asset", ext, context.cacheDir)
            file.writeBytes(bytes)
            file
        } catch (e: Throwable) {
            Log.w(TAG, "asset temp-file failed: ${e.message}")
            return fallbackToLocal(source.fallbackText)
        }
        return playFile(tmpFile.absolutePath, cleanup = { tmpFile.delete() }, fallbackText = source.fallbackText)
    }

    /**
     * Core [MediaPlayer] runner. Sets up the player, waits for completion
     * or error, cleans up. Suspends until playback ends (or fails over to
     * the local-TTS fallback).
     */
    private suspend fun playFile(
        dataSource: String,
        cleanup: (() -> Unit)?,
        fallbackText: String?,
        prepareAsync: Boolean = false,
    ): Boolean {
        stop()
        val completion = CompletableDeferred<Boolean>()
        val player = try {
            MediaPlayer().apply {
                setDataSource(dataSource)
                setOnCompletionListener {
                    if (completion.isActive) completion.complete(true)
                }
                setOnErrorListener { _, what, extra ->
                    Log.w(TAG, "MediaPlayer error what=$what extra=$extra source=$dataSource")
                    if (completion.isActive) completion.complete(false)
                    true
                }
                if (prepareAsync) {
                    setOnPreparedListener { it.start() }
                    prepareAsync()
                } else {
                    prepare()
                    start()
                }
            }
        } catch (e: Throwable) {
            Log.w(TAG, "MediaPlayer setup failed: ${e.message}")
            cleanup?.invoke()
            return fallbackToLocal(fallbackText)
        }
        mediaPlayer = player

        val succeeded = try {
            // Cap wait: a broken MediaPlayer can leave a suspend hanging.
            withTimeoutOrNull(PLAYBACK_TIMEOUT_MS) { completion.await() } ?: run {
                Log.w(TAG, "MediaPlayer playback timed out source=$dataSource")
                false
            }
        } finally {
            withContext(Dispatchers.Default) {
                runCatching { player.stop() }
                player.release()
                if (mediaPlayer === player) mediaPlayer = null
                cleanup?.invoke()
            }
        }

        return if (!succeeded) fallbackToLocal(fallbackText) else true
    }

    private suspend fun fallbackToLocal(fallbackText: String?): Boolean {
        val text = fallbackText?.takeIf { it.isNotBlank() } ?: return false
        // Distinguishes "ElevenLabs pipeline failed" from "text-only reply
        // with no audio intended." The phone always attempts ElevenLabs
        // first; reaching this fallback means either a relay failure or the
        // watch is offline / disconnected from the phone.
        Log.w(TAG, "elevenlabs pipeline failed → local android tts (offline fallback)")
        return localTts.speak(text)
    }

    private fun mimeExtension(mime: String): String = when {
        mime.contains("mpeg", ignoreCase = true) -> ".mp3"
        mime.contains("wav", ignoreCase = true) -> ".wav"
        mime.contains("ogg", ignoreCase = true) -> ".ogg"
        else -> ".bin"
    }

    companion object {
        private const val TAG = "WearAudioRouter"
        private const val PLAYBACK_TIMEOUT_MS = 60_000L
    }
}

/**
 * Parse the inline base64 audio from a chat reply payload. Returns null
 * when [base64] is null, empty, or not valid base64.
 */
internal fun decodeInlineAudio(base64: String?): ByteArray? {
    if (base64.isNullOrBlank()) return null
    return try {
        Base64.decode(base64, Base64.DEFAULT).takeIf { it.isNotEmpty() }
    } catch (_: Throwable) {
        null
    }
}
