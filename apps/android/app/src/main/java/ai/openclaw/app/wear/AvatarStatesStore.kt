package ai.openclaw.app.wear

import java.util.concurrent.ConcurrentHashMap

/**
 * Per-agent multi-state avatar registry. Populated from the sidecar's
 * `identity.avatarStates` synthesis on each `agents.list` refresh; consumed by
 * the chat-relay path to fire GIF swaps when the model emits
 * `[avatar:<state>]` markers.
 *
 * This store isolates the interim sidecar-synthesis approach to the Wear
 * relay — general agent interaction elsewhere on the phone never reads or
 * writes it.
 */

data class AvatarStateEntry(
    val file: String,
    val description: String?,
)

data class AvatarStatesDescriptor(
    val default: String,
    val states: Map<String, AvatarStateEntry>,
    val instruction: String,
)

object AvatarStatesStore {
    private val byAgentId = ConcurrentHashMap<String, AvatarStatesDescriptor>()
    private val instructedAgents: MutableSet<String> =
        ConcurrentHashMap.newKeySet<String>()
    // (agentId, stateName) → avatar bytes + mime. Cached so the marker-fired
    // GIF swap is instant instead of racing a network fetch.
    private val bytesCache = ConcurrentHashMap<Pair<String, String>, CachedAvatar>()

    data class CachedAvatar(val bytes: ByteArray, val mime: String) {
        // Default equals() on ByteArray compares identity, not content. The
        // store only uses `equals` via Map semantics, which are key-based, so
        // overriding isn't strictly required — but avoid the data-class
        // equality pitfall by providing explicit ones.
        override fun equals(other: Any?): Boolean {
            if (this === other) return true
            if (other !is CachedAvatar) return false
            return mime == other.mime && bytes.contentEquals(other.bytes)
        }
        override fun hashCode(): Int = 31 * mime.hashCode() + bytes.contentHashCode()
    }

    fun put(agentId: String, descriptor: AvatarStatesDescriptor) {
        byAgentId[agentId] = descriptor
    }

    fun get(agentId: String): AvatarStatesDescriptor? = byAgentId[agentId]

    fun clearAllInstructionFlags() {
        instructedAgents.clear()
    }

    fun hasInstructedAgent(agentId: String): Boolean = instructedAgents.contains(agentId)

    fun markInstructed(agentId: String) {
        instructedAgents.add(agentId)
    }

    fun resetInstructedFlag(agentId: String) {
        instructedAgents.remove(agentId)
    }

    fun cachedBytes(agentId: String, state: String): CachedAvatar? =
        bytesCache[agentId to state]

    fun putCachedBytes(agentId: String, state: String, cached: CachedAvatar) {
        bytesCache[agentId to state] = cached
    }

    fun clearForTests() {
        byAgentId.clear()
        instructedAgents.clear()
        bytesCache.clear()
    }
}
