package ai.openclaw.app.voice

/**
 * Client-side parser for the `<<<state>>>` / `<<<state-N>>>` emotion markers
 * the model emits in assistant replies. Strips markers from the visible text
 * and surfaces them as structured events so the avatar renderer can drive
 * per-segment animations.
 *
 * Sits in front of the chat-display + TTS pipeline so that:
 *  - The user never sees the raw `<<<happy-1>>>` tokens in the bubble.
 *  - The TTS engine never vocalises them.
 *  - The avatar layer gets (state, count) pairs it can use to decide
 *    playback: N=0 → loop until next marker; N≥1 → play N times and hold on
 *    the last frame until the next marker.
 *
 * This parser is stateless and thread-safe — each call is a pure function on
 * the input string. If we later need streaming (partial chunks that might
 * split a marker across boundaries), we can add a stateful wrapper; for now
 * each `delta`/`final` event arrives with complete tokens so a single-pass
 * parse over the full text is fine.
 */
internal object AvatarMarkerParser {

    /**
     * One marker extracted from the text, in the order it appeared.
     *
     * [count] is `null` when the model omitted the suffix (bare `<<<state>>>`)
     * — clients may treat that as "loop until next marker" (same as N=0) per
     * the vocabulary we teach in the system prompt.
     *
     * [charOffset] is the index in the *cleaned* output string where this
     * marker fired. A renderer that streams text one grapheme at a time can
     * use it to align avatar state changes with text position; renderers
     * that just show the full reply at once can ignore it.
     */
    data class Marker(
        val state: String,
        val count: Int?,
        val charOffset: Int,
    )

    data class ParseResult(
        val cleanedText: String,
        val markers: List<Marker>,
    )

    // Matches `<<<name>>>` or `<<<name-N>>>` where name is `[a-zA-Z0-9_]+`
    // and N is a non-negative integer. Disallow `-` inside the state name to
    // avoid ambiguity with the count separator — `<<<foo-bar-1>>>` is treated
    // as state=`foo-bar` count=`1` because the *last* dash before the closer
    // is the separator. See resolveStateAndCount.
    private val MARKER_RE = Regex("<<<([a-zA-Z0-9_-]+)>>>")

    fun parse(text: String): ParseResult {
        if (text.isEmpty()) return ParseResult(text, emptyList())
        val markers = mutableListOf<Marker>()
        val cleaned = StringBuilder(text.length)
        var lastEnd = 0
        for (match in MARKER_RE.findAll(text)) {
            cleaned.append(text, lastEnd, match.range.first)
            val rawInner = match.groupValues[1]
            val (state, count) = resolveStateAndCount(rawInner)
            markers.add(
                Marker(
                    state = state,
                    count = count,
                    charOffset = cleaned.length,
                ),
            )
            lastEnd = match.range.last + 1
        }
        cleaned.append(text, lastEnd, text.length)
        return ParseResult(cleaned.toString(), markers)
    }

    /**
     * Splits the marker body on the *last* hyphen into state + count. If the
     * suffix isn't a non-negative integer, the whole body is the state name
     * (so weird things like `<<<state-name>>>` stay valid — the last hyphen
     * only triggers the split when what follows is digits).
     */
    private fun resolveStateAndCount(body: String): Pair<String, Int?> {
        val dashIdx = body.lastIndexOf('-')
        if (dashIdx <= 0 || dashIdx == body.length - 1) {
            return body to null
        }
        val countPart = body.substring(dashIdx + 1)
        val count = countPart.toIntOrNull()
        if (count == null || count < 0) {
            return body to null
        }
        val state = body.substring(0, dashIdx)
        if (state.isEmpty()) {
            return body to null
        }
        return state to count
    }
}
