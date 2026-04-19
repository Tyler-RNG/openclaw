package ai.openclaw.app.wear

/**
 * Kotlin port of `src/gateway/avatar-marker-parser.ts`. Lives in the phone
 * relay so the watch-path owns marker handling end to end without touching
 * the gateway's general agent flow.
 *
 * Recognizes `[avatar:<state>]` on its own line — line must contain nothing
 * else (leading/trailing whitespace tolerated). Inline `[avatar:X]` amid
 * other text is passed through as literal text. Markers are stripped from
 * the cleaned output and surfaced separately.
 *
 * The parser is stateful across pushes: a marker split mid-token across two
 * feeds is still recognized when the closing bracket + newline arrive. Plain
 * non-marker content is emitted immediately when possible so the watch
 * doesn't wait longer than it has to.
 */

data class AvatarMarker(val state: String)

data class AvatarParseResult(
    val cleanedText: String,
    val markers: List<AvatarMarker>,
)

private val MARKER_LINE_RE: Regex =
    Regex("^[ \\t]*\\[avatar:([a-zA-Z0-9_-]+)\\][ \\t]*$")

class AvatarMarkerParser {
    private var buffer: String = ""

    fun push(chunk: String): AvatarParseResult {
        if (chunk.isEmpty()) return AvatarParseResult("", emptyList())
        val combined = buffer + chunk
        val lastNl = combined.lastIndexOf('\n')
        if (lastNl < 0) {
            // No complete line yet — only buffer if the tail *could* still
            // become a marker (starts with [ after optional whitespace).
            if (tailCouldBeMarker(combined)) {
                buffer = combined
                return AvatarParseResult("", emptyList())
            }
            buffer = ""
            return AvatarParseResult(combined, emptyList())
        }
        val finalized = combined.substring(0, lastNl)
        val tail = combined.substring(lastNl + 1)
        val outParts = mutableListOf<String>()
        val markers = mutableListOf<AvatarMarker>()
        for (line in finalized.split('\n')) {
            val match = MARKER_LINE_RE.matchEntire(line)
            if (match != null) {
                markers.add(AvatarMarker(match.groupValues[1]))
            } else {
                outParts.add(line)
            }
        }
        val cleanedFinalized =
            if (outParts.isNotEmpty()) outParts.joinToString(separator = "\n") + "\n" else ""

        if (tail.isEmpty()) {
            buffer = ""
            return AvatarParseResult(cleanedFinalized, markers)
        }
        if (tailCouldBeMarker(tail)) {
            buffer = tail
            return AvatarParseResult(cleanedFinalized, markers)
        }
        buffer = ""
        return AvatarParseResult(cleanedFinalized + tail, markers)
    }

    fun flush(): AvatarParseResult {
        if (buffer.isEmpty()) return AvatarParseResult("", emptyList())
        val match = MARKER_LINE_RE.matchEntire(buffer)
        val result = if (match != null) {
            AvatarParseResult("", listOf(AvatarMarker(match.groupValues[1])))
        } else {
            AvatarParseResult(buffer, emptyList())
        }
        buffer = ""
        return result
    }

    fun reset() {
        buffer = ""
    }

    /** Could the given tail still turn into a marker once more text arrives? */
    private fun tailCouldBeMarker(tail: String): Boolean {
        var i = 0
        while (i < tail.length && (tail[i] == ' ' || tail[i] == '\t')) {
            i++
        }
        if (i == tail.length) return true // all whitespace — could still prefix a marker
        return tail[i] == '['
    }
}

/**
 * One-shot parse for non-streaming callers (e.g., each `WearChatPart` carries
 * a complete block text, not incremental tokens). Wraps push + flush.
 */
fun parseAvatarMarkers(text: String): AvatarParseResult {
    val p = AvatarMarkerParser()
    val a = p.push(text)
    val b = p.flush()
    if (b.cleanedText.isEmpty() && b.markers.isEmpty()) return a
    return AvatarParseResult(
        cleanedText = a.cleanedText + b.cleanedText,
        markers = a.markers + b.markers,
    )
}
