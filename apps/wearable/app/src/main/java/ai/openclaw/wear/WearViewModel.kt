package ai.openclaw.wear

import android.app.Application
import android.content.Intent
import android.media.MediaPlayer
import android.speech.RecognizerIntent
import android.speech.SpeechRecognizer
import android.speech.RecognitionListener
import android.os.Bundle
import android.speech.tts.TextToSpeech
import android.util.Base64
import android.util.Log
import androidx.lifecycle.AndroidViewModel
import androidx.lifecycle.viewModelScope
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.flow.update
import kotlinx.coroutines.launch
import java.io.File
import java.util.Locale

enum class WearScreen { Connecting, Dial }

/**
 * Per-agent voice lifecycle on the dial. Each in-flight chat owns its own
 * VoiceState so "thinking" doesn't bleed across agents.
 */
enum class VoiceState { Idle, Listening, Sending, Thinking, Speaking, Error }

/**
 * Source of truth for the watch UI: agents list, per-agent chat state,
 * per-agent inbox + unread counts, audio playback routing, and push-to-talk
 * mic lifecycle.
 *
 * All voice/text state is keyed by agentId so multiple agents can stream
 * concurrently without interfering. Listening is globally single-track since
 * there's only one microphone / audio focus.
 *
 * Replies flow in through [PhoneBridge.chatStream]; audio lands either as
 * inline base64 (fast path), a streamable URL, or a DataClient Asset
 * reference resolved via [WearAssetStore].
 */
class WearViewModel(app: Application) : AndroidViewModel(app) {

    private val bridge get() = getApplication<WearApp>().phoneBridge
    private val assetStore get() = getApplication<WearApp>().assetStore

    /** Avatar bytes keyed by agentId; exposed to UI for `wear-asset:avatar:*` refs. */
    val avatarAssets: StateFlow<Map<String, ByteArray>> get() = assetStore.avatars

    /**
     * Per-agent counter bumped only when THAT agent's bytes change. Used as
     * part of the Coil memory-cache key so agent A's byte arrival doesn't
     * invalidate agent B's decoded GIF and force an animation restart.
     */
    val avatarVersions: StateFlow<Map<String, Int>> get() = assetStore.avatarVersions

    /** Current avatar state name per agent (phone-driven marker dispatch). */
    val agentStates: StateFlow<Map<String, String>> get() = assetStore.agentStates

    /** Per-agent CharacterManifest envelope published by the phone relay. */
    val characterManifests: StateFlow<Map<String, ai.openclaw.displaykit.CharacterManifestEnvelope>>
        get() = assetStore.characterManifests

    /** Per-agent `{ refKey → bytes }` map resolved from manifest.assets.refs. */
    val characterAssets: StateFlow<Map<String, Map<String, ByteArray>>>
        get() = assetStore.characterAssets

    // --- Screen navigation ---
    private val _screen = MutableStateFlow(WearScreen.Connecting)
    val screen: StateFlow<WearScreen> = _screen.asStateFlow()

    // --- Connection log lines ---
    private val _logs = MutableStateFlow<List<String>>(emptyList())
    val logs: StateFlow<List<String>> = _logs.asStateFlow()

    // --- Agents ---
    private val _agents = MutableStateFlow<List<PhoneBridge.Agent>>(emptyList())
    val agents: StateFlow<List<PhoneBridge.Agent>> = _agents.asStateFlow()

    // --- Per-agent voice / response state ------------------------------------
    //
    // Each in-flight chat is owned by a single agentId. Keeping state keyed
    // by agent prevents "thinking" bleeding across the dial when one agent
    // is working and others are idle, and lets multiple agents stream at
    // once without stepping on each other.
    //
    // Listening / transcript are intrinsically single-track (one mic, one
    // audio focus) — they live outside the per-agent map.

    private val _agentVoiceStates = MutableStateFlow<Map<String, VoiceState>>(emptyMap())
    val agentVoiceStates: StateFlow<Map<String, VoiceState>> = _agentVoiceStates.asStateFlow()

    private val _agentResponseTexts = MutableStateFlow<Map<String, String?>>(emptyMap())
    val agentResponseTexts: StateFlow<Map<String, String?>> = _agentResponseTexts.asStateFlow()

    private val _liveTranscript = MutableStateFlow<String?>(null)
    val liveTranscript: StateFlow<String?> = _liveTranscript.asStateFlow()

    /** Which agent (if any) currently owns the microphone. Single-track by design. */
    private val _listeningAgentId = MutableStateFlow<String?>(null)
    val listeningAgentId: StateFlow<String?> = _listeningAgentId.asStateFlow()

