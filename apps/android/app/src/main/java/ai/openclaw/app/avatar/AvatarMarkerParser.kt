package ai.openclaw.app.avatar

/**
 * Kotlin port of `src/gateway/avatar-marker-parser.ts`. Recognizes the
 * inline escape `<<<state>>>` anywhere in assistant text — not restricted to
 * its own line. Matching markers are stripped from the visible text and
 * surfaced separately; invalid marker shapes (empty or disallowed state
 * names) are emitted verbatim so nothing is silently lost.
 *
 * The triple-angle-bracket escape is deliberately unusual so the model is
 * unlikely to emit it by accident. Prior syntax was `[avatar:state]` on its
 * own line; the new syntax permits inline emotion changes mid-utterance so
 * TTS and avatar state can switch at each marker boundary.
 *
 * The parser is stateful across pushes: a marker split mid-token across two
 * chunks is still recognized. Non-marker content is emitted immediately when
 * possible so streaming UX isn't delayed.
 *
 * Lives at `ai.openclaw.app.avatar` — both the phone voice path and the wear
 * relay path consume this. Keep in sync with the TS reference.
 */

const val AVATAR_MARKER_OPEN = "<<<"
const val AVATAR_MARKER_CLOSE = ">>>"

/**
 * One parsed `<<<state>>>` / `<<<state-N>>>` marker.
 *
 * [count] semantics forwarded from the wire format:
 *   null  — bare `<<<state>>>`, defaults to "loop until next marker" client-side.
 *   0     — explicit loop (same as bare).
 *   N >= 1 — play animation N times and hold on the last frame.
 */
data class AvatarMarker(val state: String, val count: Int? = null)

data class AvatarParseResult(
    val cleanedText: String,
    val markers: List<AvatarMarker>,
)

/**
 * Text segment produced by [splitByMarkers]. `emotion` is the state name of
 * the marker immediately preceding this segment (with any `-N` count suffix
 * stripped off into [emotionCount]), or `null` for the leading segment
 * (before any marker) and for segments introduced by an invalid marker
 * shape (which is emitted as literal text).
 */
data class TextSegmentWithEmotion(
    val text: String,
    val emotion: String?,
    val emotionCount: Int? = null,
)

private val STATE_NAME_RE = Regex("^[a-zA-Z0-9_-]+$")

private fun isValidStateName(name: String): Boolean =
    name.isNotEmpty() && STATE_NAME_RE.matches(name)

/**
 * Splits a raw marker body into (state, count). Triggers on the *last* dash
 * when the suffix is a non-negative integer — `head_cocked_1` (N=1) becomes
 * `head_cocked` + 1, but `head-cocked` (no digits after dash) stays as
 * `head-cocked` + null. Returns null count when the body is all-state.
 */
internal fun resolveStateAndCount(body: String): Pair<String, Int?> {
    val dashIdx = body.lastIndexOf('-')
    if (dashIdx <= 0 || dashIdx == body.length - 1) return body to null
    val countPart = body.substring(dashIdx + 1)
    val count = countPart.toIntOrNull()
    if (count == null || count < 0) return body to null
    val state = body.substring(0, dashIdx)
    if (state.isEmpty()) return body to null
    return state to count
}

class AvatarMarkerParser {
    private var buffer: String = ""

    fun push(chunk: String): AvatarParseResult {
        if (chunk.isEmpty()) return AvatarParseResult("", emptyList())
        val combined = buffer + chunk
        val (cleaned, markers, remainder) = processSafePrefix(combined)
        buffer = remainder
        return AvatarParseResult(cleaned, markers)
    }

    fun flush(): AvatarParseResult {
        if (buffer.isEmpty()) return AvatarParseResult("", emptyList())
        // End of stream: any still-buffered bytes can no longer become a
        // marker. Emit them as literal text.
        val leftover = buffer
        buffer = ""
        return AvatarParseResult(leftover, emptyList())
    }

    fun reset() {
        buffer = ""
    }
}

/**
 * Convenience: parse a complete (non-streamed) string in one shot.
 */
fun parseAvatarMarkers(text: String): AvatarParseResult {
    val parser = AvatarMarkerParser()
    val a = parser.push(text)
    val b = parser.flush()
    if (b.cleanedText.isEmpty() && b.markers.isEmpty()) return a
    return AvatarParseResult(
        cleanedText = a.cleanedText + b.cleanedText,
        markers = a.markers + b.markers,
    )
}

