package ai.openclaw.app.voice

import ai.openclaw.app.diag.PhoneDiagLog
import ai.openclaw.app.gateway.GatewaySession
import ai.openclaw.app.wear.WearRelayLog
import kotlinx.coroutines.sync.Mutex
import kotlinx.coroutines.sync.withLock
import kotlinx.serialization.json.Json
import kotlinx.serialization.json.JsonElement
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.JsonPrimitive
import kotlinx.serialization.json.buildJsonObject

/**
 * Single entry point for text-to-speech synthesis on Android.
 *
 * Before this class existed, the phone voice path (TalkModeManager) and the
 * wear relay path (NodeRuntime) each had their own TTS plumbing — two RPC
 * call sites, two size caps, two fallback ladders. This class owns both so
 * future emotion-driven TTS tuning (Phase 4) lands in one place instead of
 * two.
 *
 * Per-agent voice + emotion configuration comes from the gateway's
 * `sprite-core.agents` RPC method (registered by the SpriteCore plugin;
 * mirrored from the `GET /sprite-core/agents` HTTP endpoint). Core dropped
 * `voice` from its agent row — this is the only supported source now.
 * Snapshot is fetched lazily on first need and cached; call
 * [invalidateAgentsCache] after a reconnect or agents.list refresh.
 *
 * - [synthesizeForPhone] — in-process voice mode. Returns audio bytes plus
 *   metadata ready for [TalkAudioPlayer]. Uses the `talk.speak` RPC.
 * - [synthesizeForWearRelay] — phone relays TTS to the watch. Prefers the
 *   direct `/stream/tts` data-plane (served by the SpriteCore plugin, uses
 *   the agent's configured voice) and falls back to the `talk.speak` RPC.
 *   Returns a [WearTtsDelivery] ready to stuff into the wear chat part.
 *
 * The [emotion] parameter on both entry points is plumbed for Phase 4; for
 * Phase 3 callers pass `null` and the parameter is ignored. Once wired, the
 * emotion state maps to a TalkDirective override (via [resolveEmotion]) that
 * merges field-by-field with the base directive / agent voice.
 */
