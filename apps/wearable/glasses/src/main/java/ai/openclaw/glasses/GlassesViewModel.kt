package ai.openclaw.glasses

import ai.openclaw.spritecore.client.glasses.GlassesProtocol
import android.annotation.SuppressLint
import android.app.Application
import android.bluetooth.BluetoothDevice
import android.bluetooth.BluetoothManager
import android.bluetooth.le.ScanCallback
import android.bluetooth.le.ScanResult
import android.bluetooth.le.ScanSettings
import android.content.Context
import android.content.Intent
import android.graphics.Bitmap
import android.graphics.BitmapFactory
import android.graphics.Matrix
import androidx.compose.ui.graphics.ImageBitmap
import androidx.compose.ui.graphics.asImageBitmap
import androidx.lifecycle.AndroidViewModel
import androidx.lifecycle.viewModelScope
import kotlinx.coroutines.Job
import kotlinx.coroutines.delay
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.flow.update
import kotlinx.coroutines.launch
import kotlinx.coroutines.sync.Mutex
import kotlinx.coroutines.sync.withLock
import java.io.File
import java.io.FileOutputStream
import java.nio.ByteBuffer
import java.nio.ByteOrder
import java.text.SimpleDateFormat
import java.util.Date
import java.util.Locale

/**
 * Phone-side coordinator + test harness for the Brilliant Frame integration.
 *
 * Owns [OcGlassesTransport]/[OcGlassesClient] (replacements for the sprite-core
 * SDK versions) so we can:
 *  - use WRITE_TYPE_NO_RESPONSE on the TX characteristic (the SDK forced
 *    WRITE_TYPE_DEFAULT, which Frame's TX char rejects on every write);
 *  - detect drops via [OcGlassesTransport]'s disconnect callback and run a
 *    backoff-based auto-reconnect;
 *  - surface every BLE step into [Telemetry.appLog] so failures are visible.
 *
 * The on-device Lua app comes from [EmbeddedLuaApp] (probe-and-pcall flavor),
 * not the SDK asset — same reason: we need readable failure modes against
 * unknown firmware.
 */
@SuppressLint("MissingPermission")
class GlassesViewModel(app: Application) : AndroidViewModel(app) {
    private val _telemetry = MutableStateFlow(Telemetry())
    val telemetry: StateFlow<Telemetry> = _telemetry.asStateFlow()

    private var transport: OcGlassesTransport? = null
    private var client: OcGlassesClient? = null

    private var scanJob: Job? = null
    private var scanCallback: ScanCallback? = null
    private var reconnectJob: Job? = null
    private var connectJob: Job? = null
    private var autoCaptureJob: Job? = null
    private var animationJob: Job? = null
    private var lastDevice: BluetoothDevice? = null
    private var userInitiatedDisconnect: Boolean = false

    // Serializes bitmap uploads so a chunked BEGIN/CHUNK/END sequence from
    // one caller can't interleave with another (which would corrupt the
    // on-device accumulator). Single-packet uploads also hold it briefly so
    // they ordering is preserved relative to chunked ones.
    private val bitmapMutex = Mutex()

    private val cameraBuffer = ArrayDeque<ByteArray>()
    private val batteryHistory = ArrayDeque<Int>()
    private val audioBuffer = ArrayDeque<ByteArray>()
    private var micStartedAtMs: Long = 0L
    private var micSampleRate: Int = 8000
    private var micBitDepth: Int = 8

    data class Discovered(val device: BluetoothDevice, val name: String?, val rssi: Int)

    sealed interface Phase {
        data object Idle : Phase
        data class Scanning(val devices: List<Discovered>) : Phase
        data class Bonding(val device: String) : Phase
        data class Connecting(val device: String, val attempt: Int) : Phase
        data class Negotiating(val device: String) : Phase
        data class Discovering(val device: String) : Phase
        data class Installing(val device: String) : Phase
        data class Ready(
            val name: String?,
            val address: String,
            val maxAppPayload: Int,
            val mtu: Int,
        ) : Phase
        data class BackoffWait(
            val device: String,
            val attempt: Int,
            val nextInMs: Long,
            val reason: String,
        ) : Phase
        data class Error(val message: String) : Phase
    }

    data class Telemetry(
        val phase: Phase = Phase.Idle,
        val appLog: List<LogLine> = emptyList(),
        val recentLua: List<String> = emptyList(),
        val recentPackets: List<PacketSummary> = emptyList(),
        val tapCount: Int = 0,
        val lastHeading: Heading? = null,
        val batteryPct: Int? = null,
        val cameraState: CameraState = CameraState.Idle,
        val cameraImage: ImageBitmap? = null,
        val cameraImagePath: String? = null,
        val cameraBytesReceived: Int = 0,
        val autoCaptureEnabled: Boolean = false,
        val bootStatus: BootStatus? = null,
        val displayState: DisplayState = DisplayState.Cleared,
        val micState: MicState = MicState.Idle,
    )

    sealed interface DisplayState {
        data object Cleared : DisplayState
        data class Showing(val text: String, val color: Int) : DisplayState
    }

    sealed interface MicState {
        data object Idle : MicState
        data class Recording(
            val sampleRate: Int,
            val bitDepth: Int,
            val bytes: Int,
            val durationMs: Long,
        ) : MicState
        data object Stopping : MicState
        data class Saved(
            val sampleRate: Int,
            val bitDepth: Int,
            val bytes: Int,
            val path: String,
        ) : MicState
        data class Failed(val message: String) : MicState
    }

