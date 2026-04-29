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
     * Raw HTTP-fetch of the gateway `/stream/tts` route. Returns the
     * synthesized audio bytes + mime type, or null on any failure so
     * callers can fall back. Internal plumbing for both [fetch] (wear
     * relay, which packages for DataLayer delivery) and phone voice
     * playback (which hands bytes directly to TalkAudioPlayer).
     */
    suspend fun fetchBytes(
        baseUrl: String,
        voiceId: String,
        text: String,
        token: String,
        emotionOverride: EmotionTtsOverride? = null,
        logLabel: String = "chat",
    ): RawTtsAudio? = withContext(Dispatchers.IO) {
        try {
            val url = buildUrl(baseUrl, voiceId, text, emotionOverride)
            val conn = URL(url).openConnection() as HttpURLConnection
            conn.connectTimeout = 5_000
            conn.readTimeout = 30_000
            conn.requestMethod = "GET"
            // Gateway plugin HTTP auth reads the `Authorization` header
            // only — query params are ignored.
            conn.setRequestProperty("Authorization", "Bearer $token")
            val code = conn.responseCode
            if (code != 200) {
                // Read the upstream error body (plugin surfaces
                // `{error: {message, detail}}` on non-200) so the
                // diagnostic panel shows *why* the request failed rather
                // than just the status code. Helps triage whether the
                // plugin is reaching ElevenLabs, whether the API key is
                // valid, quota, malformed request, etc.
                val errBody = try {
                    (conn.errorStream ?: conn.inputStream)?.use {
                        it.readBytes().toString(Charsets.UTF_8).take(200)
                    }
                } catch (_: Throwable) {
                    null
                }
                val detail = errBody?.takeIf { it.isNotBlank() }?.let { " body=$it" } ?: ""
                WearRelayLog.warn(logLabel, "data-plane tts HTTP $code$detail")
                conn.disconnect()
                return@withContext null
            }
            val mime = conn.contentType?.substringBefore(';')?.trim() ?: DEFAULT_MIME
            val bytes = conn.inputStream.use { it.readBytes() }
            conn.disconnect()
            if (bytes.isEmpty()) {
                WearRelayLog.warn(logLabel, "data-plane tts empty body")
                return@withContext null
            }
            WearRelayLog.info(logLabel, "data-plane tts${bytes.size / 1000}KB $mime")
            RawTtsAudio(bytes = bytes, mime = mime)
        } catch (_: UnknownHostException) {
            WearRelayLog.warn(logLabel, "data-plane ttsDNS fail")
            null
        } catch (_: SocketTimeoutException) {
            WearRelayLog.warn(logLabel, "data-plane ttstimeout")
            null
        } catch (e: Throwable) {
            WearRelayLog.warn(logLabel, "data-plane tts: ${e.javaClass.simpleName}")
            null
        }
    }

    /**
     * Fetches TTS audio and packages it for transport to the watch —
     * inline base64 for small clips, DataClient asset ref for large ones.
     * Returns null on any failure.
     */
    suspend fun fetch(
        baseUrl: String,
        voiceId: String,
        text: String,
        token: String,
        emotionOverride: EmotionTtsOverride? = null,
    ): WearTtsDelivery? {
        val raw = fetchBytes(baseUrl, voiceId, text, token, emotionOverride) ?: return null

        // Small audio inlines via MessageClient (fast, no sync wait).
        // Big audio rides DataClient Asset (no 100 KB cap).
        return if (raw.bytes.size < TTS_INLINE_CAP_BYTES) {
            val b64 = Base64.encodeToString(raw.bytes, Base64.NO_WRAP)
            WearTtsDelivery.Inline(audioBase64 = b64, mimeType = raw.mime)
        } else {
            val uploader = assetUploader ?: run {
                WearRelayLog.warn("chat", "audio > cap but no asset uploader configured")
                return null
            }
            val assetId = "tts-${UUID.randomUUID().toString().take(12)}"
            if (uploader.putAsset(assetId, raw.bytes, raw.mime)) {
                WearTtsDelivery.AssetRef(
                    audioAssetRef = "wear-asset:tts:$assetId",
                    mimeType = raw.mime,
                )
            } else {
                null
            }
        }
    }

    private fun buildUrl(
        baseUrl: String,
        voiceId: String,
        text: String,
        emotionOverride: EmotionTtsOverride?,
    ): String {
        val voiceEnc = URLEncoder.encode(voiceId, Charsets.UTF_8.name())
        val textEnc = URLEncoder.encode(text, Charsets.UTF_8.name())
        val base = StringBuilder("${baseUrl.trimEnd('/')}/stream/tts")
            .append("?voice=").append(voiceEnc)
            .append("&text=").append(textEnc)
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
 * Raw TTS audio bytes straight off the gateway's `/stream/tts` stream.
 * Used by both the wear relay path (packaged into [WearTtsDelivery]) and
 * the phone voice path (wrapped as [TalkSpeakAudio] for playback).
 */
internal data class RawTtsAudio(
    val bytes: ByteArray,
    val mime: String,
)

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