internal class TalkSpeaker(
    private val rpcClient: TalkSpeakRpcClient,
    private val dataPlaneFetcher: TalkDataPlaneTtsFetcher,
    private val session: GatewaySession,
    private val dataPlaneLookup: () -> TalkSpeakerDataPlane?,
    private val authTokenLookup: () -> String?,
    private val json: Json = Json { ignoreUnknownKeys = true },
) {
    private val cacheMutex = Mutex()
    @Volatile private var cachedSnapshot: SpriteCoreAgentsSnapshot? = null
    @Volatile private var fetchAttempted: Boolean = false
    @Volatile private var fetchFailed: Boolean = false

    /**
     * Resets the cached `sprite-core.agents` snapshot. Call after the
     * operator session reconnects or when the agents.list refreshes so the
     * next TTS turn picks up fresh voice / emotion config.
     */
    suspend fun invalidateAgentsCache() {
        cacheMutex.withLock {
            cachedSnapshot = null
            fetchAttempted = false
            fetchFailed = false
        }
    }

    /**
     * Phone voice-mode entry point. Produces audio for local playback by
     * [TalkAudioPlayer]. `TalkSpeakResult.FallbackToLocal` signals the
     * caller to use system TTS (for unconfigured/unsupported providers);
     * `Failure` is a hard error.
     *
     * When [agentId] + [emotion] are set and the agent has a matching
     * emotion entry, the per-state TTS directive override is merged into
     * [baseDirective] (emotion wins on fields it sets).
     */
    suspend fun synthesizeForPhone(
        text: String,
        baseDirective: TalkDirective?,
        agentId: String? = null,
        emotion: String? = null,
    ): TalkSpeakResult {
        val emotionDirective = if (agentId != null && emotion != null) {
            resolveEmotion(agentId, emotion)
        } else {
            null
        }
        val effectiveDirective = mergeWithEmotion(baseDirective, emotionDirective)
        // Strip XML/HTML markup the model sometimes leaves in reply text so the
        // TTS engine doesn't pronounce "less than sign" etc. See TtsTextSanitizer.
        val cleanText = sanitizeTextForTts(text)
        val synthText = applyAudioTag(cleanText, emotionDirective)

        // Prefer the direct data-plane `/stream/tts` path when the agent has
        // an ElevenLabs voice configured in SpriteCore + the plugin exposes
        // `streamTts` + we have an operator auth token. This produces the
        // SAME audio quality the wear relay gets (agent's actual voice,
        // emotion directives applied) instead of whatever default voice the
        // core `talk.speak` RPC falls back to on plugin-migrated gateways.
        val agentVoice = if (agentId != null) resolveVoice(agentId) else null
        return when (
            val outcome = tryPhoneDataPlaneFetch(
                text = synthText,
                baseDirective = effectiveDirective,
                agentVoice = agentVoice,
                emotionDirective = emotionDirective,
            )
        ) {
            is PhoneDirectOutcome.Synthesized -> outcome.result
            // ElevenLabs was configured but the direct fetch failed (network
            // / HTTP error). Don't call `talk.speak`, which would synthesize
            // with a wrong default voice on plugin-migrated gateways. Let the
            // caller speak through Android's system TTS instead, so playback
            // is audibly a fallback rather than a silent voice swap.
            is PhoneDirectOutcome.DirectUnreachable -> {
                PhoneDiagLog.warn("talk", "elevenlabs unreachable → android system tts fallback")
                TalkSpeakResult.FallbackToLocal("elevenlabs unreachable")
            }
            // ElevenLabs wasn't configured (legacy / non-ElevenLabs provider).
            // Use the `talk.speak` RPC as before — that's the supported path
            // for non-plugin-migrated gateways.
            PhoneDirectOutcome.NotConfigured ->
                rpcClient.synthesize(text = synthText, directive = effectiveDirective)
        }
    }

    /**
     * Attempts the direct data-plane `/stream/tts` fetch for phone voice
     * playback. Returns:
     *  - [PhoneDirectOutcome.Synthesized] when the ElevenLabs fetch returned
     *    audio bytes.
     *  - [PhoneDirectOutcome.DirectUnreachable] when ElevenLabs *was* the
     *    configured provider + voice but the fetch failed (network / HTTP
     *    error / unreadable body). The caller must NOT fall back to
     *    `talk.speak` because that RPC uses a different default voice on
     *    plugin-migrated gateways.
     *  - [PhoneDirectOutcome.NotConfigured] when no direct-path attempt was
     *    appropriate (plugin not reachable, streamTts off, non-ElevenLabs
     *    provider, missing auth token or voiceId). The caller falls back to
     *    the `talk.speak` RPC for legacy / non-ElevenLabs setups.
     */
    private suspend fun tryPhoneDataPlaneFetch(
        text: String,
        baseDirective: TalkDirective?,
        agentVoice: TalkSpeakerAgentVoice?,
        emotionDirective: SpriteCoreEmotionDirective?,
    ): PhoneDirectOutcome {
        val dataPlane = dataPlaneLookup()
        if (dataPlane == null) {
            PhoneDiagLog.warn("talk", "direct path skipped: dataPlane null")
            return PhoneDirectOutcome.NotConfigured
        }
        if (!dataPlane.streamTtsEnabled) {
            PhoneDiagLog.warn("talk", "direct path skipped: streamTts disabled")
            return PhoneDirectOutcome.NotConfigured
        }
        val token = authTokenLookup()?.takeIf { it.isNotEmpty() }
        if (token == null) {
            PhoneDiagLog.warn("talk", "direct path skipped: no auth token")
            return PhoneDirectOutcome.NotConfigured
        }

        val wantsElevenLabs = agentVoice?.provider.equals("elevenlabs", ignoreCase = true)
        if (wantsElevenLabs != true) {
            PhoneDiagLog.info(
                "talk",
                "direct path skipped: provider=${agentVoice?.provider ?: "null"}",
            )
            return PhoneDirectOutcome.NotConfigured
        }

        val voiceId = emotionDirective?.voiceId?.takeIf { it.isNotBlank() }
            ?: baseDirective?.voiceId?.takeIf { it.isNotBlank() }
            ?: agentVoice?.voiceId?.takeIf { it.isNotBlank() }
        if (voiceId.isNullOrBlank()) {
            PhoneDiagLog.warn("talk", "direct path skipped: no voiceId")
            return PhoneDirectOutcome.NotConfigured
        }

        PhoneDiagLog.info(
            "talk",
            "direct /stream/tts voice=${voiceId.take(8)} textChars=${text.length}" +
                (emotionDirective?.audioTag?.let { " tag=$it" } ?: ""),
        )
        val raw = dataPlaneFetcher.fetchBytes(
            baseUrl = dataPlane.baseUrl,
            voiceId = voiceId,
            text = text,
            token = token,
            emotionOverride = emotionDirective?.toWireOverride(),
            logLabel = "talk",
        )
        if (raw == null) {
            PhoneDiagLog.warn("talk", "direct /stream/tts failed (elevenlabs unreachable)")
            return PhoneDirectOutcome.DirectUnreachable
        }

        PhoneDiagLog.incoming(
            "talk",
            "direct /stream/tts ok ${raw.bytes.size / 1000}KB ${raw.mime}",
        )
        return PhoneDirectOutcome.Synthesized(
            TalkSpeakResult.Success(
                TalkSpeakAudio(
                    bytes = raw.bytes,
                    provider = "elevenlabs",
                    outputFormat = null,
                    voiceCompatible = true,
                    mimeType = raw.mime,
                    fileExtension = mimeToExtension(raw.mime),
                ),
            ),
        )
    }

    private fun mimeToExtension(mime: String): String? = when {
        mime.contains("mpeg", ignoreCase = true) -> "mp3"
        mime.contains("wav", ignoreCase = true) -> "wav"
        mime.contains("ogg", ignoreCase = true) -> "ogg"
        else -> null
    }

    /**
     * Wear-relay entry point. Picks the fast direct path when the agent has
     * a voice configured in the SpriteCore plugin + data-plane `/stream/tts`
     * is available; otherwise falls back to `talk.speak`. Returns `null`
     * when no path produces audio — the caller ships the text without audio
     * and the watch can fall back to local Android TextToSpeech.
     *
     * When [emotion] matches a configured emotion entry for the agent, the
     * per-state directive is applied as query params on the data-plane
     * request. An emotion entry with its own `voiceId` replaces the agent's
     * base voice for that segment.
     */
    suspend fun synthesizeForWearRelay(
        text: String,
        agentId: String,
        emotion: String? = null,
    ): WearTtsDelivery? {
        val agentVoice = resolveVoice(agentId)
        val emotionDirective = emotion?.let { resolveEmotion(agentId, it) }
        val effectiveVoiceId = emotionDirective?.voiceId?.takeIf { it.isNotBlank() }
            ?: agentVoice?.voiceId
        // Same sanitize as synthesizeForPhone — watch relay ships the text
        // to the same ElevenLabs / talk.speak endpoints, so the fix applies.
        val cleanText = sanitizeTextForTts(text)
        val synthText = applyAudioTag(cleanText, emotionDirective)
        val dataPlane = dataPlaneLookup()
        val token = authTokenLookup()

        val wantsElevenLabs = agentVoice?.provider.equals("elevenlabs", ignoreCase = true)
        val canFetchDirect = wantsElevenLabs &&
            !effectiveVoiceId.isNullOrBlank() &&
            dataPlane?.streamTtsEnabled == true &&
            !token.isNullOrEmpty()

        if (canFetchDirect) {
            WearRelayLog.info(
                "chat",
                "data-plane tts: $agentId voice=${effectiveVoiceId!!.take(8)}" +
                    (emotion?.let { " emotion=$it" } ?: "") +
                    (emotionDirective?.audioTag?.let { " tag=$it" } ?: ""),
            )
            val direct = dataPlaneFetcher.fetch(
                baseUrl = dataPlane!!.baseUrl,
                voiceId = effectiveVoiceId,
                text = synthText,
                token = token!!,
                emotionOverride = emotionDirective?.toWireOverride(),
            )
            if (direct != null) return direct
            // ElevenLabs was the intended path but failed. Don't fall through
            // to `talk.speak`, which on plugin-migrated gateways uses a default
            // (non-ElevenLabs) voice — returning null lets the watch fall back
            // to its local Android TextToSpeech cleanly instead of shipping a
            // wrong voice that sounds like it worked.
            WearRelayLog.warn("chat", "elevenlabs unreachable → watch local tts fallback")
            return null
        }

        val reason = buildString {
            if (wantsElevenLabs != true) append("provider=${agentVoice?.provider ?: "null"} ")
            if (effectiveVoiceId.isNullOrBlank()) append("voiceId=null ")
            if (dataPlane?.streamTtsEnabled != true) append("streamTts=${dataPlane?.streamTtsEnabled} ")
            if (token.isNullOrEmpty()) append("noToken ")
        }.trim()
        WearRelayLog.info("chat", "data-plane tts skipped: $reason")

        return talkSpeakRpcForRelay(synthText)
    }

    /**
     * Merges an emotion directive override onto a base [TalkDirective].
     * Emotion fields win where set; absent emotion fields fall back to the
     * base directive. Returns `baseDirective` unchanged when [override] is
     * null.
     */
    private fun mergeWithEmotion(
        baseDirective: TalkDirective?,
        override: SpriteCoreEmotionDirective?,
    ): TalkDirective? {
        if (override == null) return baseDirective
        val base = baseDirective ?: TalkDirective()
        return base.copy(
            voiceId = override.voiceId?.takeIf { it.isNotBlank() } ?: base.voiceId,
            stability = override.stability ?: base.stability,
            similarity = override.similarity ?: base.similarity,
            style = override.style ?: base.style,
            speakerBoost = override.speakerBoost ?: base.speakerBoost,
            speed = override.speed ?: base.speed,
        )
    }

    /**
     * Prepend the emotion directive's inline audio tag (e.g. `[happy]`,
     * `[excited]`) to [text] when the tag is set. The tag is emitted with
     * a trailing space so it parses as a leading emotion cue rather than
     * merging with the first word. Returns [text] unchanged when no tag
     * is configured.
     */
    private fun applyAudioTag(text: String, directive: SpriteCoreEmotionDirective?): String {
        val tag = directive?.audioTag?.takeIf { it.isNotBlank() } ?: return text
        return "$tag $text"
    }

    /**
     * Phase-4 seam: returns the per-state TTS directive override for this
     * agent, or `null` if the agent has no emotion config or the requested
     * state isn't in the configured map.
     */
    suspend fun resolveEmotion(
        agentId: String,
        state: String,
    ): SpriteCoreEmotionDirective? {
        val snapshot = ensureAgentsSnapshot() ?: return null
        return snapshot.agents[agentId]?.emotions?.get(state)
    }

    /**
     * Resolves voice for an agent from the cached `sprite-core.agents`
     * snapshot. Returns null when the plugin isn't registered, the agent
     * isn't configured, or no voice block exists.
     */
    private suspend fun resolveVoice(agentId: String): TalkSpeakerAgentVoice? {
        val snapshot = ensureAgentsSnapshot() ?: return null
        return snapshot.agents[agentId]?.voice
    }

    /**
     * Emotion-state names + human descriptions for an agent, pulled from the
     * same `sprite-core.agents` snapshot TalkSpeaker already caches for
     * voice resolution. Used by [AvatarPromptBuilder] to build the
     * client-side "sprite-core mode" prompt prefix.
     *
     * Returns null when the plugin isn't registered, the agent isn't
     * configured, or has no descriptions (no emotion teaching to inject).
     */
    internal suspend fun resolveEmotionDescriptions(agentId: String): Map<String, String>? {
        val snapshot = ensureAgentsSnapshot() ?: return null
        return snapshot.agents[agentId]?.descriptions?.takeIf { it.isNotEmpty() }
    }

    private suspend fun ensureAgentsSnapshot(): SpriteCoreAgentsSnapshot? {
        val cached = cachedSnapshot
        if (cached != null) return cached
        if (fetchFailed) return null
        return cacheMutex.withLock {
            val doubleChecked = cachedSnapshot
            if (doubleChecked != null) return@withLock doubleChecked
            if (fetchFailed) return@withLock null
            fetchAttempted = true
            val fresh = fetchAgentsSnapshot()
            if (fresh != null) {
                cachedSnapshot = fresh
            } else {
                fetchFailed = true
            }
            fresh
        }
    }

    private suspend fun fetchAgentsSnapshot(): SpriteCoreAgentsSnapshot? {
        return try {
            val result = session.requestDetailed(
                method = "sprite-core.agents",
                paramsJson = "{}",
                timeoutMs = 10_000,
            )
            if (!result.ok || result.payloadJson == null) {
                // Plugin disabled / method not registered / RPC error —
                // treat as "no voice for any agent." Falls through to the
                // talk.speak RPC default-voice path.
                val msg = "sprite-core.agents unavailable: ${result.error?.message?.take(40) ?: "no payload"}"
                WearRelayLog.info("chat", msg)
                PhoneDiagLog.warn("talk", msg)
                return null
            }
            val snapshot = parseAgentsSnapshot(result.payloadJson!!)
            if (snapshot != null) {
                PhoneDiagLog.incoming(
                    "talk",
                    "sprite-core.agents ok agents=${snapshot.agents.size}",
                )
            }
            snapshot
        } catch (e: Throwable) {
            val msg = "sprite-core.agents: ${e.javaClass.simpleName}"
            WearRelayLog.info("chat", msg)
            PhoneDiagLog.error("talk", msg)
            null
        }
    }

    private fun parseAgentsSnapshot(payloadJson: String): SpriteCoreAgentsSnapshot? {
        val root = try {
            json.parseToJsonElement(payloadJson) as? JsonObject
        } catch (_: Throwable) {
            return null
        } ?: return null
        val agentsObj = (root["agents"] as? JsonObject) ?: return null
        val parsed = mutableMapOf<String, SpriteCoreAgentEntry>()
        for ((id, raw) in agentsObj) {
            val obj = (raw as? JsonObject) ?: continue
            val voice = parseVoice(obj["voice"])
            val emotions = parseEmotions(obj["emotions"])
            val descriptions = parseEmotionDescriptions(obj)
            parsed[id] = SpriteCoreAgentEntry(
                voice = voice,
                emotions = emotions,
                descriptions = descriptions,
            )
        }
        return SpriteCoreAgentsSnapshot(agents = parsed)
    }

    /**
     * Collects the per-state emotion descriptions exposed by the plugin's
     * `sprite-core.agents` RPC. Two sources, merged with per-emotion entries
     * winning over the legacy `prompting.descriptions` map so freshly-
     * authored emotion entries can override a stale top-level description.
     */
    private fun parseEmotionDescriptions(agentObj: JsonObject): Map<String, String>? {
        val merged = mutableMapOf<String, String>()
        (agentObj["prompting"] as? JsonObject)?.get("descriptions")?.let { raw ->
            (raw as? JsonObject)?.forEach { (state, v) ->
                v.asStringOrNull()?.trim()?.takeIf { it.isNotEmpty() }?.let { merged[state] = it }
            }
        }
        (agentObj["emotions"] as? JsonObject)?.forEach { (state, raw) ->
            val entry = raw as? JsonObject ?: return@forEach
            entry["description"].asStringOrNull()?.trim()?.takeIf { it.isNotEmpty() }?.let {
                merged[state] = it
            }
        }
        return merged.takeIf { it.isNotEmpty() }
    }

    private fun parseVoice(element: JsonElement?): TalkSpeakerAgentVoice? {
        val obj = (element as? JsonObject) ?: return null
        val provider = obj["provider"]?.asStringOrNull()
        val voiceId = obj["voiceId"]?.asStringOrNull()
        if (provider.isNullOrBlank() && voiceId.isNullOrBlank()) return null
        return TalkSpeakerAgentVoice(provider = provider, voiceId = voiceId)
    }

    private fun parseEmotions(
        element: JsonElement?,
    ): Map<String, SpriteCoreEmotionDirective>? {
        val obj = (element as? JsonObject) ?: return null
        val out = mutableMapOf<String, SpriteCoreEmotionDirective>()
        for ((state, raw) in obj) {
            val entry = (raw as? JsonObject) ?: continue
            val directive = parseEmotionDirective(entry["directive"]) ?: continue
            out[state] = directive
        }
        return out.takeIf { it.isNotEmpty() }
    }

    private fun parseEmotionDirective(element: JsonElement?): SpriteCoreEmotionDirective? {
        val obj = (element as? JsonObject) ?: return null
        val voiceId = obj["voiceId"]?.asStringOrNull()?.takeIf { it.isNotBlank() }
        val stability = obj["stability"]?.asDoubleOrNull()
        val similarity = obj["similarity"]?.asDoubleOrNull()
        val style = obj["style"]?.asDoubleOrNull()
        val speakerBoost = obj["speakerBoost"]?.asBooleanOrNull()
        val speed = obj["speed"]?.asDoubleOrNull()
        val audioTag = obj["audioTag"]?.asStringOrNull()?.trim()?.takeIf { it.isNotEmpty() }
        val anyField = listOf<Any?>(voiceId, stability, similarity, style, speakerBoost, speed, audioTag)
            .any { it != null }
        if (!anyField) return null
        return SpriteCoreEmotionDirective(
            voiceId = voiceId,
            stability = stability,
            similarity = similarity,
            style = style,
            speakerBoost = speakerBoost,
            speed = speed,
            audioTag = audioTag,
        )
    }

    /**
     * RPC fallback for the wear relay path. Uses the gateway's `talk.speak`
     * with a minimal `{text}` payload (the method's Zod schema rejects extra
     * fields; directive-style params are reserved for the direct data-plane
     * path). Returns inline base64 or a streaming URL depending on what the
     * gateway responds with.
     */
    private suspend fun talkSpeakRpcForRelay(text: String): WearTtsDelivery? {
        return try {
            val params = buildJsonObject {
                put("text", JsonPrimitive(text))
            }
            val result = session.requestDetailed(
                method = "talk.speak",
                paramsJson = params.toString(),
                timeoutMs = 30_000,
            )
            if (!result.ok || result.payloadJson == null) {
                WearRelayLog.warn("chat", "talk.speak: ${result.error?.message?.take(40) ?: "no payload"}")
                return null
            }
            val payload = json.parseToJsonElement(result.payloadJson!!)
            val obj = (payload as? JsonObject) ?: return null
            val audioUrl = (obj["audioUrl"]?.asStringOrNull() ?: obj["streamUrl"]?.asStringOrNull())
                ?.takeIf { it.isNotBlank() }
            val audioBase64 = obj["audioBase64"]?.asStringOrNull()?.takeIf { it.isNotBlank() }
            val mimeType = obj["mimeType"]?.asStringOrNull() ?: "audio/mpeg"
            when {
                audioUrl != null -> {
                    WearRelayLog.info("chat", "talk.speak → url")
                    WearTtsDelivery.StreamingUrl(audioUrl = audioUrl, mimeType = mimeType)
                }
                audioBase64 != null -> {
                    WearRelayLog.info("chat", "talk.speak → base64 ${audioBase64.length / 1000}KB")
                    WearTtsDelivery.Inline(audioBase64 = audioBase64, mimeType = mimeType)
                }
                else -> {
                    WearRelayLog.warn("chat", "talk.speak → no audio in response")
                    null
                }
            }
        } catch (e: Throwable) {
            WearRelayLog.warn("chat", "talk.speak: ${e.javaClass.simpleName}")
            null
        }
    }

    private fun JsonElement?.asStringOrNull(): String? {
        val prim = this as? JsonPrimitive ?: return null
        if (!prim.isString) return null
        return prim.content
    }

    private fun JsonElement?.asDoubleOrNull(): Double? {
        val prim = this as? JsonPrimitive ?: return null
        if (prim.isString) return null
        return prim.content.toDoubleOrNull()
    }

    private fun JsonElement?.asBooleanOrNull(): Boolean? {
        val prim = this as? JsonPrimitive ?: return null
        if (prim.isString) return null
        return when (prim.content) {
            "true" -> true
            "false" -> false
            else -> null
        }
    }
}

