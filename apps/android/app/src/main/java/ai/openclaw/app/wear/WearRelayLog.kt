package ai.openclaw.app.wear

import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.flow.update
import java.text.SimpleDateFormat
import java.util.Date
import java.util.Locale

/**
 * Severity/direction classes for relay log entries. Drives both the glyph
 * shown in the UI (`←` / `→` / `!`) and the color bucket.
 */
enum class WearRelayLogKind { Info, In, Out, Warn, Error }

/**
 * A single line in the Watch Relay panel. Structured (rather than a raw
 * string) so the UI can color by [kind] and copy-to-clipboard can format
 * consistently.
 */
data class WearRelayLogEntry(
    val timestamp: String,
    val kind: WearRelayLogKind,
    val tag: String,
    val message: String,
)

/**
 * Ring-buffered, structured log for the phone's WATCH RELAY diagnostic panel.
 *
 * Writers: [WearRelayService] (incoming/outgoing wire messages) and
 * [ai.openclaw.app.NodeRuntime] wear-relay helpers (chat lifecycle events).
 * Readers: [ai.openclaw.app.ui.SettingsSheet] renders live entries; users
 * can copy lines to clipboard for reporting.
 *
 * Also exposes [inFlight], a counter bumped by [begin]/[end] around each
 * relay coroutine so the UI can show a pulsing "working" indicator.
 *
 * Process-scoped singleton. Capped at [MAX_ENTRIES] oldest-evicted.
 */
object WearRelayLog {
    private const val MAX_ENTRIES = 50
    private val timeFmt = SimpleDateFormat("HH:mm:ss", Locale.US)

    private val _entries = MutableStateFlow<List<WearRelayLogEntry>>(emptyList())
    val entries: StateFlow<List<WearRelayLogEntry>> = _entries.asStateFlow()

    private val _inFlight = MutableStateFlow(0)
    val inFlight: StateFlow<Int> = _inFlight.asStateFlow()

    fun info(tag: String, message: String) = append(WearRelayLogKind.Info, tag, message)
    fun incoming(tag: String, message: String) = append(WearRelayLogKind.In, tag, message)
    fun outgoing(tag: String, message: String) = append(WearRelayLogKind.Out, tag, message)
    fun warn(tag: String, message: String) = append(WearRelayLogKind.Warn, tag, message)
    fun error(tag: String, message: String) = append(WearRelayLogKind.Error, tag, message)

    fun clear() {
        _entries.value = emptyList()
    }

    fun begin() {
        _inFlight.update { it + 1 }
    }

    fun end() {
        _inFlight.update { (it - 1).coerceAtLeast(0) }
    }

    private fun append(kind: WearRelayLogKind, tag: String, message: String) {
        val entry = WearRelayLogEntry(timeFmt.format(Date()), kind, tag, message)
        _entries.value = (_entries.value + entry).takeLast(MAX_ENTRIES)
    }
}

internal fun shortNode(nodeId: String): String =
    if (nodeId.length <= 6) nodeId else "${nodeId.take(6)}…"