    data class BootStatus(
        val tsMs: Long,
        val imuPresent: Boolean,
        val cameraPresent: Boolean,
        val micPresent: Boolean,
    )

    data class LogLine(val ts: String, val level: Level, val message: String) {
        enum class Level { INFO, WARN, ERROR }
    }

    data class Heading(val roll: Float, val pitch: Float, val heading: Float)

    data class PacketSummary(val channel: String, val size: Int, val hex: String)

    sealed interface CameraState {
        data object Idle : CameraState
        data object Requesting : CameraState
        data class Streaming(val bytes: Int) : CameraState
        data class Ready(val bytes: Int) : CameraState
        data class Failed(val message: String) : CameraState
    }

    // ─── Scan ──────────────────────────────────────────────────────────────

    fun startScan() {
        val scanner = bluetoothManager()?.adapter?.bluetoothLeScanner
            ?: run {
                setPhase(Phase.Error("Bluetooth off or unavailable"))
                return
            }
        stopScanInternal()
        setPhase(Phase.Scanning(emptyList()))
        appLog("scan start")

        val cb = object : ScanCallback() {
            override fun onScanResult(callbackType: Int, result: ScanResult) {
                val name = runCatching { result.device.name }.getOrNull()
                    ?: result.scanRecord?.deviceName
                if (name == null || !name.startsWith("Frame", ignoreCase = true)) return
                _telemetry.update { state ->
                    val current = state.phase as? Phase.Scanning ?: return@update state
                    val list = current.devices
                    if (list.any { it.device.address == result.device.address }) state
                    else state.copy(
                        phase = Phase.Scanning(list + Discovered(result.device, name, result.rssi)),
                    )
                }
            }

            override fun onScanFailed(errorCode: Int) {
                appLogError("scan failed code=$errorCode")
                setPhase(Phase.Error("Scan failed (code $errorCode)"))
            }
        }
        scanCallback = cb
        scanner.startScan(
            /* filters = */ null,
            ScanSettings.Builder().setScanMode(ScanSettings.SCAN_MODE_LOW_LATENCY).build(),
            cb,
        )
        scanJob = viewModelScope.launch {
            delay(SCAN_DURATION_MS)
            stopScanInternal()
        }
    }

    fun stopScan() = stopScanInternal()

    private fun stopScanInternal() {
        scanJob?.cancel(); scanJob = null
        val cb = scanCallback ?: return
        scanCallback = null
        runCatching { bluetoothManager()?.adapter?.bluetoothLeScanner?.stopScan(cb) }
    }

    // ─── Connect / reconnect state machine ─────────────────────────────────

    fun connectTo(device: BluetoothDevice) {
        userInitiatedDisconnect = false
        lastDevice = device
        stopScanInternal()
        cancelPendingConnect()
        connectJob = viewModelScope.launch { runConnect(device, attempt = 1) }
    }

    fun reconnect() {
        val d = lastDevice ?: return startScan()
        connectTo(d)
    }

    fun disconnect() {
        userInitiatedDisconnect = true
        appLog("user disconnect")
        cancelPendingConnect()
        teardown()
        stopForegroundService()
        setPhase(Phase.Idle)
    }

    fun cancelReconnect() {
        appLog("cancel reconnect")
        userInitiatedDisconnect = true
        cancelPendingConnect()
        teardown()
        stopForegroundService()
        setPhase(Phase.Idle)
    }

    private fun cancelPendingConnect() {
        connectJob?.cancel(); connectJob = null
        reconnectJob?.cancel(); reconnectJob = null
    }

    private suspend fun runConnect(device: BluetoothDevice, attempt: Int) {
        val addr = device.address
        try {
            setPhase(Phase.Connecting(addr, attempt))
            appLog("connect attempt $attempt → $addr (bond=${bondName(device.bondState)})")

            // Tear down any prior transport.
            teardown()

            val t = OcGlassesTransport(
                context = getApplication(),
                device = device,
                log = ::appLog,
                disconnectHandler = ::onTransportDisconnect,
            )
            transport = t

            t.connect()
            setPhase(Phase.Negotiating(addr))
            val mtu = t.negotiateMtu()
            setPhase(Phase.Discovering(addr))

            val c = OcGlassesClient(t, viewModelScope, ::appLog)
            client = c
            c.start()

            // Wire flows before installing — boot probe lines print during install.
            viewModelScope.launch { c.data.collect(::onPacket) }
            viewModelScope.launch { c.luaResponses.collect { onLuaLine(it) } }

            setPhase(Phase.Installing(addr))
            appLog("installing embedded Lua app")
            EmbeddedLuaApp.install(
                client = c,
                delay = { ms -> kotlinx.coroutines.delay(ms) },
                log = ::appLog,
            )
            appLog("install OK; awaiting boot status from script")

            startForegroundService()

            val name = runCatching { device.name }.getOrNull()
            setPhase(Phase.Ready(name, addr, c.maxAppPayload, mtu))
            appLog("READY name=$name mtu=$mtu maxPayload=${c.maxAppPayload}")
        } catch (e: Throwable) {
            appLogError("connect attempt $attempt failed: ${e.message}")
            teardown()
            if (userInitiatedDisconnect) {
                setPhase(Phase.Idle)
                return
            }
            // Don't auto-reconnect on connect/install errors — they tend to be
            // configuration problems (write rejected, MTU stuck, etc.) that
            // looping won't fix and just floods the logs. Auto-reconnect only
            // fires on a real GATT drop, via [onTransportDisconnect].
            setPhase(Phase.Error(e.message ?: "connect failed"))
            stopForegroundService()
        }
    }

