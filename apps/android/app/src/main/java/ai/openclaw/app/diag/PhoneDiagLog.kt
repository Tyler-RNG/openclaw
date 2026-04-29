package ai.openclaw.app.diag

import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.flow.update
import java.text.SimpleDateFormat
import java.util.Date
import java.util.Locale

/**
 * Severity/direction buckets for phone-side diagnostic log entries.
 * Mirrors `WearRelayLogKind` so the rendering (arrow/color) code can share
 * between the two panels.
 */
enum class PhoneDiagLogKind { Info, In, Out, Warn, Error }

/**
 * Single diagnostic line in the phone's APP DEBUG panel. Structured so the
 * UI can color by [kind] and the copy-all export can format consistently.
 */
data class PhoneDiagLogEntry(
    val timestamp: String,
    val kind: PhoneDiagLogKind,
    val tag: String,
    val message: String,
)

/**
 * Ring-buffered, structured log for the phone's on-device diagnostic panel.
 *
 * Complements `WearRelayLog` (which captures the watch-relay wire layer) by
 * surfacing what the phone's OWN runtime is doing — gateway connection
 * lifecycle, config resolution (data-plane path, base URL, streamTts),
 * active-agent changes, mic state transitions, and the TalkSpeaker path
 * decisions (direct /stream/tts vs talk.speak RPC vs local-TTS fallback).
 *
 * Writers: [ai.openclaw.app.NodeRuntime], [ai.openclaw.app.voice.TalkSpeaker],
 * [ai.openclaw.app.voice.TalkSpeakRpcClient], [ai.openclaw.app.voice.TalkModeManager],
 * [ai.openclaw.app.voice.MicCaptureManager].
 * Readers: [ai.openclaw.app.ui.SettingsSheet] renders entries when the
 * APP DEBUG toggle is on; lines are tappable to copy.
 *
 * Process-scoped singleton. Capped at [MAX_ENTRIES] oldest-evicted. Safe to
 * call from any thread — [MutableStateFlow] updates are atomic.
 */
object PhoneDiagLog {
    private const val MAX_ENTRIES = 80
    private val timeFmt = SimpleDateFormat("HH:mm:ss", Locale.US)

    private val _entries = MutableStateFlow<List<PhoneDiagLogEntry>>(emptyList())
    val entries: StateFlow<List<PhoneDiagLogEntry>> = _entries.asStateFlow()

    private val _inFlight = MutableStateFlow(0)
    val inFlight: StateFlow<Int> = _inFlight.asStateFlow()

    fun info(tag: String, message: String) = append(PhoneDiagLogKind.Info, tag, message)
    fun incoming(tag: String, message: String) = append(PhoneDiagLogKind.In, tag, message)
    fun outgoing(tag: String, message: String) = append(PhoneDiagLogKind.Out, tag, message)
    fun warn(tag: String, message: String) = append(PhoneDiagLogKind.Warn, tag, message)
    fun error(tag: String, message: String) = append(PhoneDiagLogKind.Error, tag, message)

    fun clear() {
        _entries.value = emptyList()
    }

    fun begin() {
        _inFlight.update { it + 1 }
    }

    fun end() {
        _inFlight.update { (it - 1).coerceAtLeast(0) }
    }

    fun asCopyableLine(entry: PhoneDiagLogEntry): String {
        val arrow = when (entry.kind) {
            PhoneDiagLogKind.In -> "←"
            PhoneDiagLogKind.Out -> "→"
            PhoneDiagLogKind.Error -> "!"
            PhoneDiagLogKind.Warn -> "·"
            PhoneDiagLogKind.Info -> "·"
        }
        return "${entry.timestamp} $arrow ${entry.tag} ${entry.message}"
    }

    private fun append(kind: PhoneDiagLogKind, tag: String, message: String) {
        val entry = PhoneDiagLogEntry(timeFmt.format(Date()), kind, tag, message)
        _entries.value = (_entries.value + entry).takeLast(MAX_ENTRIES)
    }
}

/** Extension matching WearRelayLogEntry.asCopyableLine for the copy-all path. */
fun PhoneDiagLogEntry.asCopyableLine(): String = PhoneDiagLog.asCopyableLine(this)