/**
 * Outcome of [TalkSpeaker.tryPhoneDataPlaneFetch]. Split into three cases so
 * the caller can distinguish "ElevenLabs failed — fall back to local system
 * TTS" from "ElevenLabs wasn't configured — fall through to `talk.speak` RPC".
 */
private sealed interface PhoneDirectOutcome {
    data class Synthesized(val result: TalkSpeakResult) : PhoneDirectOutcome
    object DirectUnreachable : PhoneDirectOutcome
    object NotConfigured : PhoneDirectOutcome
}

/** Snapshot of an agent's voice config relevant for TTS routing. */
internal data class TalkSpeakerAgentVoice(
    val provider: String?,
    val voiceId: String?,
)

/** Snapshot of the wear data-plane config (baseUrl + feature flags). */
internal data class TalkSpeakerDataPlane(
    val baseUrl: String,
    val streamTtsEnabled: Boolean,
)

/**
 * Per-emotion TTS voice-directive override received from
 * `sprite-core.agents`. Mirrors the gateway-side
 * `SpriteCoreEmotionDirective` shape; all fields optional.
 *
 * [audioTag] is an inline emotion tag supported by models like
 * ElevenLabs `eleven_v3` (e.g. `[happy]`, `[sad]`, `[excited]`,
 * `[whispers]`). When set, [TalkSpeaker] prepends it to the segment
 * text before synthesis so the model renders the emotion directly.
 * Older models ignore the tag; leave unset when you're on
 * `eleven_turbo_v2` / `eleven_multilingual_v2` — otherwise the bracketed
 * text will be spoken aloud.
 */
internal data class SpriteCoreEmotionDirective(
    val voiceId: String? = null,
    val stability: Double? = null,
    val similarity: Double? = null,
    val style: Double? = null,
    val speakerBoost: Boolean? = null,
    val speed: Double? = null,
    val audioTag: String? = null,
)

/**
 * Projects the emotion directive into the `/stream/tts` query-param shape.
 * Returns null when no field would change the request (all null), so the
 * fetcher skips appending empty params.
 */
internal fun SpriteCoreEmotionDirective.toWireOverride(): EmotionTtsOverride? {
    if (stability == null && similarity == null && style == null && speakerBoost == null) {
        return null
    }
    return EmotionTtsOverride(
        stability = stability,
        similarity = similarity,
        style = style,
        speakerBoost = speakerBoost,
    )
}

/** Cached parse of one `sprite-core.agents` RPC response. */
private data class SpriteCoreAgentsSnapshot(
    val agents: Map<String, SpriteCoreAgentEntry>,
)

private data class SpriteCoreAgentEntry(
    val voice: TalkSpeakerAgentVoice?,
    val emotions: Map<String, SpriteCoreEmotionDirective>?,
    val descriptions: Map<String, String>?,
)
