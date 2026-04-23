package ai.openclaw.app.diag

import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import java.text.SimpleDateFormat
import java.util.Date
import java.util.Locale

/**
 * Deep/verbose trace log for the phone's on-device diagnostic panel.
 *
 * Separate from [PhoneDiagLog] and [ai.openclaw.app.wear.WearRelayLog] on
 * purpose: those summarize lifecycle events; this one records every wire
 * message the phone exchanges with the watch at a granularity useful for
 * reconstructing a 30-second mid-reply capture (path, size, millis, short
 * payload summary). Gated by the PHONE DEEP panel toggle — writes are
 * always on, but nobody looks unless the panel is open.
 *
 * Writers: [ai.openclaw.app.wear.WearRelayService] at every DataClient
 * putDataItem and MessageClient sendMessage site, inbound MessageEvent
 * dispatch, and state-signal publish.
 * Readers: [ai.openclaw.app.ui.SettingsSheet] renders entries under
 * the PHONE DEEP toggle; lines are tappable to copy.
 *
 * Process-scoped singleton. Capped at [MAX_ENTRIES] oldest-evicted — sized
 * for a ~30s reply trace without drowning the UI.
 */
object PhoneDeepLog {
    private const val MAX_ENTRIES = 400
    private val timeFmt = SimpleDateFormat("HH:mm:ss.SSS", Locale.US)

    private val _entries = MutableStateFlow<List<PhoneDiagLogEntry>>(emptyList())
    val entries: StateFlow<List<PhoneDiagLogEntry>> = _entries.asStateFlow()

    fun info(tag: String, message: String) = append(PhoneDiagLogKind.Info, tag, message)
    fun incoming(tag: String, message: String) = append(PhoneDiagLogKind.In, tag, message)
    fun outgoing(tag: String, message: String) = append(PhoneDiagLogKind.Out, tag, message)
    fun warn(tag: String, message: String) = append(PhoneDiagLogKind.Warn, tag, message)
    fun error(tag: String, message: String) = append(PhoneDiagLogKind.Error, tag, message)

    fun clear() {
        _entries.value = emptyList()
    }

    private fun append(kind: PhoneDiagLogKind, tag: String, message: String) {
        val entry = PhoneDiagLogEntry(timeFmt.format(Date()), kind, tag, message)
        _entries.value = (_entries.value + entry).takeLast(MAX_ENTRIES)
    }
}