    private fun onTransportDisconnect(status: Int) {
        if (userInitiatedDisconnect) return
        val device = lastDevice ?: return
        appLogError("link dropped status=$status — scheduling reconnect")
        teardown()
        scheduleReconnect(device, attempt = 1, reason = "status $status")
    }

    private fun scheduleReconnect(device: BluetoothDevice, attempt: Int, reason: String) {
        if (attempt >= RECONNECT_MAX_ATTEMPTS) {
            appLogError("giving up after $attempt attempts ($reason)")
            setPhase(Phase.Error("Reconnect gave up after $attempt attempts: $reason"))
            stopForegroundService()
            return
        }
        val backoff = RECONNECT_BACKOFFS[attempt.coerceAtMost(RECONNECT_BACKOFFS.size) - 1]
        setPhase(
            Phase.BackoffWait(
                device = device.address,
                attempt = attempt,
                nextInMs = backoff,
                reason = reason,
            ),
        )
        appLog("reconnect in ${backoff}ms (attempt ${attempt + 1})")
        reconnectJob = viewModelScope.launch {
            delay(backoff)
            if (userInitiatedDisconnect) return@launch
            runConnect(device, attempt + 1)
        }
    }

    private fun teardown() {
        autoCaptureJob?.cancel(); autoCaptureJob = null
        animationJob?.cancel(); animationJob = null
        runCatching { client?.stop() }
        runCatching { transport?.close() }
        client = null
        transport = null
        cameraBuffer.clear()
        audioBuffer.clear()
        _telemetry.update {
            it.copy(
                cameraState = CameraState.Idle,
                autoCaptureEnabled = false,
                micState = MicState.Idle,
                displayState = DisplayState.Cleared,
            )
        }
    }

    private fun startForegroundService() {
        runCatching {
            val ctx = getApplication<Application>()
            val intent = Intent(ctx, GlassesForegroundService::class.java)
            ctx.startForegroundService(intent)
        }.onFailure { appLogError("fg service start: ${it.message}") }
    }

    private fun stopForegroundService() {
        runCatching {
            val ctx = getApplication<Application>()
            ctx.stopService(Intent(ctx, GlassesForegroundService::class.java))
        }
    }

    // ─── Inbound dispatch ─────────────────────────────────────────────────

    private fun onPacket(p: OcGlassesClient.DataPacket) {
        val name = channelName(p.channel)
        val hex = hexPreview(p.bytes, MAX_HEX_PREVIEW_BYTES)
        _telemetry.update {
            val next = (it.recentPackets + PacketSummary(name, p.bytes.size, hex))
                .takeLast(RECENT_PACKET_LIMIT)
            it.copy(recentPackets = next)
        }
        when (p.channel) {
            GlassesProtocol.Channel.IMU_TAP -> _telemetry.update { it.copy(tapCount = it.tapCount + 1) }
            GlassesProtocol.Channel.IMU_HEADING -> onHeading(p.bytes)
            GlassesProtocol.Channel.BATTERY ->
                if (p.bytes.isNotEmpty()) {
                    val raw = (p.bytes[0].toInt() and 0xFF).coerceIn(0, 100)
                    batteryHistory.addLast(raw)
                    while (batteryHistory.size > BATTERY_SMOOTH_WINDOW) batteryHistory.removeFirst()
                    val smoothed = batteryHistory.sum() / batteryHistory.size
                    _telemetry.update { it.copy(batteryPct = smoothed) }
                }
            EmbeddedLuaApp.CH_CAM_CHUNK -> onCameraChunk(p.bytes)
            EmbeddedLuaApp.CH_CAM_DONE -> onCameraDone()
            EmbeddedLuaApp.CH_AUDIO_CHUNK -> onAudioChunk(p.bytes)
            EmbeddedLuaApp.CH_AUDIO_DONE -> onAudioDone()
            EmbeddedLuaApp.CH_STATUS -> onStatus(p.bytes)
        }
    }

    private fun onHeading(bytes: ByteArray) {
        if (bytes.size < 12) return
        val buf = ByteBuffer.wrap(bytes).order(ByteOrder.LITTLE_ENDIAN)
        _telemetry.update {
            it.copy(lastHeading = Heading(buf.float, buf.float, buf.float))
        }
    }

    private fun onLuaLine(line: String) {
        val trimmed = line.trim()
        if (trimmed.isEmpty()) return
        _telemetry.update {
            val next = (it.recentLua + trimmed).takeLast(RECENT_LUA_LIMIT)
            it.copy(recentLua = next)
        }
    }

    /**
     * STATUS channel (0x7D) dispatch. Layout: [kind][payload...].
     *  - 0x03 pong: [nonce:1]
     *  - 0x04 boot: [imu:1][cam:1]
     */
    private fun onStatus(bytes: ByteArray) {
        if (bytes.isEmpty()) return
        val kind = bytes[0].toInt() and 0xFF
        val payload = bytes.copyOfRange(1, bytes.size)
        val now = System.currentTimeMillis()
        when (kind) {
            0x03 -> appLog("pong nonce=${payload.firstOrNull()?.toInt()?.and(0xFF) ?: -1}")
            0x04 -> {
                if (payload.size < 2) return
                val boot = BootStatus(
                    tsMs = now,
                    imuPresent = payload[0].toInt() != 0,
                    cameraPresent = payload[1].toInt() != 0,
                    micPresent = payload.size >= 3 && payload[2].toInt() != 0,
                )
                appLog("boot imu=${boot.imuPresent} cam=${boot.cameraPresent} mic=${boot.micPresent}")
                _telemetry.update { it.copy(bootStatus = boot) }
            }
            0x05 -> {
                val msg = String(payload, Charsets.UTF_8)
                appLogError("mic error: $msg")
                _telemetry.update { it.copy(micState = MicState.Failed(msg)) }
            }
            else -> appLog("status unknown kind=0x${"%02X".format(kind)}")
        }
    }