    private fun voiceStateOf(agentId: String): VoiceState =
        _agentVoiceStates.value[agentId] ?: VoiceState.Idle

    private fun setAgentVoiceState(agentId: String, state: VoiceState) {
        _agentVoiceStates.update { current ->
            if (state == VoiceState.Idle) current - agentId
            else current + (agentId to state)
        }
    }

    private fun setAgentResponseText(agentId: String, text: String?) {
        _agentResponseTexts.update { current ->
            if (text == null) current - agentId
            else current + (agentId to text)
        }
    }

    // --- Per-agent inbox (mailbox for replies) ---
    data class InboxEntry(
        val text: String,
        val timestamp: Long,
        val isFinal: Boolean,
        val audioUrl: String? = null,
        val audioBase64: String? = null,
        val audioAssetRef: String? = null,
        val audioMime: String? = null,
    )

    /** Pending "tap badge → scroll to this agent and auto-play" directive. */
    private val _pendingMailJump = MutableStateFlow<String?>(null)
    val pendingMailJump: StateFlow<String?> = _pendingMailJump.asStateFlow()

    /** Called when the user taps the generic mailbox; picks any agent with unread. */
    fun openMailbox() {
        val agentWithMail = _unreadByAgent.value.entries.firstOrNull { it.value > 0 }?.key
            ?: return
        _pendingMailJump.value = agentWithMail
    }

    /** Called when the user taps a specific agent's mailbox icon. */
    fun openMailboxFor(agentId: String) {
        _pendingMailJump.value = agentId
    }

    /** Called by the dial once it's scrolled to the jump target + played audio. */
    fun consumeMailJump() {
        _pendingMailJump.value = null
    }

    /**
     * Replay the most recent final reply for [agentId]. Tries asset → url →
     * base64 → local TTS; the tap always produces *something* audible.
     */
    fun replayLastFinal(agentId: String) {
        val lastFinal = _inbox.value[agentId]?.lastOrNull { it.isFinal } ?: return
        if (lastFinal.text.isBlank() &&
            lastFinal.audioAssetRef == null &&
            lastFinal.audioUrl == null &&
            lastFinal.audioBase64 == null
        ) return
        setAgentVoiceState(agentId, VoiceState.Speaking)
        playReplyAudio(
            agentId,
            PhoneBridge.ChatReply(
                text = lastFinal.text,
                isFinal = true,
                audioUrl = lastFinal.audioUrl,
                audioBase64 = lastFinal.audioBase64,
                audioAssetRef = lastFinal.audioAssetRef,
                audioMime = lastFinal.audioMime,
            ),
        )
    }

    /**
     * Dispatch a reply's audio through the right channel. Preference order:
     * DataClient asset (big ElevenLabs) → streamed URL → inline base64 → local
     * TTS fallback. Each path updates per-agent voiceState on completion.
     */
    private fun playReplyAudio(agentId: String, reply: PhoneBridge.ChatReply) {
        val mime = reply.audioMime ?: "audio/mpeg"
        when {
            reply.audioAssetRef != null -> playAssetAudio(agentId, reply.audioAssetRef, mime, reply.text)
            reply.audioUrl != null -> playStreamedAudio(agentId, reply.audioUrl, mime)
            reply.audioBase64 != null -> playElevenLabsAudio(agentId, reply.audioBase64, mime)
            reply.text.isNotBlank() -> speakLocalTts(agentId, reply.text)
            else -> setAgentVoiceState(agentId, VoiceState.Idle)
        }
    }

    /**
     * Resolve a `wear-asset:tts:<id>` ref to real bytes via the AssetStore,
     * write to a temp file, play via MediaPlayer. Falls back to local TTS if
     * the asset never syncs in time.
     */
    private fun playAssetAudio(agentId: String, assetRef: String, mime: String, fallbackText: String) {
        val assetId = assetRef.removePrefix("wear-asset:tts:")
        viewModelScope.launch {
            val bytes = assetStore.awaitTts(assetId, timeoutMs = 20_000)
            if (bytes == null) {
                Log.w("WearVM", "tts asset $assetId never arrived; falling back to local TTS")
                if (fallbackText.isNotBlank()) speakLocalTts(agentId, fallbackText)
                else setAgentVoiceState(agentId, VoiceState.Idle)
                return@launch
            }
            try {
                val ext = if (mime.contains("mpeg")) ".mp3" else ".wav"
                val tmpFile = File.createTempFile("tts-asset", ext, getApplication<WearApp>().cacheDir)
                tmpFile.writeBytes(bytes)
                mediaPlayer?.release()
                mediaPlayer = MediaPlayer().apply {
                    setDataSource(tmpFile.absolutePath)
                    setOnCompletionListener {
                        setAgentVoiceState(agentId, VoiceState.Idle)
                        tmpFile.delete()
                    }
                    setOnErrorListener { _, _, _ ->
                        setAgentVoiceState(agentId, VoiceState.Idle)
                        tmpFile.delete()
                        true
                    }
                    prepare()
                    start()
                }
            } catch (e: Throwable) {
                Log.e("WearVM", "asset audio playback failed", e)
                if (fallbackText.isNotBlank()) speakLocalTts(agentId, fallbackText)
                else setAgentVoiceState(agentId, VoiceState.Idle)
            }
        }
    }

