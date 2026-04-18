package ai.openclaw.app

import android.Manifest
import android.content.Context
import android.content.pm.PackageManager
import android.os.SystemClock
import android.util.Log
import androidx.core.content.ContextCompat
import ai.openclaw.app.chat.ChatController
import ai.openclaw.app.chat.ChatMessage
import ai.openclaw.app.chat.ChatPendingToolCall
import ai.openclaw.app.chat.ChatSessionEntry
import ai.openclaw.app.chat.OutgoingAttachment
import ai.openclaw.app.gateway.DeviceAuthStore
import ai.openclaw.app.gateway.DeviceIdentityStore
import ai.openclaw.app.gateway.GatewayDiscovery
import ai.openclaw.app.gateway.GatewayEndpoint
import ai.openclaw.app.gateway.GatewaySession
import ai.openclaw.app.gateway.GatewayTlsProbeFailure
import ai.openclaw.app.gateway.GatewayTlsProbeResult
import ai.openclaw.app.gateway.probeGatewayTlsFingerprint
import ai.openclaw.app.node.*
import ai.openclaw.app.protocol.OpenClawCanvasA2UIAction
import ai.openclaw.app.voice.MicCaptureManager
import ai.openclaw.app.voice.TalkModeManager
import ai.openclaw.app.voice.VoiceConversationEntry
import ai.openclaw.app.wear.WearRelayLog
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.async
import kotlinx.coroutines.coroutineScope
import kotlinx.coroutines.delay
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.flow.combine
import kotlinx.coroutines.flow.distinctUntilChanged
import kotlinx.coroutines.launch
import kotlinx.coroutines.tasks.await
import kotlinx.serialization.Serializable
import kotlinx.serialization.json.Json
import kotlinx.serialization.json.JsonArray
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.JsonPrimitive
import kotlinx.serialization.json.buildJsonObject
import java.util.UUID
import java.util.concurrent.atomic.AtomicLong