    fun probeApis() {
        appLog("probing Frame APIs")
        evalLuaLogged(EmbeddedLuaApp.PROBE_APIS_LUA, label = "probe")
    }

    /** Round-trip BLE test. If `TEST NNN` appears in Frame log, BLE is fine. */
    fun testSingleEval() {
        appLog("test single eval")
        evalLuaLogged("print('TEST '..tostring(math.random(1000)))", label = "testEval")
    }

    /** Ping the Frame on the data channel; pong arrives on STATUS. */
    fun pingFrame() {
        val c = client ?: run { appLogError("ping: not connected"); return }
        appLog("ping Frame (verifies data channel round-trip)")
        viewModelScope.launch {
            runCatching {
                c.sendData(EmbeddedLuaApp.CMD_PING, byteArrayOf(0x42))
            }.onFailure { appLogError("ping: ${it.message}") }
        }
    }

    /** Captures a single frame and prints camera state + any errors to Lua log. */
    fun testCamera() {
        val c = client ?: run { appLogError("testCamera: not connected"); return }
        appLog("test camera capture")
        viewModelScope.launch {
            runCatching {
                c.evalRaw(
                    "if frame.camera then for k,v in pairs(frame.camera) do " +
                        "print('cam.'..k..'='..type(v)) end end",
                )
                kotlinx.coroutines.delay(200)
                c.evalRaw("print('cam.capture try');local ok,err=pcall(function() frame.camera.capture() end);print('cam.capture ok='..tostring(ok)..' err='..tostring(err))")
            }.onFailure { appLogError("testCamera: ${it.message}") }
        }
    }

    /** Concatenates app log + Frame print output into a single string for clipboard. */
    fun fullLogSnapshot(): String {
        val t = _telemetry.value
        val app = t.appLog.joinToString("\n") { "${it.ts}  [${it.level}]  ${it.message}" }
        val lua = t.recentLua.joinToString("\n")
        return buildString {
            append("=== App log (${t.appLog.size}) ===\n")
            append(app)
            append("\n\n=== Frame print output (${t.recentLua.size}) ===\n")
            append(lua)
            append("\n\n=== State ===\n")
            append("phase=${t.phase}\n")
            append("battery=${t.batteryPct}\n")
            append("tapCount=${t.tapCount}\n")
            append("lastHeading=${t.lastHeading}\n")
            append("cameraState=${t.cameraState}  bytesRx=${t.cameraBytesReceived}\n")
        }
    }

    // ─── Camera ───────────────────────────────────────────────────────────

    fun toggleAutoCapture() {
        val newState = !_telemetry.value.autoCaptureEnabled
        _telemetry.update { it.copy(autoCaptureEnabled = newState) }
        autoCaptureJob?.cancel()
        if (newState) {
            appLog("auto-capture ON (every 2.5s)")
            autoCaptureJob = viewModelScope.launch {
                while (true) {
                    captureImage()
                    delay(2_500)
                }
            }
        } else {
            appLog("auto-capture OFF")
            autoCaptureJob = null
        }
    }

    fun captureImage() {
        val c = client ?: run { appLogError("camera: not connected"); return }
        cameraBuffer.clear()
        _telemetry.update {
            it.copy(
                cameraState = CameraState.Requesting,
                cameraImage = null,
                cameraImagePath = null,
                cameraBytesReceived = 0,
            )
        }
        appLog("camera capture request")
        viewModelScope.launch {
            runCatching { c.sendData(EmbeddedLuaApp.CH_CAM_REQUEST, ByteArray(0)) }
                .onFailure {
                    val msg = it.message ?: "send failed"
                    appLogError("camera request: $msg")
                    _telemetry.update { s -> s.copy(cameraState = CameraState.Failed(msg)) }
                }
        }
    }

    private fun onCameraChunk(bytes: ByteArray) {
        cameraBuffer.addLast(bytes)
        _telemetry.update {
            val total = it.cameraBytesReceived + bytes.size
            it.copy(cameraState = CameraState.Streaming(total), cameraBytesReceived = total)
        }
    }

    private fun onCameraDone() {
        val full = ByteArray(cameraBuffer.sumOf { it.size })
        var off = 0
        for (chunk in cameraBuffer) { chunk.copyInto(full, off); off += chunk.size }
        cameraBuffer.clear()
        if (full.isEmpty()) {
            appLogError("camera: 0 bytes — check Lua log")
            _telemetry.update { it.copy(cameraState = CameraState.Failed("0 bytes received")) }
            return
        }
        val out = File(getApplication<Application>().cacheDir, "openclaw-${System.currentTimeMillis()}.jpg")
        runCatching { FileOutputStream(out).use { it.write(full) } }
        // Frame camera ships JPEGs rotated 90° clockwise relative to how you
        // naturally read the scene; counter-rotate so the preview is upright.
        val raw = runCatching { BitmapFactory.decodeByteArray(full, 0, full.size) }.getOrNull()
        val bitmap = raw?.let { rotateBitmap(it, -90f) }
        if (bitmap == null) {
            appLogError("camera: decode failed (${full.size}B saved to ${out.absolutePath})")
            _telemetry.update {
                it.copy(
                    cameraState = CameraState.Failed("decode failed; raw saved"),
                    cameraImagePath = out.absolutePath,
                )
            }
            return
        }
        appLog("camera OK (${full.size}B → ${bitmap.width}×${bitmap.height})")
        _telemetry.update {
            it.copy(
                cameraState = CameraState.Ready(full.size),
                cameraImage = bitmap.asImageBitmap(),
                cameraImagePath = out.absolutePath,
            )
        }
    }