    private val _inbox = MutableStateFlow<Map<String, List<InboxEntry>>>(emptyMap())
    val inbox: StateFlow<Map<String, List<InboxEntry>>> = _inbox.asStateFlow()

    private val _unreadByAgent = MutableStateFlow<Map<String, Int>>(emptyMap())
    val unreadByAgent: StateFlow<Map<String, Int>> = _unreadByAgent.asStateFlow()

    /** The agentId currently visible on the dial — null before we know. */
    private val _currentAgentId = MutableStateFlow<String?>(null)

    /** Called by the UI whenever the user swipes to a different agent page. */
    fun onAgentViewed(agentId: String?) {
        _currentAgentId.value = agentId
        if (agentId != null) markRead(agentId)
    }

    /** Clear unread count for an agent (e.g. when the user scrolls to it). */
    fun markRead(agentId: String) {
        _unreadByAgent.update { current ->
            if (current[agentId] == null) current else current - agentId
        }
    }

    private fun appendToInbox(agentId: String, entry: InboxEntry) {
        _inbox.update { current ->
            val list = current[agentId].orEmpty()
            current + (agentId to (list + entry).takeLast(INBOX_CAP))
        }
    }

    private fun bumpUnreadIfNotCurrent(agentId: String) {
        if (_currentAgentId.value == agentId) return
        _unreadByAgent.update { current ->
            current + (agentId to ((current[agentId] ?: 0) + 1))
        }
    }

    // Fallback TTS
    private var tts: TextToSpeech? = null
    private var ttsReady = false

    // Audio player for ElevenLabs
    private var mediaPlayer: MediaPlayer? = null

    init {
        tts = TextToSpeech(app) { status ->
            ttsReady = status == TextToSpeech.SUCCESS
            tts?.language = Locale.US
        }
    }

    override fun onCleared() {
        tts?.shutdown()
        mediaPlayer?.release()
        super.onCleared()
    }

    // --- Connection flow ---
    fun connect() {
        if (_screen.value != WearScreen.Connecting) return
        viewModelScope.launch {
            _logs.value = emptyList()
            log("Finding phone…")

            val nodeId = bridge.findPhone()
            if (nodeId == null) {
                log("ERROR: No paired phone found")
                return@launch
            }

            log("Pinging relay…")
            val ping = bridge.ping()
            if (!ping.connected) {
                log("ERROR: ${ping.status}")
                return@launch
            }
            log("Gateway: ${ping.serverName ?: "connected"}")

            val agents = bridge.getAgents()
            if (agents.isEmpty()) {
                log("ERROR: No agents available")
                return@launch
            }
            _agents.value = agents

            log("Ready · ${agents.size} agent${if (agents.size == 1) "" else "s"}")
            _screen.value = WearScreen.Dial
        }
    }

    fun retry() {
        _screen.value = WearScreen.Connecting
        connect()
    }

    // --- Mic permission ---
    private var micPermissionGranted = false

    fun onMicPermissionResult(granted: Boolean) {
        micPermissionGranted = granted
    }

    // --- Voice interaction (press-and-hold on the dial) ---
    private var activeRecognizer: SpeechRecognizer? = null

