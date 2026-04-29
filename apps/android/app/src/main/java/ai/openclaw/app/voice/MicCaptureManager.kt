package ai.openclaw.app.voice

import ai.openclaw.spritecore.client.AvatarMarker
import ai.openclaw.spritecore.client.parseAvatarMarkers
import android.Manifest
import android.content.Context
import android.content.Intent
import android.content.pm.PackageManager
import android.os.Bundle
import android.os.Handler
import android.os.Looper
import android.util.Log
import android.speech.RecognitionListener
import android.speech.RecognizerIntent
import android.speech.SpeechRecognizer
import androidx.core.content.ContextCompat
import ai.openclaw.app.diag.PhoneDiagLog
import java.util.UUID
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.Job
import kotlinx.coroutines.delay
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext
import kotlinx.serialization.json.Json
import kotlinx.serialization.json.JsonArray
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.JsonPrimitive

enum class VoiceConversationRole {
  User,
  Assistant,
}

data class VoiceConversationEntry(
  val id: String,
  val role: VoiceConversationRole,
  val text: String,
  val isStreaming: Boolean = false,
)

class MicCaptureManager(
  private val context: Context,
  private val scope: CoroutineScope,
  /**
   * Send [message] to the gateway and return the run ID.
   * [onRunIdKnown] is called with the idempotency key *before* the network
   * round-trip so [pendingRunId] is set before any chat events can arrive.
   */
  private val sendToGateway: suspend (message: String, onRunIdKnown: (String) -> Unit) -> String?,
  private val speakAssistantReply: suspend (String) -> Unit = {},
  /**
   * Fired once per completed assistant reply, with the ordered list of
   * `<<<state-N>>>` markers parsed out of the model's text. NodeRuntime uses
   * this to drive the avatar's emotion animation client-side without
   * round-tripping through a gateway-emitted state event. The [String] text
   * is the cleaned (marker-stripped) reply — handy for diagnostic logging.
   */
  private val onAssistantMarkers: (cleaned: String, markers: List<AvatarMarker>) -> Unit = { _, _ -> },
  /**
   * Transcribe the given WAV file via the gateway's `/stream/stt` proxy.
   * Returns the committed transcript, or null when the gateway can't reach
   * ElevenLabs / no STT plugin is configured — callers fall back to the
   * Android on-device SpeechRecognizer path. Passed as a callback to keep
   * MicCaptureManager free of gateway/auth coupling.
   */
  private val transcribeViaGateway: (suspend (file: java.io.File) -> String?)? = null,
  /**
   * Is the gateway's `/stream/stt` route available right now? Checked at
   * press-start to decide between the ElevenLabs path (record PCM + upload)
   * and the SpeechRecognizer fallback. Evaluated lazily so gateway
   * reconnects / config reloads take effect on the next press.
   */
  private val streamSttAvailable: () -> Boolean = { false },
) {
  companion object {
    private const val tag = "MicCapture"
    private const val speechMinSessionMs = 30_000L
    private const val speechCompleteSilenceMs = 1_500L
    private const val speechPossibleSilenceMs = 900L
    // In press-and-hold mode the user — not the recognizer's VAD — owns the
    // turn boundary. These values keep the session alive through pauses.
    private const val holdSpeechMinSessionMs = 60_000L
    private const val holdSpeechCompleteSilenceMs = 600_000L
    private const val holdSpeechPossibleSilenceMs = 600_000L
    private const val transcriptIdleFlushMs = 1_600L
    private const val maxConversationEntries = 40
    // Cold-start model responses (especially via openrouter) can exceed 45s;
    // keeping the old value caused the phone to time out the runId, auto-
    // re-send the message on the queue, and then drop the late first-reply
    // with "chat event dropped: no pendingRunId". 120s covers the slow path.
    private const val pendingRunTimeoutMs = 120_000L
    private const val holdReleaseDrainMs = 2_500L
  }

  @Volatile private var holdMode = false
  private val accumulatedHoldText = mutableListOf<String>()
  @Volatile private var suppressResultsUntilMs = 0L

  // ElevenLabs-STT press-and-hold path. When [pcmRecorder] is active the
  // SpeechRecognizer session is NOT started — we record raw PCM instead and
  // upload on release. Null means the current hold is using the
  // SpeechRecognizer fallback.
  private val pcmRecorder = MicPcmRecorder(
    context = context,
    onInputLevel = { level -> _inputLevel.value = level },
  )
  @Volatile private var pcmHoldActive = false

  private val mainHandler = Handler(Looper.getMainLooper())
  private val json = Json { ignoreUnknownKeys = true }

  private val _micEnabled = MutableStateFlow(false)
  val micEnabled: StateFlow<Boolean> = _micEnabled

  private val _micCooldown = MutableStateFlow(false)
  val micCooldown: StateFlow<Boolean> = _micCooldown

  private val _isListening = MutableStateFlow(false)
  val isListening: StateFlow<Boolean> = _isListening

  private val _statusText = MutableStateFlow("Mic off")
  val statusText: StateFlow<String> = _statusText

  private val _liveTranscript = MutableStateFlow<String?>(null)
  val liveTranscript: StateFlow<String?> = _liveTranscript

  private val _queuedMessages = MutableStateFlow<List<String>>(emptyList())
  val queuedMessages: StateFlow<List<String>> = _queuedMessages

  private val _conversation = MutableStateFlow<List<VoiceConversationEntry>>(emptyList())
  val conversation: StateFlow<List<VoiceConversationEntry>> = _conversation

  private val _inputLevel = MutableStateFlow(0f)
  val inputLevel: StateFlow<Float> = _inputLevel

  private val _isSending = MutableStateFlow(false)
  val isSending: StateFlow<Boolean> = _isSending

  private val messageQueue = ArrayDeque<String>()
  private val messageQueueLock = Any()
  private var flushedPartialTranscript: String? = null
  private var pendingRunId: String? = null
  private var pendingAssistantEntryId: String? = null
  private var gatewayConnected = false

  // Sliding window of runIds we've sent in the last few turns whose final /
  // error / aborted event hasn't landed yet. Prevents "chat event dropped: no
  // pendingRunId" when a reply arrives after pendingRunTimeoutMs cleared
  // pendingRunId and the queue already moved on to a new runId. Events for
  // any runId in this set are still processed — late replies display in the
  // UI instead of getting silently swallowed.
  private val recentRunIds = LinkedHashSet<String>()
  private val recentRunIdsLock = Any()
  private val recentRunIdsWindow = 6

  private var recognizer: SpeechRecognizer? = null
  private var restartJob: Job? = null
  private var drainJob: Job? = null
  private var transcriptFlushJob: Job? = null
  private var pendingRunTimeoutJob: Job? = null
  private var stopRequested = false
  private val ttsPauseLock = Any()
  private var ttsPauseDepth = 0
  private var resumeMicAfterTts = false

  /**
   * Drop the voice-tab conversation history. Called when the dial rotates
   * an agent's session — the UI should show a blank slate for the fresh
   * conversation. Doesn't touch the pending turn or the queued-message
   * buffer (those already get cleaned up by completePendingTurn / drain).
   */
  fun clearConversation() {
    _conversation.value = emptyList()
    _liveTranscript.value = null
    pendingAssistantEntryId = null
    flushedPartialTranscript = null
    PhoneDiagLog.info("mic", "conversation cleared (new session)")
  }

  private fun enqueueMessage(message: String) {
    synchronized(messageQueueLock) {
      messageQueue.addLast(message)
    }
  }

  private fun snapshotMessageQueue(): List<String> {
    return synchronized(messageQueueLock) {
      messageQueue.toList()
    }
  }

  private fun hasQueuedMessages(): Boolean {
    return synchronized(messageQueueLock) {
      messageQueue.isNotEmpty()
    }
  }

  private fun firstQueuedMessage(): String? {
    return synchronized(messageQueueLock) {
      messageQueue.firstOrNull()
    }
  }

  private fun removeFirstQueuedMessage(): String? {
    return synchronized(messageQueueLock) {
      if (messageQueue.isEmpty()) null else messageQueue.removeFirst()
    }
  }

  private fun queuedMessageCount(): Int {
    return synchronized(messageQueueLock) {
      messageQueue.size
    }
  }

  fun setMicEnabled(enabled: Boolean) {
    if (_micEnabled.value == enabled) return
    _micEnabled.value = enabled
    PhoneDiagLog.info("mic", if (enabled) "enabled" else "disabled")
    // If an external teardown (e.g. leaving the Voice screen) flips the mic
    // off while we're mid-PCM-hold, make sure the AudioRecord + writer thread
    // stop — otherwise they leak until the process dies.
    if (!enabled && pcmHoldActive) {
      pcmHoldActive = false
      pcmRecorder.cancel()
    }
    if (enabled) {
      val pausedForTts =
        synchronized(ttsPauseLock) {
          if (ttsPauseDepth > 0) {
            resumeMicAfterTts = true
            true
          } else {
            false
          }
        }
      if (pausedForTts) {
        _statusText.value = if (_isSending.value) "Speaking · waiting for reply" else "Speaking…"
        return
      }
      start()
      sendQueuedIfIdle()
    } else {
      // Give the recognizer time to finish processing buffered audio.
      // Cancel any prior drain to prevent duplicate sends on rapid toggle.
      drainJob?.cancel()
      _micCooldown.value = true
      drainJob = scope.launch {
        delay(2000L)
        stop()
        // Capture any partial transcript that didn't get a final result from the recognizer
        val partial = _liveTranscript.value?.trim().orEmpty()
        if (partial.isNotEmpty()) {
          queueRecognizedMessage(partial)
        }
        drainJob = null
        _micCooldown.value = false
        sendQueuedIfIdle()
      }
    }
  }

  /**
   * Enter press-and-hold mode. When the gateway has `streamStt.enabled=true`
   * and a [transcribeViaGateway] callback is wired, we record raw 16 kHz PCM
   * to a temp file for upload on release (ElevenLabs-via-gateway path).
   * Otherwise we keep the Android SpeechRecognizer alive through silence as a
   * fallback so the feature still works on gateways without the STT plugin.
   */
  fun startHold() {
    synchronized(accumulatedHoldText) { accumulatedHoldText.clear() }
    suppressResultsUntilMs = 0L

    val useElevenLabs = transcribeViaGateway != null && streamSttAvailable()
    if (useElevenLabs) {
      pcmHoldActive = pcmRecorder.start()
      if (pcmHoldActive) {
        PhoneDiagLog.info("mic", "hold start path=elevenlabs-stt")
        _micEnabled.value = true
        _isListening.value = true
        _statusText.value = "Recording…"
        return
      }
      // Recorder failed (permission / device busy) — fall through to the
      // SpeechRecognizer path so the user still gets some transcription.
      PhoneDiagLog.warn("mic", "pcm recorder start failed; falling back to SpeechRecognizer")
    }

    holdMode = true
    PhoneDiagLog.info("mic", "hold start path=speech-recognizer")
    setMicEnabled(true)
  }

  /**
   * Exit press-and-hold mode. On the ElevenLabs path this stops the
   * [MicPcmRecorder], uploads the resulting WAV via [transcribeViaGateway],
   * and queues the returned transcript. On the SpeechRecognizer fallback
   * path it flushes the accumulated text captured across any mid-hold
   * recognizer cutoffs.
   */
  fun stopHold() {
    if (pcmHoldActive) {
      pcmHoldActive = false
      _isListening.value = false
      _inputLevel.value = 0f
      _micEnabled.value = false
      // Gate the button so users can't re-press while the upload is in flight.
      _micCooldown.value = true
      _statusText.value = "Transcribing…"
      val transcribe = transcribeViaGateway
      scope.launch {
        try {
          val file = pcmRecorder.stop()
          if (file == null) {
            PhoneDiagLog.warn("mic", "hold release — no audio captured")
            _statusText.value = "Mic off"
            return@launch
          }
          PhoneDiagLog.info("mic", "hold release path=elevenlabs-stt bytes=${file.length()}")
          val text = try {
            transcribe?.invoke(file)
          } catch (e: Throwable) {
            PhoneDiagLog.error("mic", "transcribeViaGateway threw: ${e.javaClass.simpleName}")
            null
          }
          file.delete()
          if (text.isNullOrBlank()) {
            _statusText.value = "Transcription failed"
            PhoneDiagLog.warn("mic", "hold release — empty transcript")
            return@launch
          }
          PhoneDiagLog.info("mic", "hold release — queued transcript chars=${text.length}")
          queueRecognizedMessage(text.trim())
          sendQueuedIfIdle()
        } finally {
          _micCooldown.value = false
        }
      }
      return
    }

    if (!holdMode && accumulatedHoldText.isEmpty()) {
      setMicEnabled(false)
      return
    }
    val combined = synchronized(accumulatedHoldText) {
      val parts = accumulatedHoldText.toList()
      accumulatedHoldText.clear()
      val partial = _liveTranscript.value?.trim().orEmpty()
      (parts + partial).filter { it.isNotBlank() }.joinToString(" ").trim()
    }
    holdMode = false
    // Discard any onResults that arrive during the recognizer drain window;
    // the accumulated text has already been captured above.
    suppressResultsUntilMs = System.currentTimeMillis() + holdReleaseDrainMs
    _liveTranscript.value = null
    PhoneDiagLog.info("mic", "hold release path=speech-recognizer chars=${combined.length}")
    setMicEnabled(false)
    if (combined.isNotEmpty()) {
      queueRecognizedMessage(combined)
      sendQueuedIfIdle()
    }
  }

  suspend fun pauseForTts() {
    val shouldPause =
      synchronized(ttsPauseLock) {
        ttsPauseDepth += 1
        if (ttsPauseDepth > 1) return@synchronized false
        resumeMicAfterTts = _micEnabled.value
        val active = resumeMicAfterTts || recognizer != null || _isListening.value
        if (!active) return@synchronized false
        stopRequested = true
        restartJob?.cancel()
        restartJob = null
        transcriptFlushJob?.cancel()
        transcriptFlushJob = null
        _isListening.value = false
        _inputLevel.value = 0f
        _liveTranscript.value = null
        _statusText.value = if (_isSending.value) "Speaking · waiting for reply" else "Speaking…"
        true
      }
    if (!shouldPause) return
    withContext(Dispatchers.Main) {
      recognizer?.cancel()
      recognizer?.destroy()
      recognizer = null
    }
  }

  suspend fun resumeAfterTts() {
    val shouldResume =
      synchronized(ttsPauseLock) {
        if (ttsPauseDepth == 0) return@synchronized false
        ttsPauseDepth -= 1
        if (ttsPauseDepth > 0) return@synchronized false
        val resume = resumeMicAfterTts && _micEnabled.value
        resumeMicAfterTts = false
        if (!resume) {
          _statusText.value =
            when {
              _micEnabled.value && _isSending.value -> "Listening · sending queued voice"
              _micEnabled.value -> "Listening"
              _isSending.value -> "Mic off · sending…"
              else -> "Mic off"
            }
        }
        resume
      }
    if (!shouldResume) return
    stopRequested = false
    start()
    sendQueuedIfIdle()
  }

  fun onGatewayConnectionChanged(connected: Boolean) {
    gatewayConnected = connected
    if (connected) {
      sendQueuedIfIdle()
      return
    }
    pendingRunTimeoutJob?.cancel()
    pendingRunTimeoutJob = null
    pendingRunId = null
    pendingAssistantEntryId = null
    _isSending.value = false
    if (hasQueuedMessages()) {
      _statusText.value = queuedWaitingStatus()
    }
  }

  fun handleGatewayEvent(event: String, payloadJson: String?) {
    if (event != "chat") return
    if (payloadJson.isNullOrBlank()) return
    // Raw-arrival log (before any filtering) — tells us whether the
    // gateway is emitting chat events at all when the phone isn't
    // receiving replies.
    PhoneDiagLog.incoming("mic", "chat event bytes=${payloadJson.length}")
    val payload =
      try {
        json.parseToJsonElement(payloadJson).asObjectOrNull()
      } catch (_: Throwable) {
        null
      } ?: return

    val eventRunId = payload["runId"].asStringOrNull() ?: return
    val matchesPending = pendingRunId != null && pendingRunId == eventRunId
    val matchesRecent = isRecentRunId(eventRunId)
    if (!matchesPending && !matchesRecent) {
      Log.d("MicCapture", "runId not tracked: event=$eventRunId pending=$pendingRunId")
      PhoneDiagLog.warn(
        "mic",
        "chat event dropped: runId event=${eventRunId.take(8)} not tracked (pending=${pendingRunId?.take(8) ?: "null"})",
      )
      return
    }

    val state = payload["state"].asStringOrNull()
    when (state) {
      "delta" -> {
        val rawDelta = parseAssistantText(payload)
        if (!rawDelta.isNullOrBlank()) {
          // Strip `<<<state-N>>>` markers from the streamed text before it
          // reaches the UI — keeps the bubble clean while the model types.
          // We don't dispatch markers here because delta payloads are
          // cumulative (each one replaces the previous); firing marker
          // callbacks on every delta would re-dispatch the same emotion
          // repeatedly. The "final" branch handles dispatch once.
          val parsed = parseAvatarMarkers(rawDelta)
          upsertPendingAssistant(text = parsed.cleanedText.trim(), isStreaming = true)
        }
      }
      "final" -> {
        val rawFinal = parseAssistantText(payload)?.trim().orEmpty()
        val parsed = parseAvatarMarkers(rawFinal)
        val finalText = parsed.cleanedText
        PhoneDiagLog.incoming(
          "mic",
          "chat final chars=${finalText.length}" +
            (if (parsed.markers.isNotEmpty()) " markers=${parsed.markers.size}" else "") +
            (if (!matchesPending) " (late/recent)" else "") +
            if (finalText.isNotEmpty()) " \"${finalText.take(40)}${if (finalText.length > 40) "…" else ""}\"" else "",
        )
        if (finalText.isNotEmpty()) {
          upsertPendingAssistant(text = finalText, isStreaming = false)
          // Feed the CLEAN text to TTS so `<<<happy-1>>>` etc. never gets
          // vocalised by ElevenLabs.
          playAssistantReplyAsync(finalText)
        } else if (pendingAssistantEntryId != null) {
          updateConversationEntry(pendingAssistantEntryId!!, text = null, isStreaming = false)
        }
        if (parsed.markers.isNotEmpty()) {
          onAssistantMarkers(finalText, parsed.markers)
        }
        forgetRunId(eventRunId)
        if (matchesPending) completePendingTurn() else dropHeadQueuedMessage()
      }
      "error" -> {
        val errorMessage = payload["errorMessage"].asStringOrNull()?.trim().orEmpty().ifEmpty { "Voice request failed" }
        PhoneDiagLog.error("mic", "chat error: ${errorMessage.take(60)}" + if (!matchesPending) " (late/recent)" else "")
        upsertPendingAssistant(text = errorMessage, isStreaming = false)
        forgetRunId(eventRunId)
        if (matchesPending) completePendingTurn() else dropHeadQueuedMessage()
      }
      "aborted" -> {
        PhoneDiagLog.warn("mic", "chat aborted" + if (!matchesPending) " (late/recent)" else "")
        upsertPendingAssistant(text = "Response aborted", isStreaming = false)
        forgetRunId(eventRunId)
        if (matchesPending) completePendingTurn() else dropHeadQueuedMessage()
      }
      else -> {
        if (!state.isNullOrBlank()) {
          PhoneDiagLog.info("mic", "chat event state=$state")
        }
      }
    }
  }

  private fun rememberRunId(runId: String) {
    synchronized(recentRunIdsLock) {
      // LinkedHashSet preserves insertion order; remove-then-add bumps an
      // existing key to the newest slot so it isn't evicted prematurely.
      recentRunIds.remove(runId)
      recentRunIds.add(runId)
      while (recentRunIds.size > recentRunIdsWindow) {
        val iterator = recentRunIds.iterator()
        if (iterator.hasNext()) {
          iterator.next()
          iterator.remove()
        }
      }
    }
  }

  private fun isRecentRunId(runId: String): Boolean =
    synchronized(recentRunIdsLock) { runId in recentRunIds }

  private fun forgetRunId(runId: String) {
    synchronized(recentRunIdsLock) { recentRunIds.remove(runId) }
  }

  /**
   * Remove the head queued message after a late reply is accepted post-
   * watchdog. The user got their answer; we don't want the next turn to
   * redeliver the same message. Keeps the conversation forward-moving even
   * when the gateway response straddled [pendingRunTimeoutMs].
   */
  private fun dropHeadQueuedMessage() {
    if (removeFirstQueuedMessage() != null) {
      publishQueue()
    }
  }

  private fun start() {
    stopRequested = false
    if (!SpeechRecognizer.isRecognitionAvailable(context)) {
      _statusText.value = "Speech recognizer unavailable"
      _micEnabled.value = false
      return
    }
    if (!hasMicPermission()) {
      _statusText.value = "Microphone permission required"
      _micEnabled.value = false
      return
    }

    mainHandler.post {
      try {
        if (recognizer == null) {
          recognizer = SpeechRecognizer.createSpeechRecognizer(context).also { it.setRecognitionListener(listener) }
        }
        startListeningSession()
      } catch (err: Throwable) {
        _statusText.value = "Start failed: ${err.message ?: err::class.simpleName}"
        _micEnabled.value = false
      }
    }
  }

  private fun stop() {
    stopRequested = true
    restartJob?.cancel()
    restartJob = null
    transcriptFlushJob?.cancel()
    transcriptFlushJob = null
    _isListening.value = false
    _statusText.value = if (_isSending.value) "Mic off · sending…" else "Mic off"
    _inputLevel.value = 0f
    mainHandler.post {
      recognizer?.cancel()
      recognizer?.destroy()
      recognizer = null
    }
  }

  private fun startListeningSession() {
    val recognizerInstance = recognizer ?: return
    val minMs = if (holdMode) holdSpeechMinSessionMs else speechMinSessionMs
    val completeSilenceMs = if (holdMode) holdSpeechCompleteSilenceMs else speechCompleteSilenceMs
    val possibleSilenceMs = if (holdMode) holdSpeechPossibleSilenceMs else speechPossibleSilenceMs
    val intent =
      Intent(RecognizerIntent.ACTION_RECOGNIZE_SPEECH).apply {
        putExtra(RecognizerIntent.EXTRA_LANGUAGE_MODEL, RecognizerIntent.LANGUAGE_MODEL_FREE_FORM)
        putExtra(RecognizerIntent.EXTRA_PARTIAL_RESULTS, true)
        putExtra(RecognizerIntent.EXTRA_MAX_RESULTS, 3)
        putExtra(RecognizerIntent.EXTRA_CALLING_PACKAGE, context.packageName)
        putExtra(RecognizerIntent.EXTRA_SPEECH_INPUT_MINIMUM_LENGTH_MILLIS, minMs)
        putExtra(RecognizerIntent.EXTRA_SPEECH_INPUT_COMPLETE_SILENCE_LENGTH_MILLIS, completeSilenceMs)
        putExtra(
          RecognizerIntent.EXTRA_SPEECH_INPUT_POSSIBLY_COMPLETE_SILENCE_LENGTH_MILLIS,
          possibleSilenceMs,
        )
      }
    _statusText.value =
      when {
        _isSending.value -> "Listening · sending queued voice"
        hasQueuedMessages() -> "Listening · ${queuedMessageCount()} queued"
        else -> "Listening"
      }
    _isListening.value = true
    recognizerInstance.startListening(intent)
  }

  private fun scheduleRestart(delayMs: Long = 300L) {
    if (stopRequested) return
    if (!_micEnabled.value) return
    restartJob?.cancel()
    restartJob =
      scope.launch {
        delay(delayMs)
        mainHandler.post {
          if (stopRequested || !_micEnabled.value) return@post
          try {
            startListeningSession()
          } catch (_: Throwable) {
            // retry through onError
          }
        }
      }
  }

  private fun queueRecognizedMessage(text: String) {
    val message = text.trim()
    _liveTranscript.value = null
    if (message.isEmpty()) return
    PhoneDiagLog.outgoing(
      "mic",
      "queueing msg chars=${message.length} \"${message.take(40)}${if (message.length > 40) "…" else ""}\"",
    )
    appendConversation(
      role = VoiceConversationRole.User,
      text = message,
    )
    enqueueMessage(message)
    publishQueue()
  }

  private fun scheduleTranscriptFlush(expectedText: String) {
    // Hold mode owns the turn boundary — partial-idle auto-commit would
    // race against the release handler in [stopHold].
    if (holdMode) return
    transcriptFlushJob?.cancel()
    transcriptFlushJob =
      scope.launch {
        delay(transcriptIdleFlushMs)
        if (!_micEnabled.value || _isSending.value) return@launch
        val current = _liveTranscript.value?.trim().orEmpty()
        if (current.isEmpty() || current != expectedText) return@launch
        flushedPartialTranscript = current
        queueRecognizedMessage(current)
        sendQueuedIfIdle()
      }
  }

  private fun publishQueue() {
    _queuedMessages.value = snapshotMessageQueue()
  }

  private fun sendQueuedIfIdle() {
    if (_isSending.value) return
    if (!hasQueuedMessages()) {
      if (_micEnabled.value) {
        _statusText.value = "Listening"
      } else {
        _statusText.value = "Mic off"
      }
      return
    }
    if (!gatewayConnected) {
      _statusText.value = queuedWaitingStatus()
      return
    }

    val next = firstQueuedMessage() ?: return
    _isSending.value = true
    pendingRunTimeoutJob?.cancel()
    pendingRunTimeoutJob = null
    _statusText.value = if (_micEnabled.value) "Listening · sending queued voice" else "Sending queued voice"

    scope.launch {
      try {
        PhoneDiagLog.outgoing("mic", "chat.send chars=${next.length}")
        val runId = sendToGateway(next) { earlyRunId ->
          // Called with the idempotency key before chat.send fires so that
          // pendingRunId is populated before any chat events can arrive.
          pendingRunId = earlyRunId
          rememberRunId(earlyRunId)
        }
        // Update to the real runId if the gateway returned a different one.
        if (runId != null && runId != pendingRunId) pendingRunId = runId
        if (runId != null) rememberRunId(runId)
        if (runId == null) {
          PhoneDiagLog.warn("mic", "chat.send returned null runId — send dropped")
          pendingRunTimeoutJob?.cancel()
          pendingRunTimeoutJob = null
          removeFirstQueuedMessage()
          publishQueue()
          _isSending.value = false
          pendingAssistantEntryId = null
          sendQueuedIfIdle()
        } else {
          PhoneDiagLog.info("mic", "chat.send runId=${runId.take(8)} armed")
          armPendingRunTimeout(runId)
        }
      } catch (err: Throwable) {
        PhoneDiagLog.error("mic", "chat.send threw: ${err.message?.take(60) ?: err::class.simpleName}")
        pendingRunTimeoutJob?.cancel()
        pendingRunTimeoutJob = null
        _isSending.value = false
        pendingRunId = null
        pendingAssistantEntryId = null
        _statusText.value =
          if (!gatewayConnected) {
            queuedWaitingStatus()
          } else {
            "Send failed: ${err.message ?: err::class.simpleName}"
          }
      }
    }
  }

  private fun armPendingRunTimeout(runId: String) {
    pendingRunTimeoutJob?.cancel()
    pendingRunTimeoutJob =
      scope.launch {
        delay(pendingRunTimeoutMs)
        if (pendingRunId != runId) return@launch
        // Watchdog fired — clear pending state so new turns aren't blocked,
        // but DO NOT auto-resend the queued message. The old "resend on
        // timeout" behavior caused duplicate turns when the original reply
        // arrived a few seconds late. recentRunIds still holds this id, so
        // if the gateway eventually emits a final/error/aborted for it, the
        // reply lands in the UI and clears the queued entry naturally (see
        // handleGatewayEvent's !matchesPending branch).
        PhoneDiagLog.warn(
          "mic",
          "pending run watchdog: runId=${runId.take(8)} (late reply still accepted, no auto-resend)",
        )
        pendingRunId = null
        pendingAssistantEntryId = null
        _isSending.value = false
        _statusText.value =
          if (gatewayConnected) {
            "Reply slow; still waiting"
          } else {
            queuedWaitingStatus()
          }
      }
  }

  private fun completePendingTurn() {
    pendingRunTimeoutJob?.cancel()
    pendingRunTimeoutJob = null
    if (removeFirstQueuedMessage() != null) {
      publishQueue()
    }
    pendingRunId = null
    pendingAssistantEntryId = null
    _isSending.value = false
    sendQueuedIfIdle()
  }

  private fun queuedWaitingStatus(): String {
    return "${queuedMessageCount()} queued · waiting for gateway"
  }

  private fun appendConversation(
    role: VoiceConversationRole,
    text: String,
    isStreaming: Boolean = false,
  ): String {
    val id = UUID.randomUUID().toString()
    _conversation.value =
      (_conversation.value + VoiceConversationEntry(id = id, role = role, text = text, isStreaming = isStreaming))
        .takeLast(maxConversationEntries)
    return id
  }

  private fun updateConversationEntry(id: String, text: String?, isStreaming: Boolean) {
    val current = _conversation.value
    if (current.isEmpty()) return

    val targetIndex =
      when {
        current[current.lastIndex].id == id -> current.lastIndex
        else -> current.indexOfFirst { it.id == id }
      }
    if (targetIndex < 0) return

    val entry = current[targetIndex]
    val updatedText = text ?: entry.text
    if (updatedText == entry.text && entry.isStreaming == isStreaming) return
    val updated = current.toMutableList()
    updated[targetIndex] = entry.copy(text = updatedText, isStreaming = isStreaming)
    _conversation.value = updated
  }

  private fun upsertPendingAssistant(text: String, isStreaming: Boolean) {
    val currentId = pendingAssistantEntryId
    if (currentId == null) {
      pendingAssistantEntryId =
        appendConversation(
          role = VoiceConversationRole.Assistant,
          text = text,
          isStreaming = isStreaming,
        )
      return
    }
    updateConversationEntry(id = currentId, text = text, isStreaming = isStreaming)
  }

  private fun playAssistantReplyAsync(text: String) {
    val spoken = text.trim()
    if (spoken.isEmpty()) return
    scope.launch {
      try {
        speakAssistantReply(spoken)
      } catch (err: Throwable) {
        Log.w(tag, "assistant speech failed: ${err.message ?: err::class.simpleName}")
      }
    }
  }

  private fun disableMic(status: String) {
    stopRequested = true
    restartJob?.cancel()
    restartJob = null
    transcriptFlushJob?.cancel()
    transcriptFlushJob = null
    _micEnabled.value = false
    _isListening.value = false
    _inputLevel.value = 0f
    _statusText.value = status
    mainHandler.post {
      recognizer?.cancel()
      recognizer?.destroy()
      recognizer = null
    }
  }

  private fun hasMicPermission(): Boolean {
    return (
      ContextCompat.checkSelfPermission(context, Manifest.permission.RECORD_AUDIO) ==
        PackageManager.PERMISSION_GRANTED
      )
  }

  private fun parseAssistantText(payload: JsonObject): String? {
    val message = payload["message"].asObjectOrNull() ?: return null
    if (message["role"].asStringOrNull() != "assistant") return null
    val content = message["content"] as? JsonArray ?: return null

    val parts =
      content.mapNotNull { item ->
        val obj = item.asObjectOrNull() ?: return@mapNotNull null
        if (obj["type"].asStringOrNull() != "text") return@mapNotNull null
        obj["text"].asStringOrNull()?.trim()?.takeIf { it.isNotEmpty() }
      }
    if (parts.isEmpty()) return null
    return parts.joinToString("\n")
  }

  private val listener =
    object : RecognitionListener {
      override fun onReadyForSpeech(params: Bundle?) {
        _isListening.value = true
      }

      override fun onBeginningOfSpeech() {}

      override fun onRmsChanged(rmsdB: Float) {
        val level = ((rmsdB + 2f) / 12f).coerceIn(0f, 1f)
        _inputLevel.value = level
      }

      override fun onBufferReceived(buffer: ByteArray?) {}

      override fun onEndOfSpeech() {
        _inputLevel.value = 0f
        scheduleRestart()
      }

      override fun onError(error: Int) {
        if (stopRequested) return
        _isListening.value = false
        _inputLevel.value = 0f
        val status =
          when (error) {
            SpeechRecognizer.ERROR_AUDIO -> "Audio error"
            SpeechRecognizer.ERROR_CLIENT -> "Client error"
            SpeechRecognizer.ERROR_NETWORK -> "Network error"
            SpeechRecognizer.ERROR_NETWORK_TIMEOUT -> "Network timeout"
            SpeechRecognizer.ERROR_NO_MATCH -> "Listening"
            SpeechRecognizer.ERROR_RECOGNIZER_BUSY -> "Recognizer busy"
            SpeechRecognizer.ERROR_SERVER -> "Server error"
            SpeechRecognizer.ERROR_SPEECH_TIMEOUT -> "Listening"
            SpeechRecognizer.ERROR_INSUFFICIENT_PERMISSIONS -> "Microphone permission required"
            SpeechRecognizer.ERROR_LANGUAGE_NOT_SUPPORTED -> "Language not supported on this device"
            SpeechRecognizer.ERROR_LANGUAGE_UNAVAILABLE -> "Language unavailable on this device"
            SpeechRecognizer.ERROR_SERVER_DISCONNECTED -> "Speech service disconnected"
            SpeechRecognizer.ERROR_TOO_MANY_REQUESTS -> "Speech requests limited; retrying"
            else -> "Speech error ($error)"
          }
        _statusText.value = status
        PhoneDiagLog.warn("mic", "recognizer error=$error ($status)")

        if (
          error == SpeechRecognizer.ERROR_INSUFFICIENT_PERMISSIONS ||
            error == SpeechRecognizer.ERROR_LANGUAGE_NOT_SUPPORTED ||
            error == SpeechRecognizer.ERROR_LANGUAGE_UNAVAILABLE
        ) {
          disableMic(status)
          return
        }

        val restartDelayMs =
          when (error) {
            SpeechRecognizer.ERROR_NO_MATCH,
            SpeechRecognizer.ERROR_SPEECH_TIMEOUT,
            -> 1_200L
            SpeechRecognizer.ERROR_TOO_MANY_REQUESTS -> 2_500L
            else -> 600L
          }
        scheduleRestart(delayMs = restartDelayMs)
      }

      override fun onResults(results: Bundle?) {
        transcriptFlushJob?.cancel()
        transcriptFlushJob = null
        // Drain-window guard: suppress stray onResults that arrive after the
        // user released a press-and-hold (the text was already captured by
        // stopHold; this callback would otherwise double-queue).
        if (System.currentTimeMillis() < suppressResultsUntilMs) {
          _liveTranscript.value = null
          return
        }
        val text = results?.getStringArrayList(SpeechRecognizer.RESULTS_RECOGNITION).orEmpty().firstOrNull()
        if (!text.isNullOrBlank()) {
          val trimmed = text.trim()
          if (holdMode) {
            // Recognizer cut the session mid-hold. Accumulate what it heard
            // and keep listening; stopHold will flush on release.
            synchronized(accumulatedHoldText) { accumulatedHoldText.add(trimmed) }
            _liveTranscript.value = null
            scheduleRestart()
            return
          }
          if (trimmed != flushedPartialTranscript) {
            queueRecognizedMessage(trimmed)
            sendQueuedIfIdle()
          } else {
            flushedPartialTranscript = null
            _liveTranscript.value = null
          }
        }
        scheduleRestart()
      }

      override fun onPartialResults(partialResults: Bundle?) {
        val text = partialResults?.getStringArrayList(SpeechRecognizer.RESULTS_RECOGNITION).orEmpty().firstOrNull()
        if (!text.isNullOrBlank()) {
          val trimmed = text.trim()
          _liveTranscript.value = trimmed
          scheduleTranscriptFlush(trimmed)
        }
      }

      override fun onEvent(eventType: Int, params: Bundle?) {}
    }
}

private fun kotlinx.serialization.json.JsonElement?.asObjectOrNull(): JsonObject? =
  this as? JsonObject

private fun kotlinx.serialization.json.JsonElement?.asStringOrNull(): String? =
  (this as? JsonPrimitive)?.takeIf { it.isString }?.content
