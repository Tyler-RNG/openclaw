package ai.openclaw.app.voice

import android.util.Base64
import ai.openclaw.app.wear.WearRelayLog
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext
import java.net.HttpURLConnection
import java.net.SocketTimeoutException
import java.net.URL
import java.net.URLEncoder
import java.net.UnknownHostException
import java.util.UUID

/**
 * Direct-fetch client for the gateway's `/stream/tts` route (served by the
 * SpriteCore plugin). The phone is on the same network as the gateway; the
 * watch isn't, so the phone fetches audio directly and relays it through the
 * wearable data layer instead of round-tripping through the `talk.speak` RPC.
 *
 * Internal to [TalkSpeaker] — callers shouldn't depend on this class
 * directly. Pairs with [TtsAssetUploader] for audio too large to inline.
 */
internal class TalkDataPlaneTtsFetcher(
    private val assetUploader: TtsAssetUploader?,
) {
    /**
     * Fetches TTS audio from the gateway's data-plane `/stream/tts` route and
     * packages the result for transport to the watch — inline for small
     * clips, or via a DataClient asset ref for large ones.
     *
     * Returns `null` when the fetch fails (DNS, timeout, non-200, empty
     * body) so the caller can fall back to the RPC path.
     */
    suspend fun fetch(
        baseUrl: String,
        voiceId: String,
        text: String,
        token: String,
        emotionOverride: EmotionTtsOverride? = null,
    ): WearTtsDelivery? = withContext(Dispatchers.IO) {
        try {
            val url = buildUrl(baseUrl, voiceId, text, token, emotionOverride)
            val conn = URL(url).openConnection() as HttpURLConnection
            conn.connectTimeout = 5_000
            conn.readTimeout = 30_000
            conn.requestMethod = "GET"
            val code = conn.responseCode
            if (code != 200) {
                WearRelayLog.warn("chat", "data-plane tts HTTP $code")
                conn.disconnect()
                return@withContext null
            }
            val mime = conn.contentType?.substringBefore(';')?.trim() ?: DEFAULT_MIME
            val bytes = conn.inputStream.use { it.readBytes() }
            conn.disconnect()
            if (bytes.isEmpty()) {
                WearRelayLog.warn("chat", "data-plane tts empty body")
                return@withContext null
            }
            WearRelayLog.info("chat", "data-plane tts${bytes.size / 1000}KB $mime")

            // Small audio inlines via MessageClient (fast, no sync wait).
            // Big audio rides DataClient Asset (no 100 KB cap).
            if (bytes.size < TTS_INLINE_CAP_BYTES) {
                val b64 = Base64.encodeToString(bytes, Base64.NO_WRAP)
                WearTtsDelivery.Inline(audioBase64 = b64, mimeType = mime)
            } else {
                val uploader = assetUploader ?: run {
                    WearRelayLog.warn("chat", "audio > cap but no asset uploader configured")
                    return@withContext null
                }
                val assetId = "tts-${UUID.randomUUID().toString().take(12)}"
                if (uploader.putAsset(assetId, bytes, mime)) {
                    WearTtsDelivery.AssetRef(
                        audioAssetRef = "wear-asset:tts:$assetId",
                        mimeType = mime,
                    )
                } else {
                    null
                }
            }
        } catch (_: UnknownHostException) {
            WearRelayLog.warn("chat", "data-plane ttsDNS fail")
            null
        } catch (_: SocketTimeoutException) {
            WearRelayLog.warn("chat", "data-plane ttstimeout")
            null
        } catch (e: Throwable) {
            WearRelayLog.warn("chat", "data-plane tts: ${e.javaClass.simpleName}")
            null
        }
    }

    private fun buildUrl(
        baseUrl: String,
        voiceId: String,
        text: String,
        token: String,
        emotionOverride: EmotionTtsOverride?,
    ): String {
        val voiceEnc = URLEncoder.encode(voiceId, Charsets.UTF_8.name())
        val textEnc = URLEncoder.encode(text, Charsets.UTF_8.name())
        val tokenEnc = URLEncoder.encode(token, Charsets.UTF_8.name())
        val base = StringBuilder("${baseUrl.trimEnd('/')}/stream/tts")
            .append("?voice=").append(voiceEnc)
            .append("&text=").append(textEnc)
            .append("&token=").append(tokenEnc)
        if (emotionOverride != null) {
            emotionOverride.stability?.let { base.append("&stability=").append(it) }
            emotionOverride.similarity?.let { base.append("&similarity=").append(it) }
            emotionOverride.style?.let { base.append("&style=").append(it) }
            emotionOverride.speakerBoost?.let { base.append("&speaker_boost=").append(it) }
        }
        return base.toString()
    }

    companion object {
        /** Audio under this size rides inline via the reply MessageClient message. */
        internal const val TTS_INLINE_CAP_BYTES = 60_000
        private const val DEFAULT_MIME = "audio/mpeg"
    }
}

/**
 * Emotion-driven overrides for the `/stream/tts` request. Added in Phase 3 as
 * a forward-compatible shape; Phase 4 wires these through from the avatar
 * source based on the `<<<state>>>` marker that preceded a text segment.
 */
internal data class EmotionTtsOverride(
    val stability: Double? = null,
    val similarity: Double? = null,
    val style: Double? = null,
    val speakerBoost: Boolean? = null,
)

/**
 * Packaged TTS audio ready to hand to the wear relay. The wear relay carries
 * one of these in its `WearChatPart` for delivery to the watch.
 */
internal sealed interface WearTtsDelivery {
    val mimeType: String

    /** Audio bytes base64-encoded inline in the reply message (small clips). */
    data class Inline(val audioBase64: String, override val mimeType: String) : WearTtsDelivery

    /** Audio delivered via DataClient asset; the watch fetches by ref. */
    data class AssetRef(val audioAssetRef: String, override val mimeType: String) : WearTtsDelivery

    /** Audio available via an HTTP URL the watch fetches directly. */
    data class StreamingUrl(val audioUrl: String, override val mimeType: String) : WearTtsDelivery
}

/**
 * Hook for uploading TTS asset bytes through the Wearable DataClient. The
 * phone app provides an implementation wired to its `Wearable.getDataClient`;
 * unit tests can substitute a fake.
 */
internal interface TtsAssetUploader {
    suspend fun putAsset(assetId: String, bytes: ByteArray, mime: String): Boolean
}