/**
 * Split [text] into segments delimited by `<<<state>>>` markers. Each
 * segment carries the preceding marker's state as its [TextSegmentWithEmotion.emotion]
 * (null for the leading segment before any marker).
 *
 * Invalid marker shapes — empty state names or disallowed characters — are
 * treated as literal text and merged into the enclosing segment.
 *
 * Empty-text segments are dropped; a reply of pure markers returns an empty
 * list. Callers that need the state of an all-markers reply should read the
 * markers through [parseAvatarMarkers] directly.
 */
fun splitByMarkers(text: String): List<TextSegmentWithEmotion> {
    if (text.isEmpty()) return emptyList()
    val segments = mutableListOf<TextSegmentWithEmotion>()
    val currentText = StringBuilder()
    var currentEmotion: String? = null
    var currentEmotionCount: Int? = null
    var i = 0
    while (i < text.length) {
        val openAt = text.indexOf(AVATAR_MARKER_OPEN, i)
        if (openAt == -1) {
            currentText.append(text, i, text.length)
            break
        }
        // Accumulate literal text up to the opener.
        currentText.append(text, i, openAt)
        val closeAt = text.indexOf(AVATAR_MARKER_CLOSE, openAt + AVATAR_MARKER_OPEN.length)
        if (closeAt == -1) {
            // Unterminated marker — rest of string is literal.
            currentText.append(text, openAt, text.length)
            break
        }
        val rawBody = text.substring(openAt + AVATAR_MARKER_OPEN.length, closeAt)
        if (isValidStateName(rawBody)) {
            val (stateName, stateCount) = resolveStateAndCount(rawBody)
            // Close the current segment and start a new one tagged with the
            // marker's state.
            if (currentText.isNotEmpty()) {
                segments.add(
                    TextSegmentWithEmotion(
                        currentText.toString(),
                        currentEmotion,
                        currentEmotionCount,
                    ),
                )
                currentText.setLength(0)
            }
            currentEmotion = stateName
            currentEmotionCount = stateCount
        } else {
            // Invalid marker shape — emit verbatim as literal text within
            // the current segment.
            currentText.append(text, openAt, closeAt + AVATAR_MARKER_CLOSE.length)
        }
        i = closeAt + AVATAR_MARKER_CLOSE.length
    }
    if (currentText.isNotEmpty()) {
        segments.add(
            TextSegmentWithEmotion(currentText.toString(), currentEmotion, currentEmotionCount),
        )
    }
    return segments
}

private data class ProcessResult(
    val cleanedText: String,
    val markers: List<AvatarMarker>,
    val remainder: String,
)

/**
 * Process as much of [combined] as possible without consuming a potential
 * partial marker at the tail. Returns the clean-output prefix, extracted
 * markers, and the byte suffix that might still become a marker when more
 * input arrives — callers buffer the remainder until the next push.
 */
private fun processSafePrefix(combined: String): ProcessResult {
    val markers = mutableListOf<AvatarMarker>()
    val out = StringBuilder()
    var i = 0
    while (i < combined.length) {
        val openAt = combined.indexOf(AVATAR_MARKER_OPEN, i)
        if (openAt == -1) {
            // No complete `<<<` left. But the tail might be a partial start
            // (`<` or `<<`) that could extend into a marker with more input;
            // buffer those trailing `<` characters so the next chunk can
            // complete them.
            var j = combined.length
            while (j > i && combined[j - 1] == '<') {
                j -= 1
            }
            out.append(combined, i, j)
            return ProcessResult(out.toString(), markers, combined.substring(j))
        }
        out.append(combined, i, openAt)
        val closeAt = combined.indexOf(AVATAR_MARKER_CLOSE, openAt + AVATAR_MARKER_OPEN.length)
        if (closeAt == -1) {
            // Unterminated marker — buffer everything from the opener onward.
            return ProcessResult(out.toString(), markers, combined.substring(openAt))
        }
        val rawState = combined.substring(openAt + AVATAR_MARKER_OPEN.length, closeAt)
        if (isValidStateName(rawState)) {
            val (stateName, stateCount) = resolveStateAndCount(rawState)
            markers.add(AvatarMarker(stateName, stateCount))
        } else {
            // Invalid marker shape — emit verbatim so nothing is silently lost.
            out.append(combined, openAt, closeAt + AVATAR_MARKER_CLOSE.length)
        }
        i = closeAt + AVATAR_MARKER_CLOSE.length
    }
    return ProcessResult(out.toString(), markers, "")
}
