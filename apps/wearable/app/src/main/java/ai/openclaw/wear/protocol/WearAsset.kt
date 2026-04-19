package ai.openclaw.wear.protocol

/**
 * Wire-level constants for avatar/asset references exchanged with the phone.
 * Mirrors src/protocol/wear-asset.ts on the gateway — keep in sync.
 */
object WearAsset {
    const val AVATAR_REF_PREFIX = "wear-asset:avatar:"
    const val DATA_AVATAR_PATH = "/openclaw/avatars"
    const val DATA_TTS_PATH = "/openclaw/tts"

    fun parseAvatarRef(raw: String?): String? {
        if (raw == null || !raw.startsWith(AVATAR_REF_PREFIX)) return null
        val id = raw.substring(AVATAR_REF_PREFIX.length)
        return id.takeIf { it.isNotEmpty() }
    }
}
