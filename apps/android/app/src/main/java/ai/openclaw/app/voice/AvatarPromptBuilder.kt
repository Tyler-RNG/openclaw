package ai.openclaw.app.voice

import ai.openclaw.app.diag.PhoneDiagLog

/**
 * Assembles the client-side "sprite-core mode" system-prompt prefix that
 * teaches the model how to emit `<<<state-N>>>` emotion markers. Kept on the
 * client so the gateway plugin doesn't have to own prompt engineering — the
 * gateway provides the assets + emotion config via `sprite-core.agents`,
 * we build the prompt from that config here.
 *
 * Injection cadence is the caller's decision: prepend once per session
 * (cheap — costs one prefix in the chat history), or on every message
 * (redundant). [NodeRuntime] tracks primed session keys so the prefix fires
 * only on the first outgoing user message per session.
 *
 * Returns null when we have no emotion descriptions for the agent — no
 * teaching to do, so nothing to inject. The parser is a no-op on the
 * response too.
 */
internal object AvatarPromptBuilder {

    fun build(
        agentName: String?,
        agentDescriptions: Map<String, String>?,
    ): String? {
        val descriptions = agentDescriptions?.filterValues { it.isNotBlank() } ?: return null
        if (descriptions.isEmpty()) return null

        val name = agentName?.takeIf { it.isNotBlank() } ?: "your on-screen character"
        val sb = StringBuilder()
        sb.append("<instructions>\n")
        sb.append("You are $name and you have an on-screen sprite that animates your emotions as you speak.\n")
        sb.append("\n")
        sb.append("Emit emotion markers inline in your reply using this exact syntax:\n")
        sb.append("  <<<state-N>>>\n")
        sb.append("Where state is one of the named emotions below and N controls playback:\n")
        sb.append("  N = 0 → loop the animation until the next marker\n")
        sb.append("  N = 1 → play once and pause on the last frame\n")
        sb.append("  N ≥ 2 → play N times and pause on the last frame\n")
        sb.append("If you omit -N, the marker defaults to N = 0 (loop).\n")
        sb.append("\n")
        sb.append("Markers are stripped from the visible reply and never spoken aloud.\n")
        sb.append("You do not need to emit a marker at the start — the client handles the default state.\n")
        sb.append("Emit a marker whenever your emotional tone changes within a reply (e.g., ")
        sb.append("<<<happy-1>>> That's wonderful! <<<surprised-1>>> Wait, really?).\n")
        sb.append("\n")
        sb.append("Available states (use ONLY these names):\n")
        for ((state, desc) in descriptions) {
            sb.append("- ").append(state).append(" — ").append(desc).append("\n")
        }
        sb.append("</instructions>\n")
        val out = sb.toString()
        PhoneDiagLog.info(
            "avatar",
            "prompt prefix built states=${descriptions.size} chars=${out.length}",
        )
        return out
    }

    /**
     * Glue the prefix onto a user message. The empty line between `<instructions>`
     * and the user's text helps models see them as distinct segments instead of
     * letting the teaching bleed into the user's voice.
     */
    fun prepend(prefix: String, userMessage: String): String {
        return prefix + "\nUser: " + userMessage
    }
}