    // ─── Display text ─────────────────────────────────────────────────────

    /**
     * Shows [text] on the Frame display at ([x],[y]) in palette color [colorIndex].
     * [spacing] is Frame's character-spacing arg (default 4 matches firmware default;
     * 6–8 reads as widened/title-screen kerning, lifted from FrameDinoGame's titles).
     * Multi-line: newlines are split on-device and each line offset by 32px.
     */
    fun showText(
        text: String,
        colorIndex: Int = COLOR_WHITE,
        x: Int = 50,
        y: Int = 100,
        spacing: Int = 4,
    ) {
        val c = client ?: run { appLogError("text: not connected"); return }
        val payload = buildTextPayload(text, colorIndex, x, y, spacing)
        val maxBytes = c.maxAppPayload
        if (payload.size > maxBytes) {
            val msg = "text too long: ${payload.size}B > ${maxBytes}B"
            appLogError(msg)
            return
        }
        appLog("display text len=${text.length} color=${paletteName(colorIndex)} sp=$spacing @ ($x,$y)")
        viewModelScope.launch {
            runCatching { c.sendData(EmbeddedLuaApp.CMD_TEXT_SHOW, payload) }
                .onSuccess {
                    _telemetry.update {
                        it.copy(displayState = DisplayState.Showing(text, colorIndex))
                    }
                }
                .onFailure { appLogError("text send: ${it.message}") }
        }
    }

    fun clearDisplay() {
        val c = client ?: run { appLogError("clear: not connected"); return }
        appLog("clear display")
        viewModelScope.launch {
            runCatching { c.sendData(EmbeddedLuaApp.CMD_TEXT_CLEAR, ByteArray(0)) }
                .onSuccess { _telemetry.update { it.copy(displayState = DisplayState.Cleared) } }
                .onFailure { appLogError("clear: ${it.message}") }
        }
    }

    /** Renders all 16 palette colors as labelled text — visual check that colors work. */
    fun showPaletteDemo() {
        val c = client ?: run { appLogError("palette: not connected"); return }
        appLog("show 16-color palette demo")
        viewModelScope.launch {
            runCatching { c.sendData(EmbeddedLuaApp.CMD_PALETTE_DEMO, ByteArray(0)) }
                .onFailure { appLogError("palette: ${it.message}") }
        }
    }

    /** Draws TL/TR/BL/BR + centre labels to map out the visible 640×400 area. */
    fun showCornerDemo() {
        val c = client ?: run { appLogError("corners: not connected"); return }
        appLog("show corner-test (640×400)")
        viewModelScope.launch {
            runCatching { c.sendData(EmbeddedLuaApp.CMD_CORNER_DEMO, ByteArray(0)) }
                .onFailure { appLogError("corners: ${it.message}") }
        }
    }

    /** Draws the same text in 9 different color-arg variants for visual comparison. */
    fun showColorTest() {
        val c = client ?: run { appLogError("color test: not connected"); return }
        appLog("show color-test (string vs int vs default)")
        viewModelScope.launch {
            runCatching { c.sendData(EmbeddedLuaApp.CMD_COLOR_TEST, ByteArray(0)) }
                .onFailure { appLogError("color test: ${it.message}") }
        }
    }

    private fun buildTextPayload(
        text: String,
        colorIndex: Int,
        x: Int,
        y: Int,
        spacing: Int,
    ): ByteArray {
        val ci = colorIndex.coerceIn(0, 15)
        val xb = x.coerceIn(0, 0xFFFF)
        val yb = y.coerceIn(0, 0xFFFF)
        val sp = spacing.coerceIn(0, 0xFF)
        val textBytes = text.toByteArray(Charsets.UTF_8)
        val buf = ByteArray(6 + textBytes.size)
        buf[0] = ci.toByte()
        buf[1] = (xb and 0xFF).toByte()
        buf[2] = ((xb ushr 8) and 0xFF).toByte()
        buf[3] = (yb and 0xFF).toByte()
        buf[4] = ((yb ushr 8) and 0xFF).toByte()
        buf[5] = sp.toByte()
        textBytes.copyInto(buf, 6)
        return buf
    }

    // ─── Bitmap ───────────────────────────────────────────────────────────

    /**
     * Sends [bitmap] to the Frame display at ([x], [y]) as a 1bpp bitmap with
     * [paletteOffset] as the foreground colour index. Width must be a multiple
     * of 8 (see [BitmapEncoder]). Auto-routes:
     *   - single-packet CMD_BITMAP_SHOW when ≤ maxAppPayload (≈30×60 1bpp)
     *   - chunked BEGIN/CHUNK/END for anything larger, up to full-screen
     */
    fun showBitmap(bitmap: Bitmap, x: Int, y: Int, paletteOffset: Int = COLOR_WHITE) {
        val c = client ?: run { appLogError("bitmap: not connected"); return }
        viewModelScope.launch {
            runCatching { sendBitmapInternal(c, bitmap, x, y, paletteOffset) }
                .onFailure { appLogError("bitmap send: ${it.message}") }
        }
    }

