package ai.openclaw.app.wear

/**
 * Per-agent avatar metadata cached on the phone, populated by
 * `WearRelayService.rewriteAvatars` and consulted by `handleChat` to drive
 * state swaps (thinking dispatch, default-state reset) without re-walking
 * the agents JSON.
 *
 * Kept deliberately simple: just the default state name. Sprite/atlas/legacy
 * all have a `default` field in their descriptors; this object unifies the
 * lookup so handleChat doesn't need to branch on `kind`.
 *
 * Cleared implicitly when a new agent list arrives (rewriteAvatars overwrites
 * every agent's entry); stale agents hang around until they're re-seen or
 * the phone process dies — harmless for watch-dispatch purposes.
 */
object AgentAvatarMeta {
    private val defaultStates = mutableMapOf<String, String>()

    fun setDefault(agentId: String, state: String) {
        defaultStates[agentId] = state
    }

    fun getDefault(agentId: String): String? = defaultStates[agentId]

    fun clear(agentId: String) {
        defaultStates.remove(agentId)
    }
}
