package ai.openclaw.app.wear

import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import java.text.SimpleDateFormat
import java.util.Date
import java.util.Locale

/**
 * Shared log visible in Settings > Watch Relay section.
 */
object WearRelayLog {
    private val timeFmt = SimpleDateFormat("HH:mm:ss", Locale.US)
    private val _entries = MutableStateFlow<List<String>>(emptyList())
    val entries: StateFlow<List<String>> = _entries.asStateFlow()

    fun log(message: String) {
        val time = timeFmt.format(Date())
        val line = "[$time] $message"
        _entries.value = (_entries.value + line).takeLast(50)
    }

    fun incoming(path: String, from: String) {
        log(">> $path from $from")
    }

    fun outgoing(path: String, to: String) {
        log("<< $path to $to")
    }

    fun error(message: String) {
        log("ERROR: $message")
    }
}