    /**
     * Suspending core for bitmap uploads — used directly by animation loops so
     * frame N completes (all chunks delivered) before frame N+1 starts. Holds
     * [bitmapMutex] so concurrent callers serialize.
     */
    private suspend fun sendBitmapInternal(
        c: OcGlassesClient,
        bitmap: Bitmap,
        x: Int,
        y: Int,
        paletteOffset: Int,
    ) {
        val data = BitmapEncoder.encode1bpp(bitmap)
        val w = bitmap.width
        val h = bitmap.height
        bitmapMutex.withLock {
            val singlePayload = buildBitmapPayload(x, y, w, COLOR_FORMAT_1BPP, paletteOffset, data)
            if (singlePayload.size <= c.maxAppPayload) {
                c.sendData(EmbeddedLuaApp.CMD_BITMAP_SHOW, singlePayload)
                return@withLock
            }
            val beginHeader = buildBitmapPayload(x, y, w, COLOR_FORMAT_1BPP, paletteOffset, ByteArray(0))
            val chunkLimit = c.maxAppPayload
            val nChunks = (data.size + chunkLimit - 1) / chunkLimit
            appLog("bitmap ${w}×${h} chunked ${data.size}B / $nChunks chunks po=${paletteName(paletteOffset)}")
            c.sendData(EmbeddedLuaApp.CMD_BITMAP_BEGIN, beginHeader)
            var off = 0
            while (off < data.size) {
                val end = minOf(off + chunkLimit, data.size)
                c.sendData(EmbeddedLuaApp.CMD_BITMAP_CHUNK, data.copyOfRange(off, end))
                off = end
            }
            c.sendData(EmbeddedLuaApp.CMD_BITMAP_END, ByteArray(0))
        }
    }

    /** Fires the on-device letterbox demo — two 20-px-wide WHITE columns at x=1 and x=620. */
    fun showLetterboxDemo() {
        val c = client ?: run { appLogError("letterbox: not connected"); return }
        appLog("letterbox demo (640×400 viewport mask)")
        viewModelScope.launch {
            runCatching { c.sendData(EmbeddedLuaApp.CMD_LETTERBOX_DEMO, ByteArray(0)) }
                .onFailure { appLogError("letterbox: ${it.message}") }
        }
    }

    // ─── Sprite demos ─────────────────────────────────────────────────────

    fun showSmiley() {
        stopAnimation()
        appLog("sprite: smiley 64×64 @ center")
        showBitmap(Sprites.smiley(), x = 288, y = 168, paletteOffset = COLOR_YELLOW)
    }

    fun showHeart() {
        stopAnimation()
        appLog("sprite: heart 64×64 @ center")
        showBitmap(Sprites.heart(), x = 288, y = 168, paletteOffset = COLOR_RED)
    }

    fun showCheckerboardFullScreen() {
        stopAnimation()
        appLog("sprite: 640×400 checkerboard (chunked ~32 KB upload, ~2 s)")
        showBitmap(Sprites.checkerboardFullScreen(), x = 0, y = 0, paletteOffset = COLOR_WHITE)
    }

    fun showBatteryIconDemo() {
        stopAnimation()
        appLog("sprite: battery icon 48×24 @ 70% fill")
        showBitmap(Sprites.batteryAtFill(0.7f), x = 296, y = 188, paletteOffset = COLOR_GREEN)
    }

    // ─── Animations ───────────────────────────────────────────────────────

    fun stopAnimation() {
        if (animationJob == null) return
        animationJob?.cancel()
        animationJob = null
        appLog("animation stopped")
    }

    /** 8-frame loading spinner @ 8 fps, looping. */
    fun playSpinnerAnimation() {
        val c = client ?: run { appLogError("spinner: not connected"); return }
        stopAnimation()
        appLog("animation: spinner (8 frames @ 8 fps)")
        animationJob = viewModelScope.launch {
            var i = 0
            while (true) {
                val frame = Sprites.spinnerFrame(i % 8)
                runCatching { sendBitmapInternal(c, frame, x = 304, y = 184, paletteOffset = COLOR_WHITE) }
                    .onFailure { appLogError("spinner: ${it.message}"); return@launch }
                delay(125)
                i++
            }
        }
    }

    /** 24×24 ball travels left↔right across the 640px display. ~12 fps. */
    fun playBouncingBall() {
        val c = client ?: run { appLogError("ball: not connected"); return }
        stopAnimation()
        appLog("animation: bouncing ball (24×24 @ ~12 fps)")
        animationJob = viewModelScope.launch {
            val ball = Sprites.ball()
            var x = 0
            var dir = 1
            while (true) {
                runCatching { sendBitmapInternal(c, ball, x = x, y = 188, paletteOffset = COLOR_WHITE) }
                    .onFailure { appLogError("ball: ${it.message}"); return@launch }
                x += dir * 24
                if (x >= 616) { x = 616; dir = -1 }
                if (x <= 0) { x = 0; dir = 1 }
                delay(80)
            }
        }
    }

