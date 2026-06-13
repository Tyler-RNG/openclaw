package ai.openclaw.app.voice

import ai.openclaw.app.diag.PhoneDiagLog
import ai.openclaw.app.gateway.GatewaySession
import kotlinx.serialization.Serializable
import kotlinx.serialization.encodeToString
import kotlinx.serialization.json.Json

/** Decoded talk.speak audio bytes plus provider metadata needed for Android playback. */
internal data class TalkSpeakAudio(
  val bytes: ByteArray,
  val provider: String,
  val outputFormat: String?,
  val voiceCompatible: Boolean?,
  val mimeType: String?,
  val fileExtension: String?,
)

/** Result of requesting remote speech synthesis through the gateway. */
internal sealed interface TalkSpeakResult {
  /** Remote synthesis returned audio that Android can route to playback. */
  data class Success(
    val audio: TalkSpeakAudio,
  ) : TalkSpeakResult

  /** Provider or config absence allows Android local TTS to handle the reply. */
  data class FallbackToLocal(
    val message: String,
  ) : TalkSpeakResult

  /** Request, payload, or audio errors that should stay visible to the caller. */
  data class Failure(
    val message: String,
  ) : TalkSpeakResult
}

/**
 * Synthesis abstraction so [TalkModeManager] can have its TTS path injected
 * (e.g. faked in unit tests). Production synthesis flows through [TalkSpeaker];
 * [TalkSpeakRpcClient] implements this for the RPC fallback path.
 */
internal interface TalkSpeechSynthesizing {
  /** Synthesizes assistant text using optional per-utterance talk directives. */
  suspend fun synthesize(
    text: String,
    directive: TalkDirective?,
  ): TalkSpeakResult
}

/**
 * Internal helper: wraps the `talk.speak` gateway RPC and decodes the base64
 * audio payload. Used by [TalkSpeaker] as the RPC fallback when the direct
 * data-plane `/stream/tts` path isn't available. Callers outside this package
 * should go through [TalkSpeaker] rather than using this client directly.
 */
internal class TalkSpeakRpcClient(
  private val session: GatewaySession? = null,
  private val json: Json = Json { ignoreUnknownKeys = true },
  private val requestDetailed: (suspend (String, String, Long) -> GatewaySession.RpcResult)? = null,
) : TalkSpeechSynthesizing {
  override suspend fun synthesize(text: String, directive: TalkDirective?): TalkSpeakResult {
    PhoneDiagLog.outgoing(
      "talk",
      "talk.speak RPC textChars=${text.length}" +
        (directive?.voiceId?.let { " voice=${it.take(8)}" } ?: ""),
    )
    val response =
      try {
        performRequest(
          method = "talk.speak",
          paramsJson = json.encodeToString(TalkSpeakRequest.from(text = text, directive = directive)),
          timeoutMs = 45_000,
        )
      } catch (err: Throwable) {
        val msg = err.message ?: "talk.speak request failed"
        PhoneDiagLog.error("talk", "talk.speak threw: $msg")
        return TalkSpeakResult.Failure(msg)
      }
    if (!response.ok) {
      val error = response.error
      val message = error?.message ?: "talk.speak request failed"
      return if (isFallbackEligible(error)) {
        PhoneDiagLog.warn("talk", "talk.speak → FallbackToLocal: ${message.take(60)}")
        TalkSpeakResult.FallbackToLocal(message)
      } else {
        PhoneDiagLog.error("talk", "talk.speak → Failure: ${message.take(60)}")
        TalkSpeakResult.Failure(message)
      }
    }
    val payload =
      try {
        json.decodeFromString<TalkSpeakResponse>(response.payloadJson ?: "")
      } catch (err: Throwable) {
        val msg = err.message ?: "talk.speak payload invalid"
        PhoneDiagLog.error("talk", "talk.speak payload decode: $msg")
        return TalkSpeakResult.Failure(msg)
      }
    val bytes =
      try {
        android.util.Base64.decode(payload.audioBase64, android.util.Base64.DEFAULT)
      } catch (err: Throwable) {
        val msg = err.message ?: "talk.speak audio decode failed"
        PhoneDiagLog.error("talk", "talk.speak audio decode: $msg")
        return TalkSpeakResult.Failure(msg)
      }
    if (bytes.isEmpty()) {
      PhoneDiagLog.error("talk", "talk.speak returned empty audio")
      return TalkSpeakResult.Failure("talk.speak returned empty audio")
    }
    PhoneDiagLog.incoming(
      "talk",
      "talk.speak ok ${bytes.size / 1000}KB ${payload.mimeType ?: "?"} via ${payload.provider}",
    )
    return TalkSpeakResult.Success(
      TalkSpeakAudio(
        bytes = bytes,
        provider = payload.provider,
        outputFormat = payload.outputFormat,
        voiceCompatible = payload.voiceCompatible,
        mimeType = payload.mimeType,
        fileExtension = payload.fileExtension,
      ),
    )
  }

  private fun isFallbackEligible(error: GatewaySession.ErrorShape?): Boolean {
    val reason = error?.details?.reason
    if (reason == null) return true
    // Only provider/config absence should fall back to Android TTS; payload and
    // transport errors should stay visible to the caller.
    return reason == "talk_unconfigured" ||
      reason == "talk_provider_unsupported" ||
      reason == "method_unavailable"
  }

  private suspend fun performRequest(
    method: String,
    paramsJson: String,
    timeoutMs: Long,
  ): GatewaySession.RpcResult {
    requestDetailed?.let { return it(method, paramsJson, timeoutMs) }
    val activeSession = session ?: throw IllegalStateException("session missing")
    return activeSession.requestDetailed(method = method, paramsJson = paramsJson, timeoutMs = timeoutMs)
  }
}

/** Gateway talk.speak request payload assembled from text plus directive overrides. */
@Serializable
internal data class TalkSpeakRequest(
  val text: String,
  val voiceId: String? = null,
  val modelId: String? = null,
  val outputFormat: String? = null,
  val speed: Double? = null,
  val rateWpm: Int? = null,
  val stability: Double? = null,
  val similarity: Double? = null,
  val style: Double? = null,
  val speakerBoost: Boolean? = null,
  val seed: Long? = null,
  val normalize: String? = null,
  val language: String? = null,
  val latencyTier: Int? = null,
) {
  companion object {
    /** Converts parsed inline talk directives into the gateway RPC payload shape. */
    fun from(
      text: String,
      directive: TalkDirective?,
    ): TalkSpeakRequest =
      TalkSpeakRequest(
        text = text,
        voiceId = directive?.voiceId,
        modelId = directive?.modelId,
        outputFormat = directive?.outputFormat,
        speed = directive?.speed,
        rateWpm = directive?.rateWpm,
        stability = directive?.stability,
        similarity = directive?.similarity,
        style = directive?.style,
        speakerBoost = directive?.speakerBoost,
        seed = directive?.seed,
        normalize = directive?.normalize,
        language = directive?.language,
        latencyTier = directive?.latencyTier,
      )
  }
}

@Serializable
private data class TalkSpeakResponse(
  val audioBase64: String,
  val provider: String,
  val outputFormat: String? = null,
  val voiceCompatible: Boolean? = null,
  val mimeType: String? = null,
  val fileExtension: String? = null,
)