    /**
     * Called when the user presses the mic target. Opens the recognizer.
     * Safe to call multiple times — a second press while listening or thinking
     * is a no-op so mis-taps don't cancel a live session.
     */
    fun startPushToTalk(agentIndex: Int) {
        val agent = _agents.value.getOrNull(agentIndex) ?: return
        val ctx = getApplication<WearApp>()

        // Reject if this agent is already busy; also reject if the mic is
        // already live (any agent).
        val existing = voiceStateOf(agent.id)
        if (existing == VoiceState.Listening ||
            existing == VoiceState.Sending ||
            existing == VoiceState.Thinking
        ) return
        if (_listeningAgentId.value != null) return

        if (existing == VoiceState.Speaking) {
            // User wants to barge in on this agent — stop its playback.
            runCatching { mediaPlayer?.stop() }
            mediaPlayer?.release()
            mediaPlayer = null
            runCatching { tts?.stop() }
        }

        if (!micPermissionGranted) {
            setAgentVoiceState(agent.id, VoiceState.Error)
            setAgentResponseText(agent.id, "Mic permission denied")
            return
        }

        if (!SpeechRecognizer.isRecognitionAvailable(ctx)) {
            setAgentVoiceState(agent.id, VoiceState.Error)
            setAgentResponseText(agent.id, "Speech recognition not available")
            return
        }

        setAgentVoiceState(agent.id, VoiceState.Listening)
        setAgentResponseText(agent.id, null)
        _liveTranscript.value = null
        _listeningAgentId.value = agent.id

        val recognizer = SpeechRecognizer.createSpeechRecognizer(ctx)
        activeRecognizer = recognizer
        val intent = Intent(RecognizerIntent.ACTION_RECOGNIZE_SPEECH).apply {
            putExtra(RecognizerIntent.EXTRA_LANGUAGE_MODEL, RecognizerIntent.LANGUAGE_MODEL_FREE_FORM)
            putExtra(RecognizerIntent.EXTRA_PARTIAL_RESULTS, true)
            putExtra(RecognizerIntent.EXTRA_MAX_RESULTS, 1)
        }

        recognizer.setRecognitionListener(object : RecognitionListener {
            override fun onReadyForSpeech(params: Bundle?) {}
            override fun onBeginningOfSpeech() {}
            override fun onRmsChanged(rmsdB: Float) {}
            override fun onBufferReceived(buffer: ByteArray?) {}
            override fun onEndOfSpeech() {}

            override fun onPartialResults(partialResults: Bundle?) {
                partialResults
                    ?.getStringArrayList(SpeechRecognizer.RESULTS_RECOGNITION)
                    ?.firstOrNull()
                    ?.takeIf { it.isNotBlank() }
                    ?.let { _liveTranscript.value = it }
            }

            override fun onResults(results: Bundle?) {
                if (activeRecognizer === recognizer) activeRecognizer = null
                recognizer.destroy()
                _listeningAgentId.value = null
                val text = results
                    ?.getStringArrayList(SpeechRecognizer.RESULTS_RECOGNITION)
                    ?.firstOrNull()
                    ?: _liveTranscript.value

                if (text.isNullOrBlank()) {
                    setAgentVoiceState(agent.id, VoiceState.Idle)
                    return
                }

                _liveTranscript.value = text
                sendToAgent(agent, text)
            }

            override fun onError(error: Int) {
                if (activeRecognizer === recognizer) activeRecognizer = null
                recognizer.destroy()
                _listeningAgentId.value = null
                val buffered = _liveTranscript.value
                if (!buffered.isNullOrBlank() &&
                    (error == SpeechRecognizer.ERROR_NO_MATCH ||
                        error == SpeechRecognizer.ERROR_SPEECH_TIMEOUT)
                ) {
                    sendToAgent(agent, buffered)
                    return
                }
                if (error == SpeechRecognizer.ERROR_NO_MATCH ||
                    error == SpeechRecognizer.ERROR_SPEECH_TIMEOUT
                ) {
                    setAgentVoiceState(agent.id, VoiceState.Idle)
                } else {
                    setAgentVoiceState(agent.id, VoiceState.Error)
                    setAgentResponseText(agent.id, "Speech error: $error")
                }
            }

            override fun onEvent(eventType: Int, params: Bundle?) {}
        })

        recognizer.startListening(intent)
    }

    /**
     * Called when the user releases the mic target. Stops capture; whatever
     * was said gets delivered through onResults (or onError → fallback).
     */
    fun endPushToTalk() {
        val recognizer = activeRecognizer ?: return
        runCatching { recognizer.stopListening() }
    }