    /** Battery icon cycles fill 0→100% then loops. ~5 fps. */
    fun playBatteryFillAnimation() {
        val c = client ?: run { appLogError("battery anim: not connected"); return }
        stopAnimation()
        appLog("animation: battery fill 0→100% (9 frames, looping)")
        animationJob = viewModelScope.launch {
            while (true) {
                for (i in 0..8) {
                    val pct = i / 8f
                    val frame = Sprites.batteryAtFill(pct)
                    runCatching { sendBitmapInternal(c, frame, x = 296, y = 188, paletteOffset = COLOR_GREEN) }
                        .onFailure { appLogError("battery anim: ${it.message}"); return@launch }
                    delay(200)
                }
            }
        }
    }

    // ─── Microphone ───────────────────────────────────────────────────────

    /**
     * Starts streaming PCM audio from the Frame mic. [sampleRate] in Hz
     * (8000 or 16000), [bitDepth] 8 or 16. Frame returns signed PCM; we
     * rebias 8-bit to unsigned at WAV-save time so the file is playable.
     */
    fun startMic(sampleRate: Int = 8000, bitDepth: Int = 8) {
        val c = client ?: run { appLogError("mic: not connected"); return }
        require(sampleRate in setOf(8000, 16000)) { "sampleRate must be 8000 or 16000" }
        require(bitDepth == 8 || bitDepth == 16) { "bitDepth must be 8 or 16" }
        audioBuffer.clear()
        micSampleRate = sampleRate
        micBitDepth = bitDepth
        micStartedAtMs = System.currentTimeMillis()
        _telemetry.update {
            it.copy(
                micState = MicState.Recording(sampleRate, bitDepth, 0, 0L),
            )
        }
        appLog("mic start ${sampleRate}Hz ${bitDepth}bit")
        viewModelScope.launch {
            runCatching {
                c.sendData(
                    EmbeddedLuaApp.CMD_MIC_START,
                    byteArrayOf((sampleRate / 1000).toByte(), bitDepth.toByte()),
                )
            }.onFailure {
                val msg = it.message ?: "send failed"
                appLogError("mic start: $msg")
                _telemetry.update { s -> s.copy(micState = MicState.Failed(msg)) }
            }
        }
    }

    fun stopMic() {
        val c = client ?: run { appLogError("mic: not connected"); return }
        if (_telemetry.value.micState !is MicState.Recording) return
        appLog("mic stop")
        _telemetry.update { it.copy(micState = MicState.Stopping) }
        viewModelScope.launch {
            runCatching { c.sendData(EmbeddedLuaApp.CMD_MIC_STOP, ByteArray(0)) }
                .onFailure {
                    val msg = it.message ?: "send failed"
                    appLogError("mic stop send: $msg")
                    _telemetry.update { s -> s.copy(micState = MicState.Failed(msg)) }
                }
        }
    }

    private fun onAudioChunk(bytes: ByteArray) {
        audioBuffer.addLast(bytes)
        val current = _telemetry.value.micState as? MicState.Recording ?: return
        val totalBytes = current.bytes + bytes.size
        _telemetry.update {
            it.copy(
                micState = current.copy(
                    bytes = totalBytes,
                    durationMs = System.currentTimeMillis() - micStartedAtMs,
                ),
            )
        }
    }

    private fun onAudioDone() {
        val total = audioBuffer.sumOf { it.size }
        val pcm = ByteArray(total)
        var off = 0
        for (chunk in audioBuffer) { chunk.copyInto(pcm, off); off += chunk.size }
        audioBuffer.clear()
        if (total == 0) {
            appLogError("mic: 0 bytes received")
            _telemetry.update { it.copy(micState = MicState.Failed("0 bytes received")) }
            return
        }
        val ts = System.currentTimeMillis()
        val out = File(
            getApplication<Application>().cacheDir,
            "openclaw-audio-$ts.wav",
        )
        val saved = runCatching {
            WavWriter.write(out, pcm, micSampleRate, micBitDepth)
        }.onFailure { appLogError("mic save: ${it.message}") }.isSuccess
        if (!saved) {
            _telemetry.update { it.copy(micState = MicState.Failed("WAV write failed")) }
            return
        }
        appLog("mic OK (${total}B → ${out.name})")
        _telemetry.update {
            it.copy(
                micState = MicState.Saved(
                    sampleRate = micSampleRate,
                    bitDepth = micBitDepth,
                    bytes = total,
                    path = out.absolutePath,
                ),
            )
        }
    }

    // ─── Lua eval ─────────────────────────────────────────────────────────

    fun evalLua(text: String) {
        if (text.isBlank()) return
        evalLuaLogged(text, label = "user")
    }

    fun reinstallLuaApp() {
        val c = client ?: run { appLogError("reinstall: not connected"); return }
        appLog("reinstall begin")
        viewModelScope.launch {
            runCatching {
                EmbeddedLuaApp.install(
                    client = c,
                    delay = { ms -> kotlinx.coroutines.delay(ms) },
                    log = ::appLog,
                )
            }
                .onFailure { appLogError("reinstall: ${it.message}") }
                .onSuccess { appLog("reinstall OK") }
        }
    }

    private fun evalLuaLogged(lua: String, label: String) {
        val c = client ?: run { appLogError("$label: not connected"); return }
        viewModelScope.launch {
            runCatching { c.eval(lua) }
                .onFailure { appLogError("$label eval: ${it.message}") }
        }
    }

    // ─── App log ──────────────────────────────────────────────────────────

    private fun appLog(message: String) = log(LogLine.Level.INFO, message)
    private fun appLogError(message: String) = log(LogLine.Level.ERROR, message)

