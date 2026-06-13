package ai.openclaw.app.voice

/**
 * Strip XML-style markup and decode common HTML entities before handing text
 * to a TTS engine. Intended for assistant reply text that may still carry
 * stray `<thinking>` / `<tool_use>` tags or entity-escaped angle brackets —
 * without this, ElevenLabs and Android's system TTS will happily pronounce
 * "less than sign" / "ampersand L T" etc.
 *
 * Only well-formed XML-ish tags are removed (`<name…>`, `</name>`, `<name/>`).
 * Standalone `<` / `>` left as-is so math and comparison prose survives
 * ("x < 5" still reads correctly to the TTS).
 *
 * Keep this path allocation-cheap — it runs once per utterance, both for the
 * phone voice mode and the wear-relay fan-out.
 */

private val XML_TAG_REGEX = Regex("""<\s*/?\s*[A-Za-z][A-Za-z0-9._:-]*(\s[^<>]*?)?/?\s*>""")

private val HTML_ENTITIES = mapOf(
    "&lt;" to "<",
    "&gt;" to ">",
    "&amp;" to "&",
    "&quot;" to "\"",
    "&apos;" to "'",
    "&#39;" to "'",
    "&#x27;" to "'",
    "&nbsp;" to " ",
)

internal fun sanitizeTextForTts(raw: String): String {
    if (raw.isEmpty()) return raw
    var out = XML_TAG_REGEX.replace(raw, "")
    for ((entity, replacement) in HTML_ENTITIES) {
        if (out.contains(entity)) out = out.replace(entity, replacement)
    }
    // Collapse whitespace introduced by tag stripping so the TTS doesn't
    // pause awkwardly where a block tag used to live.
    val collapsed = out.replace(Regex("[ \t]+"), " ").replace(Regex("\\s*\n\\s*\n\\s*"), "\n\n")
    return collapsed.trim()
}