    private fun sendToAgent(agent: PhoneBridge.Agent, text: String) {
        setAgentVoiceState(agent.id, VoiceState.Sending)
        // Each chat runs in its own coroutine so agents stream independently.
        viewModelScope.launch {
            setAgentVoiceState(agent.id, VoiceState.Thinking)
            setAgentResponseText(agent.id, null)

            val error = bridge.chatStream(agent.id, text) { reply ->
                if (reply.text.isNotBlank()) {
                    appendToInbox(
                        agent.id,
                        InboxEntry(
                            text = reply.text,
                            timestamp = System.currentTimeMillis(),
                            isFinal = reply.isFinal,
                            audioUrl = reply.audioUrl,
                            audioBase64 = reply.audioBase64,
                            audioAssetRef = reply.audioAssetRef,
                            audioMime = reply.audioMime,
                        ),
                    )
                    if (reply.isFinal) bumpUnreadIfNotCurrent(agent.id)
                }

                val prevText = _agentResponseTexts.value[agent.id]
                setAgentResponseText(agent.id, reply.text.ifBlank { prevText })

                if (reply.isFinal) {
                    if (reply.text.isNotBlank()) {
                        val onThisAgent = _currentAgentId.value == agent.id
                        if (onThisAgent) {
                            setAgentVoiceState(agent.id, VoiceState.Speaking)
                            playReplyAudio(agent.id, reply)
                        } else {
                            setAgentVoiceState(agent.id, VoiceState.Idle)
                        }
                    } else {
                        setAgentVoiceState(agent.id, VoiceState.Idle)
                    }
                } else {
                    setAgentVoiceState(agent.id, VoiceState.Thinking)
                }
            }

            if (error != null) {
                setAgentResponseText(agent.id, error)
                setAgentVoiceState(agent.id, VoiceState.Error)
            }
        }
    }

    /**
     * Stream audio from a URL (typically the gateway's /stream/tts proxy).
     * First byte plays within ~500 ms because MediaPlayer streams as bytes
     * arrive — no waiting for the whole synthesis, no 100 KB Data Layer cap.
     */
    private fun playStreamedAudio(agentId: String, url: String, mime: String) {
        viewModelScope.launch {
            try {
                mediaPlayer?.release()
                mediaPlayer = MediaPlayer().apply {
                    setDataSource(url)
                    setOnPreparedListener { it.start() }
                    setOnCompletionListener {
                        setAgentVoiceState(agentId, VoiceState.Idle)
                    }
                    setOnErrorListener { _, what, extra ->
                        Log.e("WearVM", "streamed audio error what=$what extra=$extra")
                        setAgentVoiceState(agentId, VoiceState.Idle)
                        true
                    }
                    prepareAsync()
                }
            } catch (e: Throwable) {
                Log.e("WearVM", "streamed audio setup failed", e)
                val text = _agentResponseTexts.value[agentId]
                if (text != null) speakLocalTts(agentId, text) else setAgentVoiceState(agentId, VoiceState.Idle)
            }
        }
    }

    private fun playElevenLabsAudio(agentId: String, base64: String, mime: String) {
        viewModelScope.launch {
            try {
                val bytes = Base64.decode(base64, Base64.DEFAULT)
                val ext = if (mime.contains("mpeg")) ".mp3" else ".wav"
                val tmpFile = File.createTempFile("tts", ext, getApplication<WearApp>().cacheDir)
                tmpFile.writeBytes(bytes)

                mediaPlayer?.release()
                mediaPlayer = MediaPlayer().apply {
                    setDataSource(tmpFile.absolutePath)
                    setOnCompletionListener {
                        setAgentVoiceState(agentId, VoiceState.Idle)
                        tmpFile.delete()
                    }
                    setOnErrorListener { _, _, _ ->
                        setAgentVoiceState(agentId, VoiceState.Idle)
                        tmpFile.delete()
                        true
                    }
                    prepare()
                    start()
                }
            } catch (e: Throwable) {
                Log.e("WearVM", "ElevenLabs playback failed", e)
                val text = _agentResponseTexts.value[agentId]
                if (text != null) speakLocalTts(agentId, text) else setAgentVoiceState(agentId, VoiceState.Idle)
            }
        }
    }

    private fun speakLocalTts(agentId: String, text: String) {
        if (ttsReady && tts != null) {
            tts!!.speak(text, TextToSpeech.QUEUE_FLUSH, null, "wear_response_$agentId")
            viewModelScope.launch {
                kotlinx.coroutines.delay(maxOf(1500L, text.length * 60L))
                if (voiceStateOf(agentId) == VoiceState.Speaking) {
                    setAgentVoiceState(agentId, VoiceState.Idle)
                }
            }
        } else {
            setAgentVoiceState(agentId, VoiceState.Idle)
        }
    }

    private fun log(message: String) {
        Log.d("WearVM", message)
        _logs.value = _logs.value + message
    }

    companion object {
        private const val INBOX_CAP = 20
    }
}