    private fun log(level: LogLine.Level, message: String) {
        val ts = TIMESTAMP_FMT.format(Date())
        _telemetry.update {
            val next = (it.appLog + LogLine(ts, level, message)).takeLast(APP_LOG_LIMIT)
            it.copy(appLog = next)
        }
    }

    fun bluetoothManager(): BluetoothManager? =
        getApplication<Application>().getSystemService(Context.BLUETOOTH_SERVICE) as? BluetoothManager

    private fun setPhase(phase: Phase) = _telemetry.update { it.copy(phase = phase) }

    override fun onCleared() {
        stopScanInternal()
        cancelPendingConnect()
        teardown()
        stopForegroundService()
        super.onCleared()
    }

    private fun buildBitmapPayload(
        x: Int,
        y: Int,
        width: Int,
        colorFormat: Int,
        paletteOffset: Int,
        data: ByteArray,
    ): ByteArray {
        val xb = x.coerceIn(0, 0xFFFF)
        val yb = y.coerceIn(0, 0xFFFF)
        val wb = width.coerceIn(0, 0xFFFF)
        val cf = colorFormat.coerceIn(0, 0xFF)
        val po = paletteOffset.coerceIn(0, 0xFF)
        val buf = ByteArray(8 + data.size)
        buf[0] = (xb and 0xFF).toByte()
        buf[1] = ((xb ushr 8) and 0xFF).toByte()
        buf[2] = (yb and 0xFF).toByte()
        buf[3] = ((yb ushr 8) and 0xFF).toByte()
        buf[4] = (wb and 0xFF).toByte()
        buf[5] = ((wb ushr 8) and 0xFF).toByte()
        buf[6] = cf.toByte()
        buf[7] = po.toByte()
        data.copyInto(buf, 8)
        return buf
    }

    companion object {
        // Frame's frame.display.bitmap color_format arg = number of palette
        // colours used by the data: 2 → 1bpp, 4 → 2bpp, 16 → 4bpp.
        const val COLOR_FORMAT_1BPP = 2
        const val COLOR_FORMAT_2BPP = 4
        const val COLOR_FORMAT_4BPP = 16

        const val COLOR_VOID = 0
        const val COLOR_WHITE = 1
        const val COLOR_GREY = 2
        const val COLOR_RED = 3
        const val COLOR_PINK = 4
        const val COLOR_DARKBROWN = 5
        const val COLOR_BROWN = 6
        const val COLOR_ORANGE = 7
        const val COLOR_YELLOW = 8
        const val COLOR_DARKGREEN = 9
        const val COLOR_GREEN = 10
        const val COLOR_LIGHTGREEN = 11
        const val COLOR_NIGHTBLUE = 12
        const val COLOR_SEABLUE = 13
        const val COLOR_SKYBLUE = 14
        const val COLOR_CLOUDBLUE = 15

        fun paletteName(index: Int): String =
            EmbeddedLuaApp.PALETTE.getOrElse(index.coerceIn(0, 15)) { "WHITE" }

        const val SCAN_DURATION_MS = 10_000L
        const val RECENT_PACKET_LIMIT = 30
        const val RECENT_LUA_LIMIT = 60
        const val APP_LOG_LIMIT = 200
        const val MAX_HEX_PREVIEW_BYTES = 16
        const val BATTERY_SMOOTH_WINDOW = 5
        const val RECONNECT_MAX_ATTEMPTS = 5
        val RECONNECT_BACKOFFS = longArrayOf(2_000L, 5_000L, 10_000L, 15_000L, 30_000L)

        val TIMESTAMP_FMT = SimpleDateFormat("HH:mm:ss.SSS", Locale.US)

        fun channelName(ch: Byte): String = when (ch) {
            GlassesProtocol.Channel.IMU_TAP -> "TAP"
            GlassesProtocol.Channel.IMU_HEADING -> "HEAD"
            GlassesProtocol.Channel.BATTERY -> "BAT"
            GlassesProtocol.Channel.ACK -> "ACK"
            EmbeddedLuaApp.CMD_CAMERA -> "CAMR"
            EmbeddedLuaApp.CH_CAM_CHUNK -> "CAMC"
            EmbeddedLuaApp.CH_CAM_DONE -> "CAMD"
            EmbeddedLuaApp.CH_AUDIO_CHUNK -> "AUDC"
            EmbeddedLuaApp.CH_AUDIO_DONE -> "AUDD"
            EmbeddedLuaApp.CMD_PING -> "PING"
            EmbeddedLuaApp.CH_STATUS -> "STAT"
            else -> "0x%02X".format(ch.toInt() and 0xFF)
        }

        fun hexPreview(bytes: ByteArray, limit: Int): String {
            if (bytes.isEmpty()) return ""
            val take = minOf(bytes.size, limit)
            val sb = StringBuilder()
            for (i in 0 until take) {
                if (i > 0) sb.append(' ')
                sb.append("%02X".format(bytes[i].toInt() and 0xFF))
            }
            if (bytes.size > take) sb.append("…")
            return sb.toString()
        }

        fun rotateBitmap(src: Bitmap, degrees: Float): Bitmap {
            val m = Matrix().apply { postRotate(degrees) }
            return Bitmap.createBitmap(src, 0, 0, src.width, src.height, m, true)
        }

        fun bondName(s: Int): String = when (s) {
            BluetoothDevice.BOND_NONE -> "none"
            BluetoothDevice.BOND_BONDING -> "bonding"
            BluetoothDevice.BOND_BONDED -> "bonded"
            else -> "bond_$s"
        }
    }
}