class NodeRuntime(
  context: Context,
  val prefs: SecurePrefs = SecurePrefs(context.applicationContext),
  private val tlsFingerprintProbe: suspend (String, Int) -> GatewayTlsProbeResult = ::probeGatewayTlsFingerprint,
) {
  data class GatewayConnectAuth(
    val token: String?,
    val bootstrapToken: String?,
    val password: String?,
  )

  private val appContext = context.applicationContext
  private val scope = CoroutineScope(SupervisorJob() + Dispatchers.IO)
  private val deviceAuthStore = DeviceAuthStore(prefs)
  val canvas = CanvasController()
  val camera = CameraCaptureManager(appContext)
  val location = LocationCaptureManager(appContext)
  val sms = SmsManager(appContext)
  private val json = Json { ignoreUnknownKeys = true }

  private val externalAudioCaptureActive = MutableStateFlow(false)

  private val discovery = GatewayDiscovery(appContext, scope = scope)
  val gateways: StateFlow<List<GatewayEndpoint>> = discovery.gateways
  val discoveryStatusText: StateFlow<String> = discovery.statusText

  private val identityStore = DeviceIdentityStore(appContext)
  private var connectedEndpoint: GatewayEndpoint? = null
  private var activeGatewayAuth: GatewayConnectAuth? = null

  private val cameraHandler: CameraHandler = CameraHandler(
    appContext = appContext,
    camera = camera,
    externalAudioCaptureActive = externalAudioCaptureActive,
    showCameraHud = ::showCameraHud,
    triggerCameraFlash = ::triggerCameraFlash,
    invokeErrorFromThrowable = { invokeErrorFromThrowable(it) },
  )

  private val debugHandler: DebugHandler = DebugHandler(
    appContext = appContext,
    identityStore = identityStore,
  )

  private val locationHandler: LocationHandler = LocationHandler(
    appContext = appContext,
    location = location,
    json = json,
    isForeground = { _isForeground.value },
    locationPreciseEnabled = { locationPreciseEnabled.value },
  )

  private val deviceHandler: DeviceHandler = DeviceHandler(
    appContext = appContext,
    smsEnabled = BuildConfig.OPENCLAW_ENABLE_SMS,
    callLogEnabled = BuildConfig.OPENCLAW_ENABLE_CALL_LOG,
  )

  private val notificationsHandler: NotificationsHandler = NotificationsHandler(
    appContext = appContext,
  )

  private val systemHandler: SystemHandler = SystemHandler(
    appContext = appContext,
  )

  private val photosHandler: PhotosHandler = PhotosHandler(
    appContext = appContext,
  )

  private val contactsHandler: ContactsHandler = ContactsHandler(
    appContext = appContext,
  )

  private val calendarHandler: CalendarHandler = CalendarHandler(
    appContext = appContext,
  )

  private val callLogHandler: CallLogHandler = CallLogHandler(
    appContext = appContext,
  )

  private val motionHandler: MotionHandler = MotionHandler(
    appContext = appContext,
  )

  private val smsHandlerImpl: SmsHandler = SmsHandler(
    sms = sms,
  )

  private val a2uiHandler: A2UIHandler = A2UIHandler(
    canvas = canvas,
    json = json,
    getNodeCanvasHostUrl = { nodeSession.currentCanvasHostUrl() },
    getOperatorCanvasHostUrl = { operatorSession.currentCanvasHostUrl() },
  )

  private val connectionManager: ConnectionManager = ConnectionManager(
    prefs = prefs,
    cameraEnabled = { cameraEnabled.value },
    locationMode = { locationMode.value },
    voiceWakeMode = { VoiceWakeMode.Off },
    motionActivityAvailable = { motionHandler.isActivityAvailable() },
    motionPedometerAvailable = { motionHandler.isPedometerAvailable() },
    sendSmsAvailable = { BuildConfig.OPENCLAW_ENABLE_SMS && sms.canSendSms() },
    readSmsAvailable = { BuildConfig.OPENCLAW_ENABLE_SMS && sms.canReadSms() },
    smsSearchPossible = { BuildConfig.OPENCLAW_ENABLE_SMS && sms.hasTelephonyFeature() },
    callLogAvailable = { BuildConfig.OPENCLAW_ENABLE_CALL_LOG },
    hasRecordAudioPermission = { hasRecordAudioPermission() },
    manualTls = { manualTls.value },
  )

  private val invokeDispatcher: InvokeDispatcher = InvokeDispatcher(
    canvas = canvas,
    cameraHandler = cameraHandler,
    locationHandler = locationHandler,
    deviceHandler = deviceHandler,
    notificationsHandler = notificationsHandler,
    systemHandler = systemHandler,
    photosHandler = photosHandler,
    contactsHandler = contactsHandler,
    calendarHandler = calendarHandler,
    motionHandler = motionHandler,
    smsHandler = smsHandlerImpl,
    a2uiHandler = a2uiHandler,
    debugHandler = debugHandler,
    callLogHandler = callLogHandler,
    isForeground = { _isForeground.value },
    cameraEnabled = { cameraEnabled.value },
    locationEnabled = { locationMode.value != LocationMode.Off },
    sendSmsAvailable = { BuildConfig.OPENCLAW_ENABLE_SMS && sms.canSendSms() },
    readSmsAvailable = { BuildConfig.OPENCLAW_ENABLE_SMS && sms.canReadSms() },
    smsFeatureEnabled = { BuildConfig.OPENCLAW_ENABLE_SMS },
    smsTelephonyAvailable = { sms.hasTelephonyFeature() },
    callLogAvailable = { BuildConfig.OPENCLAW_ENABLE_CALL_LOG },
    debugBuild = { BuildConfig.DEBUG },
    refreshNodeCanvasCapability = { nodeSession.refreshNodeCanvasCapability() },
    onCanvasA2uiPush = {
      _canvasA2uiHydrated.value = true
      _canvasRehydratePending.value = false
      _canvasRehydrateErrorText.value = null
    },
    onCanvasA2uiReset = { _canvasA2uiHydrated.value = false },
    motionActivityAvailable = { motionHandler.isActivityAvailable() },
    motionPedometerAvailable = { motionHandler.isPedometerAvailable() },
  )

  data class GatewayTrustPrompt(
    val endpoint: GatewayEndpoint,
    val fingerprintSha256: String,
    val auth: GatewayConnectAuth,
  )

  private val _isConnected = MutableStateFlow(false)
  val isConnected: StateFlow<Boolean> = _isConnected.asStateFlow()
  private val _nodeConnected = MutableStateFlow(false)
  val nodeConnected: StateFlow<Boolean> = _nodeConnected.asStateFlow()

  private val _statusText = MutableStateFlow("Offline")
  val statusText: StateFlow<String> = _statusText.asStateFlow()

  private val _pendingGatewayTrust = MutableStateFlow<GatewayTrustPrompt?>(null)
  val pendingGatewayTrust: StateFlow<GatewayTrustPrompt?> = _pendingGatewayTrust.asStateFlow()

  private fun resolveNodeMainSessionKey(agentId: String? = gatewayDefaultAgentId): String {
    val deviceId = identityStore.loadOrCreate().deviceId
    return buildNodeMainSessionKey(deviceId, agentId)
  }

  private val _mainSessionKey = MutableStateFlow(resolveNodeMainSessionKey())
  val mainSessionKey: StateFlow<String> = _mainSessionKey.asStateFlow()

  private val cameraHudSeq = AtomicLong(0)
  private val _cameraHud = MutableStateFlow<CameraHudState?>(null)
  val cameraHud: StateFlow<CameraHudState?> = _cameraHud.asStateFlow()

  private val _cameraFlashToken = MutableStateFlow(0L)
  val cameraFlashToken: StateFlow<Long> = _cameraFlashToken.asStateFlow()

  private val _canvasA2uiHydrated = MutableStateFlow(false)
  val canvasA2uiHydrated: StateFlow<Boolean> = _canvasA2uiHydrated.asStateFlow()
  private val _canvasRehydratePending = MutableStateFlow(false)
  val canvasRehydratePending: StateFlow<Boolean> = _canvasRehydratePending.asStateFlow()
  private val _canvasRehydrateErrorText = MutableStateFlow<String?>(null)
  val canvasRehydrateErrorText: StateFlow<String?> = _canvasRehydrateErrorText.asStateFlow()

  private val _serverName = MutableStateFlow<String?>(null)
  val serverName: StateFlow<String?> = _serverName.asStateFlow()

  private val _remoteAddress = MutableStateFlow<String?>(null)
  val remoteAddress: StateFlow<String?> = _remoteAddress.asStateFlow()

  private val _seamColorArgb = MutableStateFlow(DEFAULT_SEAM_COLOR_ARGB)
  val seamColorArgb: StateFlow<Long> = _seamColorArgb.asStateFlow()

  data class WearDataPlane(
    val baseUrl: String,
    val publicAssets: Boolean,
    val streamTts: Boolean,
  )

  private val _wearDataPlane = MutableStateFlow<WearDataPlane?>(null)

  /** Current data-plane config from `config.get`, or null if the gateway hasn't published one. */
  fun wearRelayDataPlane(): WearDataPlane? = _wearDataPlane.value

  /** Current operator auth token, used to sign asset / stream URLs. */
  fun wearRelayAuthToken(): String? = activeGatewayAuth?.token?.takeIf { it.isNotEmpty() }

  private val _isForeground = MutableStateFlow(true)
  val isForeground: StateFlow<Boolean> = _isForeground.asStateFlow()

  private var gatewayDefaultAgentId: String? = null
  private var gatewayAgents: List<GatewayAgentSummary> = emptyList()
  private var didAutoRequestCanvasRehydrate = false
  private val canvasRehydrateSeq = AtomicLong(0)
  private var operatorConnected = false
  private var operatorStatusText: String = "Offline"
  private var nodeStatusText: String = "Offline"

  private val operatorSession =
    GatewaySession(
      scope = scope,
      identityStore = identityStore,
      deviceAuthStore = deviceAuthStore,
      onConnected = { name, remote, mainSessionKey ->
        operatorConnected = true
        operatorStatusText = "Connected"
        _serverName.value = name
        _remoteAddress.value = remote
        _seamColorArgb.value = DEFAULT_SEAM_COLOR_ARGB
        syncMainSessionKey(resolveAgentIdFromMainSessionKey(mainSessionKey))
        updateStatus()
        micCapture.onGatewayConnectionChanged(true)
        scope.launch {
          refreshHomeCanvasOverviewIfConnected()
          if (voiceReplySpeakerLazy.isInitialized()) {
            voiceReplySpeaker.refreshConfig()
          }
        }
      },
      onDisconnected = { message ->
        operatorConnected = false
        operatorStatusText = message
        _serverName.value = null
        _remoteAddress.value = null
        _seamColorArgb.value = DEFAULT_SEAM_COLOR_ARGB
        chat.applyMainSessionKey(resolveMainSessionKey())
        chat.onDisconnected(message)
        updateStatus()
        micCapture.onGatewayConnectionChanged(false)
      },
      onEvent = { event, payloadJson ->
        handleGatewayEvent(event, payloadJson)
      },
    )

  private val nodeSession =
    GatewaySession(
      scope = scope,
      identityStore = identityStore,
      deviceAuthStore = deviceAuthStore,
      onConnected = { _, _, _ ->
        _nodeConnected.value = true
        nodeStatusText = "Connected"
        didAutoRequestCanvasRehydrate = false
        _canvasA2uiHydrated.value = false
        _canvasRehydratePending.value = false
        _canvasRehydrateErrorText.value = null
        updateStatus()
        showLocalCanvasOnConnect()
        val endpoint = connectedEndpoint
        val auth = activeGatewayAuth
        if (endpoint != null && auth != null) {
          maybeStartOperatorSessionAfterNodeConnect(endpoint, auth)
        }
      },
      onDisconnected = { message ->
        _nodeConnected.value = false
        nodeStatusText = message
        didAutoRequestCanvasRehydrate = false
        _canvasA2uiHydrated.value = false
        _canvasRehydratePending.value = false
        _canvasRehydrateErrorText.value = null
        updateStatus()
        showLocalCanvasOnDisconnect()
      },
      onEvent = { _, _ -> },
      onInvoke = { req ->
        invokeDispatcher.handleInvoke(req.command, req.paramsJson)
      },
      onTlsFingerprint = { stableId, fingerprint ->
        prefs.saveGatewayTlsFingerprint(stableId, fingerprint)
      },
    )

  init {
    DeviceNotificationListenerService.setNodeEventSink { event, payloadJson ->
      scope.launch {
        nodeSession.sendNodeEvent(event = event, payloadJson = payloadJson)
      }
    }
  }

  private val chat: ChatController =
    ChatController(
      scope = scope,
      session = operatorSession,
      json = json,
      supportsChatSubscribe = false,
    ).also {
      it.applyMainSessionKey(_mainSessionKey.value)
    }
  private val voiceReplySpeakerLazy: Lazy<TalkModeManager> = lazy {
    // Reuse the existing TalkMode speech engine for native Android TTS playback
    // without enabling the legacy talk capture loop.
    TalkModeManager(
      context = appContext,
      scope = scope,
      session = operatorSession,
      supportsChatSubscribe = false,
      isConnected = { operatorConnected },
      onBeforeSpeak = { micCapture.pauseForTts() },
      onAfterSpeak = { micCapture.resumeAfterTts() },
    ).also { speaker ->
      speaker.setPlaybackEnabled(prefs.speakerEnabled.value)
    }
  }
  private val voiceReplySpeaker: TalkModeManager
    get() = voiceReplySpeakerLazy.value

  private val micCapture: MicCaptureManager by lazy {
    MicCaptureManager(
      context = appContext,
      scope = scope,
      sendToGateway = { message, onRunIdKnown ->
        val idempotencyKey = UUID.randomUUID().toString()
        // Notify MicCaptureManager of the idempotency key *before* the network
        // call so pendingRunId is set before any chat events can arrive.
        onRunIdKnown(idempotencyKey)
        val params =
          buildJsonObject {
            put("sessionKey", JsonPrimitive(resolveMainSessionKey()))
            put("message", JsonPrimitive(message))
            put("thinking", JsonPrimitive(chatThinkingLevel.value))
            put("timeoutMs", JsonPrimitive(30_000))
            put("idempotencyKey", JsonPrimitive(idempotencyKey))
          }
        val response = operatorSession.request("chat.send", params.toString())
        parseChatSendRunId(response) ?: idempotencyKey
      },
      speakAssistantReply = { text ->
        // Voice-tab replies should speak through the dedicated reply speaker.
        // Relying on talkMode.ttsOnAllResponses here can drop playback if the
        // chat-event path misses the terminal event for this turn.
        voiceReplySpeaker.speakAssistantReply(text)
      },
    )
  }

  val micStatusText: StateFlow<String>
    get() = micCapture.statusText

  val micLiveTranscript: StateFlow<String?>
    get() = micCapture.liveTranscript

  val micIsListening: StateFlow<Boolean>
    get() = micCapture.isListening

  val micEnabled: StateFlow<Boolean>
    get() = micCapture.micEnabled

  val micCooldown: StateFlow<Boolean>
    get() = micCapture.micCooldown

  val micQueuedMessages: StateFlow<List<String>>
    get() = micCapture.queuedMessages

  val micConversation: StateFlow<List<VoiceConversationEntry>>
    get() = micCapture.conversation

  val micInputLevel: StateFlow<Float>
    get() = micCapture.inputLevel

  val micIsSending: StateFlow<Boolean>
    get() = micCapture.isSending

  private val talkMode: TalkModeManager by lazy {
    TalkModeManager(
      context = appContext,
      scope = scope,
      session = operatorSession,
      supportsChatSubscribe = true,
      isConnected = { operatorConnected },
      onBeforeSpeak = { micCapture.pauseForTts() },
      onAfterSpeak = { micCapture.resumeAfterTts() },
    )
  }

  private fun syncMainSessionKey(agentId: String?) {
    val resolvedKey = resolveNodeMainSessionKey(agentId)
    // Always push the resolved session key into TalkMode, even when the
    // state flow value is unchanged, so lazy TalkMode instances do not
    // stay on the default "main" session key.
    talkMode.setMainSessionKey(resolvedKey)
    if (_mainSessionKey.value == resolvedKey) return
    _mainSessionKey.value = resolvedKey
    chat.applyMainSessionKey(resolvedKey)
    updateHomeCanvasState()
  }

  private fun updateStatus() {
    _isConnected.value = operatorConnected
    val operator = operatorStatusText.trim()
    val node = nodeStatusText.trim()
    _statusText.value =
      when {
        operatorConnected && _nodeConnected.value -> "Connected"
        operatorConnected && !_nodeConnected.value -> "Connected (node offline)"
        !operatorConnected && _nodeConnected.value ->
          if (operator.isNotEmpty() && operator != "Offline") {
            "Connected (operator: $operator)"
          } else {
            "Connected (operator offline)"
          }
        operator.isNotBlank() && operator != "Offline" -> operator
        else -> node
      }
    updateHomeCanvasState()
  }

  private fun resolveMainSessionKey(): String {
    val trimmed = _mainSessionKey.value.trim()
    return if (trimmed.isEmpty()) "main" else trimmed
  }

  private fun showLocalCanvasOnConnect() {
    _canvasA2uiHydrated.value = false
    _canvasRehydratePending.value = false
    _canvasRehydrateErrorText.value = null
    canvas.navigate("")
  }

  private fun showLocalCanvasOnDisconnect() {
    _canvasA2uiHydrated.value = false
    _canvasRehydratePending.value = false
    _canvasRehydrateErrorText.value = null
    canvas.navigate("")
  }

  fun refreshHomeCanvasOverviewIfConnected() {
    if (!operatorConnected) {
      updateHomeCanvasState()
      return
    }
    scope.launch {
      refreshBrandingFromGateway()
      refreshAgentsFromGateway()
    }
  }

  fun requestCanvasRehydrate(source: String = "manual", force: Boolean = true) {
    scope.launch {
      if (!_nodeConnected.value) {
        _canvasRehydratePending.value = false
        _canvasRehydrateErrorText.value = "Node offline. Reconnect and retry."
        return@launch
      }
      if (!force && didAutoRequestCanvasRehydrate) return@launch
      didAutoRequestCanvasRehydrate = true
      val requestId = canvasRehydrateSeq.incrementAndGet()
      _canvasRehydratePending.value = true
      _canvasRehydrateErrorText.value = null

      val sessionKey = resolveMainSessionKey()
      val prompt =
        "Restore canvas now for session=$sessionKey source=$source. " +
          "If existing A2UI state exists, replay it immediately. " +
          "If not, create and render a compact mobile-friendly dashboard in Canvas."
      val sent =
        nodeSession.sendNodeEvent(
          event = "agent.request",
          payloadJson =
            buildJsonObject {
              put("message", JsonPrimitive(prompt))
              put("sessionKey", JsonPrimitive(sessionKey))
              put("thinking", JsonPrimitive("low"))
              put("deliver", JsonPrimitive(false))
            }.toString(),
        )
      if (!sent) {
        if (!force) {
          didAutoRequestCanvasRehydrate = false
        }
        if (canvasRehydrateSeq.get() == requestId) {
          _canvasRehydratePending.value = false
          _canvasRehydrateErrorText.value = "Failed to request restore. Tap to retry."
        }
        Log.w("OpenClawCanvas", "canvas rehydrate request failed ($source): transport unavailable")
        return@launch
      }
      scope.launch {
        delay(20_000)
        if (canvasRehydrateSeq.get() != requestId) return@launch
        if (!_canvasRehydratePending.value) return@launch
        if (_canvasA2uiHydrated.value) return@launch
        _canvasRehydratePending.value = false
        _canvasRehydrateErrorText.value = "No canvas update yet. Tap to retry."
      }
    }
  }

  val instanceId: StateFlow<String> = prefs.instanceId
  val displayName: StateFlow<String> = prefs.displayName
  val cameraEnabled: StateFlow<Boolean> = prefs.cameraEnabled
  val locationMode: StateFlow<LocationMode> = prefs.locationMode
  val locationPreciseEnabled: StateFlow<Boolean> = prefs.locationPreciseEnabled
  val preventSleep: StateFlow<Boolean> = prefs.preventSleep
  val manualEnabled: StateFlow<Boolean> = prefs.manualEnabled
  val manualHost: StateFlow<String> = prefs.manualHost
  val manualPort: StateFlow<Int> = prefs.manualPort
  val manualTls: StateFlow<Boolean> = prefs.manualTls
  val gatewayToken: StateFlow<String> = prefs.gatewayToken
  val onboardingCompleted: StateFlow<Boolean> = prefs.onboardingCompleted
  fun setGatewayToken(value: String) = prefs.setGatewayToken(value)
  fun setGatewayBootstrapToken(value: String) = prefs.setGatewayBootstrapToken(value)
  fun setGatewayPassword(value: String) = prefs.setGatewayPassword(value)
  fun resetGatewaySetupAuth() {
    prefs.clearGatewaySetupAuth()
    val deviceId = identityStore.loadOrCreate().deviceId
    deviceAuthStore.clearToken(deviceId, "node")
    deviceAuthStore.clearToken(deviceId, "operator")
  }
  fun setOnboardingCompleted(value: Boolean) = prefs.setOnboardingCompleted(value)
  val lastDiscoveredStableId: StateFlow<String> = prefs.lastDiscoveredStableId
  val canvasDebugStatusEnabled: StateFlow<Boolean> = prefs.canvasDebugStatusEnabled
  val notificationForwardingEnabled: StateFlow<Boolean> = prefs.notificationForwardingEnabled
  val notificationForwardingMode: StateFlow<NotificationPackageFilterMode> =
    prefs.notificationForwardingMode
  val notificationForwardingPackages: StateFlow<Set<String>> = prefs.notificationForwardingPackages
  val notificationForwardingQuietHoursEnabled: StateFlow<Boolean> =
    prefs.notificationForwardingQuietHoursEnabled
  val notificationForwardingQuietStart: StateFlow<String> = prefs.notificationForwardingQuietStart
  val notificationForwardingQuietEnd: StateFlow<String> = prefs.notificationForwardingQuietEnd
  val notificationForwardingMaxEventsPerMinute: StateFlow<Int> =
    prefs.notificationForwardingMaxEventsPerMinute
  val notificationForwardingSessionKey: StateFlow<String?> = prefs.notificationForwardingSessionKey

  private var didAutoConnect = false

  val chatSessionKey: StateFlow<String> = chat.sessionKey
  val chatSessionId: StateFlow<String?> = chat.sessionId
  val chatMessages: StateFlow<List<ChatMessage>> = chat.messages
  val chatError: StateFlow<String?> = chat.errorText
  val chatHealthOk: StateFlow<Boolean> = chat.healthOk
  val chatThinkingLevel: StateFlow<String> = chat.thinkingLevel
  val chatStreamingAssistantText: StateFlow<String?> = chat.streamingAssistantText
  val chatPendingToolCalls: StateFlow<List<ChatPendingToolCall>> = chat.pendingToolCalls
  val chatSessions: StateFlow<List<ChatSessionEntry>> = chat.sessions
  val pendingRunCount: StateFlow<Int> = chat.pendingRunCount

  init {
    if (prefs.voiceWakeMode.value != VoiceWakeMode.Off) {
      prefs.setVoiceWakeMode(VoiceWakeMode.Off)
    }

    scope.launch {
      prefs.loadGatewayToken()
    }

    scope.launch {
      prefs.talkEnabled.collect { enabled ->
        // MicCaptureManager handles STT + send to gateway, while the dedicated
        // reply speaker handles TTS for assistant replies in the voice tab.
        micCapture.setMicEnabled(enabled)
        if (enabled) {
          talkMode.ttsOnAllResponses = false
          scope.launch { talkMode.ensureChatSubscribed() }
        }
        externalAudioCaptureActive.value = enabled
      }
    }

    scope.launch(Dispatchers.Default) {
      gateways.collect { list ->
        seedLastDiscoveredGateway(list)
        autoConnectIfNeeded()
      }
    }

    scope.launch {
      combine(
        canvasDebugStatusEnabled,
        statusText,
        serverName,
        remoteAddress,
      ) { debugEnabled, status, server, remote ->
        Quad(debugEnabled, status, server, remote)
      }.distinctUntilChanged()
        .collect { (debugEnabled, status, server, remote) ->
          canvas.setDebugStatusEnabled(debugEnabled)
          if (!debugEnabled) return@collect
          canvas.setDebugStatus(status, server ?: remote)
        }
    }

    updateHomeCanvasState()
  }

  fun setForeground(value: Boolean) {
    _isForeground.value = value
    if (value) {
      reconnectPreferredGatewayOnForeground()
    } else {
      stopActiveVoiceSession()
    }
  }

  private fun seedLastDiscoveredGateway(list: List<GatewayEndpoint>) {
    if (list.isEmpty()) return
    if (lastDiscoveredStableId.value.trim().isNotEmpty()) return
    prefs.setLastDiscoveredStableId(list.first().stableId)
  }

  private fun resolvePreferredGatewayEndpoint(): GatewayEndpoint? {
    if (manualEnabled.value) {
      val host = manualHost.value.trim()
      val port = manualPort.value
      if (host.isEmpty() || port !in 1..65535) return null
      return GatewayEndpoint.manual(host = host, port = port)
    }

    val targetStableId = lastDiscoveredStableId.value.trim()
    if (targetStableId.isEmpty()) return null
    val endpoint = gateways.value.firstOrNull { it.stableId == targetStableId } ?: return null
    val storedFingerprint = prefs.loadGatewayTlsFingerprint(endpoint.stableId)?.trim().orEmpty()
    if (storedFingerprint.isEmpty()) return null
    return endpoint
  }

  private fun autoConnectIfNeeded() {
    if (didAutoConnect) return
    if (_isConnected.value) return
    val endpoint = resolvePreferredGatewayEndpoint() ?: return
    didAutoConnect = true
    connect(endpoint)
  }

  private fun reconnectPreferredGatewayOnForeground() {
    if (_isConnected.value) return
    if (_pendingGatewayTrust.value != null) return
    if (connectedEndpoint != null) {
      refreshGatewayConnection()
      return
    }
    resolvePreferredGatewayEndpoint()?.let(::connect)
  }

  fun setDisplayName(value: String) {
    prefs.setDisplayName(value)
  }

  fun setCameraEnabled(value: Boolean) {
    prefs.setCameraEnabled(value)
  }

  fun setLocationMode(mode: LocationMode) {
    prefs.setLocationMode(mode)
  }

  fun setLocationPreciseEnabled(value: Boolean) {
    prefs.setLocationPreciseEnabled(value)
  }

  fun setPreventSleep(value: Boolean) {
    prefs.setPreventSleep(value)
  }

  fun setManualEnabled(value: Boolean) {
    prefs.setManualEnabled(value)
  }

  fun setManualHost(value: String) {
    prefs.setManualHost(value)
  }

  fun setManualPort(value: Int) {
    prefs.setManualPort(value)
  }

  fun setManualTls(value: Boolean) {
    prefs.setManualTls(value)
  }

  fun setCanvasDebugStatusEnabled(value: Boolean) {
    prefs.setCanvasDebugStatusEnabled(value)
  }

  fun setNotificationForwardingEnabled(value: Boolean) {
    prefs.setNotificationForwardingEnabled(value)
  }

  fun setNotificationForwardingMode(mode: NotificationPackageFilterMode) {
    prefs.setNotificationForwardingMode(mode)
  }

  fun setNotificationForwardingPackages(packages: List<String>) {
    prefs.setNotificationForwardingPackages(packages)
  }

  fun setNotificationForwardingQuietHours(
    enabled: Boolean,
    start: String,
    end: String,
  ): Boolean {
    return prefs.setNotificationForwardingQuietHours(enabled = enabled, start = start, end = end)
  }

  fun setNotificationForwardingMaxEventsPerMinute(value: Int) {
    prefs.setNotificationForwardingMaxEventsPerMinute(value)
  }

  fun setNotificationForwardingSessionKey(value: String?) {
    prefs.setNotificationForwardingSessionKey(value)
  }

  fun setVoiceScreenActive(active: Boolean) {
    if (!active) {
      stopActiveVoiceSession()
    }
    // Don't re-enable on active=true; mic toggle drives that
  }

  fun setMicEnabled(value: Boolean) {
    prefs.setTalkEnabled(value)
    if (value) {
      // Tapping mic on interrupts any active TTS (barge-in)
      stopVoicePlayback()
      talkMode.ttsOnAllResponses = false
      scope.launch { talkMode.ensureChatSubscribed() }
    }
    micCapture.setMicEnabled(value)
    externalAudioCaptureActive.value = value
  }

  val speakerEnabled: StateFlow<Boolean>
    get() = prefs.speakerEnabled

  fun setSpeakerEnabled(value: Boolean) {
    prefs.setSpeakerEnabled(value)
    if (voiceReplySpeakerLazy.isInitialized()) {
      voiceReplySpeaker.setPlaybackEnabled(value)
    }
    // Keep TalkMode in sync so any active Talk playback also respects speaker mute.
    talkMode.setPlaybackEnabled(value)
  }

  private fun stopActiveVoiceSession() {
    talkMode.ttsOnAllResponses = false
    stopVoicePlayback()
    micCapture.setMicEnabled(false)
    prefs.setTalkEnabled(false)
    externalAudioCaptureActive.value = false
  }

  private fun stopVoicePlayback() {
    talkMode.stopTts()
    if (voiceReplySpeakerLazy.isInitialized()) {
      voiceReplySpeaker.stopTts()
    }
  }

  fun refreshGatewayConnection() {
    val endpoint =
      connectedEndpoint ?: run {
        _statusText.value = "Failed: no cached gateway endpoint"
        return
      }
    operatorStatusText = "Connecting…"
    updateStatus()
    connectWithAuth(endpoint = endpoint, auth = resolveGatewayConnectAuth(), reconnect = true)
  }

  private fun connectWithAuth(
    endpoint: GatewayEndpoint,
    auth: GatewayConnectAuth,
    reconnect: Boolean = false,
  ) {
    activeGatewayAuth = auth
    val tls = connectionManager.resolveTlsParams(endpoint)
    val operatorAuth =
      resolveOperatorSessionConnectAuth(
        auth = auth,
        storedOperatorToken = loadStoredRoleDeviceToken("operator"),
      )
    if (operatorAuth == null) {
      operatorConnected = false
      operatorStatusText = "Offline"
      operatorSession.disconnect()
      updateStatus()
    } else {
      operatorSession.connect(
        endpoint,
        operatorAuth.token,
        operatorAuth.bootstrapToken,
        operatorAuth.password,
        connectionManager.buildOperatorConnectOptions(),
        tls,
      )
    }
    nodeSession.connect(
      endpoint,
      auth.token,
      auth.bootstrapToken,
      auth.password,
      connectionManager.buildNodeConnectOptions(),
      tls,
    )
    if (reconnect && operatorAuth != null) {
      operatorSession.reconnect()
    }
    if (reconnect) {
      nodeSession.reconnect()
    }
  }

  private fun beginConnect(
    endpoint: GatewayEndpoint,
    auth: GatewayConnectAuth,
  ) {
    val tls = connectionManager.resolveTlsParams(endpoint)
    if (tls?.required == true && tls.expectedFingerprint.isNullOrBlank()) {
      // First-time TLS: capture fingerprint, ask user to verify out-of-band, then store and connect.
      _statusText.value = "Verify gateway TLS fingerprint…"
      scope.launch {
        val tlsProbe = tlsFingerprintProbe(endpoint.host, endpoint.port)
        val fp = tlsProbe.fingerprintSha256 ?: run {
          _statusText.value = gatewayTlsProbeFailureMessage(tlsProbe.failure)
          return@launch
        }
        _pendingGatewayTrust.value =
          GatewayTrustPrompt(endpoint = endpoint, fingerprintSha256 = fp, auth = auth)
      }
      return
    }

    connectedEndpoint = endpoint
    operatorStatusText = "Connecting…"
    nodeStatusText = "Connecting…"
    updateStatus()
    connectWithAuth(endpoint = endpoint, auth = auth)
  }

  fun connect(endpoint: GatewayEndpoint) {
    beginConnect(endpoint = endpoint, auth = resolveGatewayConnectAuth())
  }

  fun connect(
    endpoint: GatewayEndpoint,
    auth: GatewayConnectAuth,
  ) {
    beginConnect(endpoint = endpoint, auth = resolveGatewayConnectAuth(auth))
  }

  internal fun resolveGatewayConnectAuth(explicitAuth: GatewayConnectAuth? = null): GatewayConnectAuth {
    return explicitAuth
      ?: GatewayConnectAuth(
        token = prefs.loadGatewayToken(),
        bootstrapToken = prefs.loadGatewayBootstrapToken(),
        password = prefs.loadGatewayPassword(),
      )
  }

  fun acceptGatewayTrustPrompt() {
    val prompt = _pendingGatewayTrust.value ?: return
    _pendingGatewayTrust.value = null
    prefs.saveGatewayTlsFingerprint(prompt.endpoint.stableId, prompt.fingerprintSha256)
    beginConnect(endpoint = prompt.endpoint, auth = prompt.auth)
  }

  fun declineGatewayTrustPrompt() {
    _pendingGatewayTrust.value = null
    _statusText.value = "Offline"
  }

  private fun gatewayTlsProbeFailureMessage(failure: GatewayTlsProbeFailure?): String {
    return when (failure) {
      GatewayTlsProbeFailure.TLS_UNAVAILABLE ->
        "Failed: this host requires wss:// or Tailscale Serve. No TLS endpoint detected."
      GatewayTlsProbeFailure.ENDPOINT_UNREACHABLE, null ->
        "Failed: couldn't reach the secure gateway endpoint for this host."
    }
  }

  private fun hasRecordAudioPermission(): Boolean {
    return (
      ContextCompat.checkSelfPermission(appContext, Manifest.permission.RECORD_AUDIO) ==
        PackageManager.PERMISSION_GRANTED
      )
  }

  fun connectManual() {
    val host = manualHost.value.trim()
    val port = manualPort.value
    if (host.isEmpty() || port <= 0 || port > 65535) {
      _statusText.value = "Failed: invalid manual host/port"
      return
    }
    connect(GatewayEndpoint.manual(host = host, port = port))
  }

  private fun loadStoredRoleDeviceToken(role: String): String? {
    val deviceId = identityStore.loadOrCreate().deviceId
    return deviceAuthStore.loadToken(deviceId, role)
  }

  private fun maybeStartOperatorSessionAfterNodeConnect(
    endpoint: GatewayEndpoint,
    auth: GatewayConnectAuth,
  ) {
    if (operatorConnected || operatorStatusText == "Connecting…") {
      return
    }
    val operatorAuth =
      resolveOperatorSessionConnectAuth(
        auth = auth,
        storedOperatorToken = loadStoredRoleDeviceToken("operator"),
      ) ?: return
    operatorStatusText = "Connecting…"
    updateStatus()
    operatorSession.connect(
      endpoint,
      operatorAuth.token,
      operatorAuth.bootstrapToken,
      operatorAuth.password,
      connectionManager.buildOperatorConnectOptions(),
      connectionManager.resolveTlsParams(endpoint),
    )
  }

  fun disconnect() {
    connectedEndpoint = null
    activeGatewayAuth = null
    _pendingGatewayTrust.value = null
    operatorSession.disconnect()
    nodeSession.disconnect()
  }

  fun handleCanvasA2UIActionFromWebView(payloadJson: String) {
    scope.launch {
      val trimmed = payloadJson.trim()
      if (trimmed.isEmpty()) return@launch

      val root =
        try {
          json.parseToJsonElement(trimmed).asObjectOrNull() ?: return@launch
        } catch (_: Throwable) {
          return@launch
        }

      val userActionObj = (root["userAction"] as? JsonObject) ?: root
      val actionId = (userActionObj["id"] as? JsonPrimitive)?.content?.trim().orEmpty().ifEmpty {
        java.util.UUID.randomUUID().toString()
      }
      val name = OpenClawCanvasA2UIAction.extractActionName(userActionObj) ?: return@launch

      val surfaceId =
        (userActionObj["surfaceId"] as? JsonPrimitive)?.content?.trim().orEmpty().ifEmpty { "main" }
      val sourceComponentId =
        (userActionObj["sourceComponentId"] as? JsonPrimitive)?.content?.trim().orEmpty().ifEmpty { "-" }
      val contextJson = (userActionObj["context"] as? JsonObject)?.toString()

      val sessionKey = resolveMainSessionKey()
      val message =
        OpenClawCanvasA2UIAction.formatAgentMessage(
          actionName = name,
          sessionKey = sessionKey,
          surfaceId = surfaceId,
          sourceComponentId = sourceComponentId,
          host = displayName.value,
          instanceId = instanceId.value.lowercase(),
          contextJson = contextJson,
        )

      val connected = _nodeConnected.value
      var error: String? = null
      if (connected) {
        val sent =
          nodeSession.sendNodeEvent(
            event = "agent.request",
            payloadJson =
              buildJsonObject {
                put("message", JsonPrimitive(message))
                put("sessionKey", JsonPrimitive(sessionKey))
                put("thinking", JsonPrimitive("low"))
                put("deliver", JsonPrimitive(false))
                put("key", JsonPrimitive(actionId))
              }.toString(),
          )
        if (!sent) {
          error = "send failed"
        }
      } else {
        error = "gateway not connected"
      }

      try {
        canvas.eval(
          OpenClawCanvasA2UIAction.jsDispatchA2UIActionStatus(
            actionId = actionId,
            ok = connected && error == null,
            error = error,
          ),
        )
      } catch (_: Throwable) {
        // ignore
      }
    }
  }

  fun isTrustedCanvasActionUrl(rawUrl: String?): Boolean {
    return a2uiHandler.isTrustedCanvasActionUrl(rawUrl)
  }

  fun loadChat(sessionKey: String) {
    val key = sessionKey.trim().ifEmpty { resolveMainSessionKey() }
    chat.load(key)
  }

  fun refreshChat() {
    chat.refresh()
  }

  fun refreshChatSessions(limit: Int? = null) {
    chat.refreshSessions(limit = limit)
  }

  fun setChatThinkingLevel(level: String) {
    chat.setThinkingLevel(level)
  }

  fun switchChatSession(sessionKey: String) {
    chat.switchSession(sessionKey)
  }

  fun abortChat() {
    chat.abort()
  }

  fun sendChat(message: String, thinking: String, attachments: List<OutgoingAttachment>) {
    chat.sendMessage(message = message, thinkingLevel = thinking, attachments = attachments)
  }

  suspend fun sendChatAwaitAcceptance(
    message: String,
    thinking: String,
    attachments: List<OutgoingAttachment>,
  ): Boolean {
    return chat.sendMessageAwaitAcceptance(message = message, thinkingLevel = thinking, attachments = attachments)
  }

  // --- Wearable relay helpers (used by WearRelayService) ---

  /** List agents known to the gateway. Returns raw JSON from agents.list RPC. */
  suspend fun wearRelayAgentsList(): String? {
    if (!operatorConnected) return null
    return try {
      operatorSession.request("agents.list", "{}")
    } catch (_: Throwable) {
      null
    }
  }

  /**
   * Send a chat message on behalf of the watch and wait for the final assistant
   * response text. Returns null on error or timeout.
   */
  data class WearChatPart(
    val text: String,
    val isFinal: Boolean,
    val audioUrl: String? = null,
    val audioBase64: String? = null,
    val audioAssetRef: String? = null,
    val audioMime: String? = null,
  )

  /**
   * Directive prepended to every message the watch sends. Keeps replies compact
   * and speakable — the watch has no real screen for long prose and we're going
   * to TTS it anyway.
   */
  private val WEAR_BREVITY_PREFIX =
    "[Watch mode — reply in 1-2 short sentences, plain spoken language, no preamble, no lists.]\n\n"

  /**
   * Sends a watch-originated chat message, then streams every assistant text
   * block back via [onPart]. Completed intermediate blocks arrive first (text
   * only), and the last stable block is emitted as final with TTS audio.
   *
   * Returns null on success, or an error message. The function suspends until
   * the final part has been delivered to [onPart].
   */
  suspend fun wearRelayChatStream(
    agentId: String,
    userText: String,
    onPart: suspend (WearChatPart) -> Unit,
  ): String? {
    if (!operatorConnected) return "Operator session not connected"
    return coroutineScope {
      try {
        val deviceId = identityStore.loadOrCreate().deviceId
        val sessionKey = buildNodeMainSessionKey(deviceId, agentId)
        val historyParams = kotlinx.serialization.json.buildJsonObject {
          put("sessionKey", kotlinx.serialization.json.JsonPrimitive(sessionKey))
        }

        val baseline = try {
          historyMessageCount(operatorSession.requestDetailed("chat.history", historyParams.toString()))
        } catch (e: Throwable) {
          WearRelayLog.warn("chat", "$agentId baseline failed: ${e.javaClass.simpleName} — assuming 0")
          0
        }
        Log.d(TAG_WEAR, "$agentId baseline=$baseline")

        val params = kotlinx.serialization.json.buildJsonObject {
          put("sessionKey", kotlinx.serialization.json.JsonPrimitive(sessionKey))
          put("message", kotlinx.serialization.json.JsonPrimitive(WEAR_BREVITY_PREFIX + userText))
          put("thinking", kotlinx.serialization.json.JsonPrimitive("low"))
          put("timeoutMs", kotlinx.serialization.json.JsonPrimitive(60_000))
          put("idempotencyKey", kotlinx.serialization.json.JsonPrimitive(UUID.randomUUID().toString()))
        }

        // Fire chat.send in the background. The poll loop is the source of
        // truth — if chat.send is slow or throws, we keep watching history.
        val sendJob = async(Dispatchers.IO) {
          try {
            val res = operatorSession.request("chat.send", params.toString(), timeoutMs = 90_000)
            parseChatSendRunId(res)?.let { runId ->
              Log.d(TAG_WEAR, "runId=${runId.takeLast(6)}")
            }
          } catch (e: Throwable) {
            WearRelayLog.warn("chat", "chat.send: ${e.javaClass.simpleName}: ${e.message?.take(40)}")
          }
        }

        val emittedInterim = mutableListOf<String>()
        var lastSignature = emptyList<Pair<String, String>>()
        var stableCount = 0
        val maxPolls = 240
        val pollIntervalMs = 400L
        val stabilityThreshold = 3
        var sawAnyBlock = false

        var consecutivePollErrors = 0
        repeat(maxPolls) {
          kotlinx.coroutines.delay(pollIntervalMs)
          val history = try {
            operatorSession.requestDetailed("chat.history", historyParams.toString())
          } catch (e: Throwable) {
            consecutivePollErrors++
            // One transient error shouldn't kill the turn; log and retry.
            // If the session is *actually* dead, we'll bail after several.
            if (consecutivePollErrors >= 5) {
              WearRelayLog.error("chat", "$agentId abort: 5 poll errors in a row")
              return@coroutineScope "${e.javaClass.simpleName}: ${e.message}"
            }
            WearRelayLog.warn("chat", "$agentId poll: ${e.javaClass.simpleName} (retry)")
            return@repeat
          }
          consecutivePollErrors = 0
          val signature = buildAssistantContentSignature(history, baseline)
          if (signature.isEmpty()) return@repeat

          val blocks = signature.filter { it.first == "text" }.map { it.second }
          if (!sawAnyBlock && blocks.isNotEmpty()) {
            sawAnyBlock = true
            Log.d(TAG_WEAR, "first text after ${(it + 1) * pollIntervalMs}ms")
          }

          // Emit text blocks that a later block has superseded → interim.
          val finished = if (blocks.size > 1) blocks.dropLast(1) else emptyList()
          for ((i, block) in finished.withIndex()) {
            if (i >= emittedInterim.size && block.isNotBlank()) {
              emittedInterim.add(block)
              WearRelayLog.info("chat", "interim #$i: ${block.take(40)}")
              onPart(WearChatPart(block, isFinal = false))
            }
          }

          if (signature == lastSignature) {
            stableCount++
          } else {
            // Log newly-added tool work so the relay panel shows why we're
            // not finalizing yet.
            val newTails = signature.drop(lastSignature.size)
            for (b in newTails) {
              when (b.first) {
                "tool_use" -> WearRelayLog.info("chat", "tool: ${b.second.ifBlank { "(unnamed)" }}")
                "tool_result" -> WearRelayLog.info("chat", "tool result")
                else -> Unit
              }
            }
            stableCount = 0
            lastSignature = signature
          }

          // Only finalize when the LAST content block is text. Tool_use /
          // tool_result as the tail means the agent is still working — even
          // if nothing has changed for a while, it's waiting on tool output.
          val lastBlockType = signature.lastOrNull()?.first
          if (stableCount >= stabilityThreshold && lastBlockType == "text") {
            sendJob.cancel()
            val finalText = blocks.lastOrNull()?.takeIf { it.isNotBlank() }
            if (finalText != null) {
              WearRelayLog.info("chat", "final: \"${finalText.take(80)}\"")
              val audio = wearRelayTalkSpeak(finalText, agentId)
              onPart(WearChatPart(
                text = finalText,
                isFinal = true,
                audioUrl = audio?.audioUrl,
                audioBase64 = audio?.audioBase64,
                audioAssetRef = audio?.audioAssetRef,
                audioMime = audio?.mimeType,
              ))
            } else {
              onPart(WearChatPart("", isFinal = true))
            }
            return@coroutineScope null
          }
        }

        sendJob.cancel()
        val fallback = lastSignature.filter { it.first == "text" }.map { it.second }.lastOrNull()
        if (!fallback.isNullOrBlank()) {
          WearRelayLog.warn("chat", "poll limit hit, finalizing last seen")
          val audio = wearRelayTalkSpeak(fallback, agentId)
          onPart(WearChatPart(
            text = fallback,
            isFinal = true,
            audioUrl = audio?.audioUrl,
            audioBase64 = audio?.audioBase64,
            audioAssetRef = audio?.audioAssetRef,
            audioMime = audio?.mimeType,
          ))
          null
        } else {
          val reason = if (sawAnyBlock) "no stable reply" else "no response from gateway"
          "Gateway unresponsive — $reason after ${maxPolls * pollIntervalMs / 1000}s"
        }
      } catch (e: Throwable) {
        "${e.javaClass.simpleName}: ${e.message}"
      }
    }
  }

  private fun historyMessageCount(history: GatewaySession.RpcResult): Int {
    val payload = history.payloadJson?.takeIf { history.ok } ?: return 0
    val root = json.parseToJsonElement(payload).asObjectOrNull() ?: return 0
    val messages = root["messages"] as? kotlinx.serialization.json.JsonArray ?: return 0
    return messages.size
  }

  /**
   * Builds an ordered "signature" of the assistant content since [baseline].
   * Each entry is (type, identifier):
   *   - ("text", <full text>)   → text blocks, identified by content so
   *     streaming token-by-token growth shows up as a change.
   *   - ("tool_use", <name>)    → tool calls.
   *   - ("tool_result", <id>)   → tool results.
   *
   * Used both to detect change (any signature drift resets stability) and to
   * decide finality (only text-as-tail counts as done; tool_use-as-tail means
   * the agent is mid-work even when history looks static).
   */
  private fun buildAssistantContentSignature(
    history: GatewaySession.RpcResult,
    baseline: Int,
  ): List<Pair<String, String>> {
    val payload = history.payloadJson?.takeIf { history.ok } ?: return emptyList()
    val root = json.parseToJsonElement(payload).asObjectOrNull() ?: return emptyList()
    val messages = root["messages"] as? kotlinx.serialization.json.JsonArray ?: return emptyList()
    val sig = mutableListOf<Pair<String, String>>()
    for (i in baseline until messages.size) {
      val msg = messages[i].asObjectOrNull() ?: continue
      if (msg["role"].asStringOrNull() != "assistant") continue
      val content = msg["content"] as? kotlinx.serialization.json.JsonArray ?: continue
      for (c in content) {
        val obj = c.asObjectOrNull() ?: continue
        val type = obj["type"].asStringOrNull() ?: continue
        val id = when (type) {
          "text" -> obj["text"].asStringOrNull()?.takeIf { it.isNotBlank() } ?: continue
          "tool_use" -> obj["name"].asStringOrNull() ?: obj["id"].asStringOrNull() ?: ""
          "tool_result" -> obj["tool_use_id"].asStringOrNull() ?: ""
          else -> ""
        }
        sig.add(type to id)
      }
    }
    return sig
  }

  data class TalkSpeakResult(
    val audioUrl: String? = null,
    val audioBase64: String? = null,
    val audioAssetRef: String? = null,
    val mimeType: String = "audio/mpeg",
  )

  /**
   * Produces TTS audio for the watch. Strategy:
   *  1. If the agent has ElevenLabs voice config + dataPlane.streamTts, fetch
   *     the sidecar's /stream/tts endpoint directly from the phone (which IS
   *     on the tailnet; the watch isn't). Faster and uses the right voice.
   *  2. Otherwise fall back to the `talk.speak` RPC on the gateway.
   *
   * Every path is logged so we can see which one actually produced audio.
   */
  private suspend fun wearRelayTalkSpeak(text: String, agentId: String): TalkSpeakResult? {
    val agent = gatewayAgents.firstOrNull { it.id == agentId }
    val dp = _wearDataPlane.value
    val token = activeGatewayAuth?.token

    val wantsElevenLabs = agent?.voiceProvider.equals("elevenlabs", ignoreCase = true)
    val canFetchDirect = wantsElevenLabs &&
      !agent?.voiceId.isNullOrBlank() &&
      dp?.streamTts == true &&
      !token.isNullOrEmpty()

    if (canFetchDirect) {
      WearRelayLog.info("chat", "sidecar tts: $agentId voice=${agent!!.voiceId!!.take(8)}")
      val direct = fetchSidecarTts(dp!!.baseUrl, agent.voiceId!!, text, token!!)
      if (direct != null) return direct
      WearRelayLog.warn("chat", "sidecar tts failed, falling back to talk.speak")
    } else {
      val reason = buildString {
        if (wantsElevenLabs != true) append("provider=${agent?.voiceProvider ?: "null"} ")
        if (agent?.voiceId.isNullOrBlank()) append("voiceId=null ")
        if (dp?.streamTts != true) append("streamTts=${dp?.streamTts} ")
        if (token.isNullOrEmpty()) append("noToken ")
      }.trim()
      WearRelayLog.info("chat", "sidecar tts skipped: $reason")
    }

    return talkSpeakRpc(text, agentId, agent)
  }

  private suspend fun talkSpeakRpc(text: String, agentId: String, agent: GatewayAgentSummary?): TalkSpeakResult? {
    return try {
      // Keep params minimal — gateway's Zod schema rejects extra fields.
      // If the gateway needs to pick a voice per-agent, it should do so via
      // the session context or extend the schema explicitly. The sidecar-
      // direct path handles ElevenLabs; this is just the default-voice
      // fallback for agents without voice config.
      val params = kotlinx.serialization.json.buildJsonObject {
        put("text", kotlinx.serialization.json.JsonPrimitive(text))
      }
      val result = operatorSession.requestDetailed("talk.speak", params.toString(), timeoutMs = 30_000)
      if (!result.ok || result.payloadJson == null) {
        WearRelayLog.warn("chat", "talk.speak: ${result.error?.message?.take(40) ?: "no payload"}")
        return null
      }
      val payload = json.parseToJsonElement(result.payloadJson!!).asObjectOrNull() ?: return null
      val audioUrl = (payload["audioUrl"].asStringOrNull() ?: payload["streamUrl"].asStringOrNull())
        ?.takeIf { it.isNotBlank() }
      val audioBase64 = payload["audioBase64"].asStringOrNull()?.takeIf { it.isNotBlank() }
      val mimeType = payload["mimeType"].asStringOrNull() ?: "audio/mpeg"
      when {
        audioUrl != null -> WearRelayLog.info("chat", "talk.speak → url")
        audioBase64 != null -> WearRelayLog.info("chat", "talk.speak → base64 ${audioBase64.length / 1000}KB")
        else -> WearRelayLog.warn("chat", "talk.speak → no audio in response")
      }
      if (audioUrl == null && audioBase64 == null) null
      else TalkSpeakResult(audioUrl = audioUrl, audioBase64 = audioBase64, mimeType = mimeType)
    } catch (e: Throwable) {
      WearRelayLog.warn("chat", "talk.speak: ${e.javaClass.simpleName}")
      null
    }
  }

  private suspend fun fetchSidecarTts(
    baseUrl: String,
    voiceId: String,
    text: String,
    token: String,
  ): TalkSpeakResult? {
    return kotlinx.coroutines.withContext(Dispatchers.IO) {
      try {
        val voiceEnc = java.net.URLEncoder.encode(voiceId, Charsets.UTF_8.name())
        val textEnc = java.net.URLEncoder.encode(text, Charsets.UTF_8.name())
        val tokenEnc = java.net.URLEncoder.encode(token, Charsets.UTF_8.name())
        val urlStr = "${baseUrl.trimEnd('/')}/stream/tts?voice=$voiceEnc&text=$textEnc&token=$tokenEnc"
        val conn = java.net.URL(urlStr).openConnection() as java.net.HttpURLConnection
        conn.connectTimeout = 5_000
        conn.readTimeout = 30_000
        conn.requestMethod = "GET"
        val code = conn.responseCode
        if (code != 200) {
          WearRelayLog.warn("chat", "sidecar tts HTTP $code")
          conn.disconnect()
          return@withContext null
        }
        val mime = conn.contentType?.substringBefore(';')?.trim() ?: "audio/mpeg"
        val bytes = conn.inputStream.use { it.readBytes() }
        conn.disconnect()
        if (bytes.isEmpty()) {
          WearRelayLog.warn("chat", "sidecar tts empty body")
          return@withContext null
        }
        WearRelayLog.info("chat", "sidecar tts ${bytes.size / 1000}KB $mime")

        // Small audio inlines via MessageClient (fast, no sync wait).
        // Big audio rides DataClient Asset (no 100 KB cap).
        if (bytes.size < TTS_INLINE_CAP_BYTES) {
          val b64 = android.util.Base64.encodeToString(bytes, android.util.Base64.NO_WRAP)
          TalkSpeakResult(audioBase64 = b64, mimeType = mime)
        } else {
          val assetId = "tts-${java.util.UUID.randomUUID().toString().take(12)}"
          if (putTtsAsset(assetId, bytes, mime)) {
            TalkSpeakResult(audioAssetRef = "wear-asset:tts:$assetId", mimeType = mime)
          } else {
            null
          }
        }
      } catch (e: java.net.UnknownHostException) {
        WearRelayLog.warn("chat", "sidecar tts DNS fail")
        null
      } catch (e: java.net.SocketTimeoutException) {
        WearRelayLog.warn("chat", "sidecar tts timeout")
        null
      } catch (e: Throwable) {
        WearRelayLog.warn("chat", "sidecar tts: ${e.javaClass.simpleName}")
        null
      }
    }
  }

  private suspend fun putTtsAsset(assetId: String, bytes: ByteArray, mime: String): Boolean {
    return try {
      val asset = com.google.android.gms.wearable.Asset.createFromBytes(bytes)
      val request = com.google.android.gms.wearable.PutDataMapRequest
        .create("/openclaw/tts/$assetId").apply {
          dataMap.putAsset("data", asset)
          dataMap.putString("mime", mime)
          dataMap.putLong("ts", System.currentTimeMillis())
        }.asPutDataRequest().setUrgent()
      com.google.android.gms.wearable.Wearable.getDataClient(appContext).putDataItem(request).await()
      Log.d(TAG_WEAR, "tts asset $assetId queued (${bytes.size / 1000}KB)")
      true
    } catch (e: Throwable) {
      WearRelayLog.warn("chat", "tts asset put failed: ${e.javaClass.simpleName}")
      false
    }
  }

  companion object {
    /** Audio under this size rides inline via the reply MessageClient message. */
    private const val TTS_INLINE_CAP_BYTES = 60_000
    /** Logcat tag for watch-relay diagnostic output. Visible via `adb logcat`. */
    private const val TAG_WEAR = "WearRelay"
  }

  private fun handleGatewayEvent(event: String, payloadJson: String?) {
    micCapture.handleGatewayEvent(event, payloadJson)
    talkMode.handleGatewayEvent(event, payloadJson)
    chat.handleGatewayEvent(event, payloadJson)
  }

  private fun parseChatSendRunId(response: String): String? {
    return try {
      val root = json.parseToJsonElement(response).asObjectOrNull() ?: return null
      root["runId"].asStringOrNull()
    } catch (_: Throwable) {
      null
    }
  }

  private suspend fun refreshBrandingFromGateway() {
    if (!_isConnected.value) return
    try {
      val res = operatorSession.request("config.get", "{}")
      val root = json.parseToJsonElement(res).asObjectOrNull()
      val config = root?.get("config").asObjectOrNull()
      val ui = config?.get("ui").asObjectOrNull()
      val raw = ui?.get("seamColor").asStringOrNull()?.trim()
      syncMainSessionKey(gatewayDefaultAgentId)

      val parsed = parseHexColorArgb(raw)
      _seamColorArgb.value = parsed ?: DEFAULT_SEAM_COLOR_ARGB

      // Data plane (sidecar) location — used by the wear relay to rewrite
      // relative avatar paths and to build /stream/tts URLs.
      val dp = config?.get("dataPlane").asObjectOrNull()
      val baseUrl = dp?.get("baseUrl").asStringOrNull()?.trim()
      _wearDataPlane.value = if (dp != null && !baseUrl.isNullOrEmpty()) {
        fun boolField(name: String): Boolean {
          val prim = dp[name] as? JsonPrimitive ?: return false
          return prim.content.toBooleanStrictOrNull() ?: false
        }
        val parsed = WearDataPlane(
          baseUrl = baseUrl.trimEnd('/'),
          publicAssets = boolField("publicAssets"),
          streamTts = boolField("streamTts"),
        )
        WearRelayLog.info(
          "config",
          "dataPlane ${parsed.baseUrl} publicAssets=${parsed.publicAssets} streamTts=${parsed.streamTts}",
        )
        parsed
      } else {
        WearRelayLog.info("config", "dataPlane not configured")
        null
      }

      updateHomeCanvasState()
    } catch (_: Throwable) {
      // ignore
    }
  }

  private suspend fun refreshAgentsFromGateway() {
    if (!operatorConnected) return
    try {
      val res = operatorSession.request("agents.list", "{}")
      val root = json.parseToJsonElement(res).asObjectOrNull() ?: return
      val defaultAgentId = root["defaultId"].asStringOrNull()?.trim().orEmpty()
      val mainKey = normalizeMainKey(root["mainKey"].asStringOrNull())
      val agents =
        (root["agents"] as? JsonArray)?.mapNotNull { item ->
          val obj = item.asObjectOrNull() ?: return@mapNotNull null
          val id = obj["id"].asStringOrNull()?.trim().orEmpty()
          if (id.isEmpty()) return@mapNotNull null
          val name = obj["name"].asStringOrNull()?.trim()
          val emoji = obj["identity"].asObjectOrNull()?.get("emoji").asStringOrNull()?.trim()
          val voice = obj["voice"].asObjectOrNull()
          val voiceProvider = voice?.get("provider").asStringOrNull()?.trim()
          val voiceId = voice?.get("voiceId").asStringOrNull()?.trim()
          GatewayAgentSummary(
            id = id,
            name = name?.takeIf { it.isNotEmpty() },
            emoji = emoji?.takeIf { it.isNotEmpty() },
            voiceProvider = voiceProvider?.takeIf { it.isNotEmpty() },
            voiceId = voiceId?.takeIf { it.isNotEmpty() },
          )
        } ?: emptyList()

      run {
        val withVoice = agents.count { !it.voiceId.isNullOrBlank() }
        WearRelayLog.info("agents", "${agents.size} parsed, $withVoice with voice")
        // Per-agent voice details go to logcat only — useful for debugging,
        // noisy in the on-device panel.
        agents.forEach { a ->
          if (!a.voiceId.isNullOrBlank()) {
            Log.d(TAG_WEAR, "${a.id} voice=${a.voiceProvider}/${a.voiceId!!.take(8)}")
          }
        }
      }
      gatewayDefaultAgentId = defaultAgentId.ifEmpty { null }
      gatewayAgents = agents
      syncMainSessionKey(resolveAgentIdFromMainSessionKey(mainKey) ?: gatewayDefaultAgentId)
      updateHomeCanvasState()
    } catch (_: Throwable) {
      // ignore
    }
  }

  private fun updateHomeCanvasState() {
    val payload =
      try {
        json.encodeToString(makeHomeCanvasPayload())
      } catch (_: Throwable) {
        null
      }
    canvas.updateHomeCanvasState(payload)
  }

  private fun makeHomeCanvasPayload(): HomeCanvasPayload {
    val state = resolveHomeCanvasGatewayState()
    val gatewayName = normalized(_serverName.value)
    val gatewayAddress = normalized(_remoteAddress.value)
    val gatewayLabel = gatewayName ?: gatewayAddress ?: "Gateway"
    val activeAgentId = resolveActiveAgentId()
    val agents = homeCanvasAgents(activeAgentId)

    return when (state) {
      HomeCanvasGatewayState.Connected ->
        HomeCanvasPayload(
          gatewayState = "connected",
          eyebrow = "Connected to $gatewayLabel",
          title = "Your agents are ready",
          subtitle =
            "This phone stays dormant until the gateway needs it, then wakes, syncs, and goes back to sleep.",
          gatewayLabel = gatewayLabel,
          activeAgentName = resolveActiveAgentName(activeAgentId),
          activeAgentBadge = agents.firstOrNull { it.isActive }?.badge ?: "OC",
          activeAgentCaption = "Selected on this phone",
          agentCount = agents.size,
          agents = agents.take(6),
          footer = "The overview refreshes on reconnect and when this screen opens.",
        )
      HomeCanvasGatewayState.Connecting ->
        HomeCanvasPayload(
          gatewayState = "connecting",
          eyebrow = "Reconnecting",
          title = "OpenClaw is syncing back up",
          subtitle =
            "The gateway session is coming back online. Agent shortcuts should settle automatically in a moment.",
          gatewayLabel = gatewayLabel,
          activeAgentName = resolveActiveAgentName(activeAgentId),
          activeAgentBadge = "OC",
          activeAgentCaption = "Gateway session in progress",
          agentCount = agents.size,
          agents = agents.take(4),
          footer = "If the gateway is reachable, reconnect should complete without intervention.",
        )
      HomeCanvasGatewayState.Error, HomeCanvasGatewayState.Offline ->
        HomeCanvasPayload(
          gatewayState = if (state == HomeCanvasGatewayState.Error) "error" else "offline",
          eyebrow = "Welcome to OpenClaw",
          title = "Your phone stays quiet until it is needed",
          subtitle =
            "Pair this device to your gateway to wake it only for real work, keep a live agent overview handy, and avoid battery-draining background loops.",
          gatewayLabel = gatewayLabel,
          activeAgentName = "Main",
          activeAgentBadge = "OC",
          activeAgentCaption = "Connect to load your agents",
          agentCount = agents.size,
          agents = agents.take(4),
          footer = "When connected, the gateway can wake the phone with a silent push instead of holding an always-on session.",
        )
    }
  }

  private fun resolveHomeCanvasGatewayState(): HomeCanvasGatewayState {
    val lower = _statusText.value.trim().lowercase()
    return when {
      _isConnected.value -> HomeCanvasGatewayState.Connected
      lower.contains("connecting") || lower.contains("reconnecting") -> HomeCanvasGatewayState.Connecting
      lower.contains("error") || lower.contains("failed") -> HomeCanvasGatewayState.Error
      else -> HomeCanvasGatewayState.Offline
    }
  }

  private fun resolveActiveAgentId(): String {
    val mainKey = _mainSessionKey.value.trim()
    if (mainKey.startsWith("agent:")) {
      val agentId = mainKey.removePrefix("agent:").substringBefore(':').trim()
      if (agentId.isNotEmpty()) return agentId
    }
    return gatewayDefaultAgentId?.trim().orEmpty()
  }

  private fun resolveActiveAgentName(activeAgentId: String): String {
    if (activeAgentId.isNotEmpty()) {
      gatewayAgents.firstOrNull { it.id == activeAgentId }?.let { agent ->
        return normalized(agent.name) ?: agent.id
      }
      return activeAgentId
    }
    return gatewayAgents.firstOrNull()?.let { normalized(it.name) ?: it.id } ?: "Main"
  }

  private fun homeCanvasAgents(activeAgentId: String): List<HomeCanvasAgentCard> {
    val defaultAgentId = gatewayDefaultAgentId?.trim().orEmpty()
    return gatewayAgents
      .map { agent ->
        val isActive = activeAgentId.isNotEmpty() && agent.id == activeAgentId
        val isDefault = defaultAgentId.isNotEmpty() && agent.id == defaultAgentId
        HomeCanvasAgentCard(
          id = agent.id,
          name = normalized(agent.name) ?: agent.id,
          badge = homeCanvasBadge(agent),
          caption =
            when {
              isActive -> "Active on this phone"
              isDefault -> "Default agent"
              else -> "Ready"
            },
          isActive = isActive,
        )
      }.sortedWith(compareByDescending<HomeCanvasAgentCard> { it.isActive }.thenBy { it.name.lowercase() })
  }

  private fun homeCanvasBadge(agent: GatewayAgentSummary): String {
    val emoji = normalized(agent.emoji)
    if (emoji != null) return emoji
    val initials =
      (normalized(agent.name) ?: agent.id)
        .split(' ', '-', '_')
        .filter { it.isNotBlank() }
        .take(2)
        .mapNotNull { token -> token.firstOrNull()?.uppercaseChar()?.toString() }
        .joinToString("")
    return if (initials.isNotEmpty()) initials else "OC"
  }

  private fun normalized(value: String?): String? {
    val trimmed = value?.trim().orEmpty()
    return trimmed.ifEmpty { null }
  }

  private fun triggerCameraFlash() {
    // Token is used as a pulse trigger; value doesn't matter as long as it changes.
    _cameraFlashToken.value = SystemClock.elapsedRealtimeNanos()
  }

  private fun showCameraHud(message: String, kind: CameraHudKind, autoHideMs: Long? = null) {
    val token = cameraHudSeq.incrementAndGet()
    _cameraHud.value = CameraHudState(token = token, kind = kind, message = message)

    if (autoHideMs != null && autoHideMs > 0) {
      scope.launch {
        delay(autoHideMs)
        if (_cameraHud.value?.token == token) _cameraHud.value = null
      }
    }
  }

}

internal fun resolveOperatorSessionConnectAuth(
  auth: NodeRuntime.GatewayConnectAuth,
  storedOperatorToken: String?,
): NodeRuntime.GatewayConnectAuth? {
  val explicitToken = auth.token?.trim()?.takeIf { it.isNotEmpty() }
  if (explicitToken != null) {
    return NodeRuntime.GatewayConnectAuth(
      token = explicitToken,
      bootstrapToken = null,
      password = null,
    )
  }

  val explicitPassword = auth.password?.trim()?.takeIf { it.isNotEmpty() }
  if (explicitPassword != null) {
    return NodeRuntime.GatewayConnectAuth(
      token = null,
      bootstrapToken = null,
      password = explicitPassword,
    )
  }

  val storedToken = storedOperatorToken?.trim()?.takeIf { it.isNotEmpty() }
  if (storedToken != null) {
    return NodeRuntime.GatewayConnectAuth(
      token = null,
      bootstrapToken = null,
      password = null,
    )
  }

  val explicitBootstrapToken = auth.bootstrapToken?.trim()?.takeIf { it.isNotEmpty() }
  if (explicitBootstrapToken != null) {
    return NodeRuntime.GatewayConnectAuth(
      token = null,
      bootstrapToken = explicitBootstrapToken,
      password = null,
    )
  }

  return null
}

internal fun shouldConnectOperatorSession(
  auth: NodeRuntime.GatewayConnectAuth,
  storedOperatorToken: String?,
): Boolean {
  return resolveOperatorSessionConnectAuth(auth, storedOperatorToken) != null
}

private enum class HomeCanvasGatewayState {
  Connected,
  Connecting,
  Error,
  Offline,
}

private data class GatewayAgentSummary(
  val id: String,
  val name: String?,
  val emoji: String?,
  val voiceProvider: String? = null,
  val voiceId: String? = null,
)

@Serializable
private data class HomeCanvasPayload(
  val gatewayState: String,
  val eyebrow: String,
  val title: String,
  val subtitle: String,
  val gatewayLabel: String,
  val activeAgentName: String,
  val activeAgentBadge: String,
  val activeAgentCaption: String,
  val agentCount: Int,
  val agents: List<HomeCanvasAgentCard>,
  val footer: String,
)

@Serializable
private data class HomeCanvasAgentCard(
  val id: String,
  val name: String,
  val badge: String,
  val caption: String,
  val isActive: Boolean,
)
