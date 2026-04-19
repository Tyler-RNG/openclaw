package ai.openclaw.app.protocol

/**
 * Wire-level constants for avatar/asset references exchanged with the Wear
 * watch. Mirrors src/protocol/wear-asset.ts on the gateway — keep in sync.
 */
object WearAsset {
    const val AVATAR_REF_PREFIX = "wear-asset:avatar:"
    const val SPRITES_REF_PREFIX = "wear-asset:sprites:"
    const val ATLAS_REF_PREFIX = "wear-asset:atlas:"
    const val DATA_AVATAR_PATH = "/openclaw/avatars"
    const val DATA_TTS_PATH = "/openclaw/tts"

    fun buildAvatarRef(agentId: String): String = "$AVATAR_REF_PREFIX$agentId"
    fun buildSpritesRef(agentId: String): String = "$SPRITES_REF_PREFIX$agentId"
    fun buildAtlasRef(agentId: String): String = "$ATLAS_REF_PREFIX$agentId"

    fun parseAvatarRef(raw: String?): String? {
        if (raw == null || !raw.startsWith(AVATAR_REF_PREFIX)) return null
        val id = raw.substring(AVATAR_REF_PREFIX.length)
        return id.takeIf { it.isNotEmpty() }
    }

    fun avatarDataPath(agentId: String): String = "$DATA_AVATAR_PATH/$agentId"
    fun avatarFramePath(agentId: String, state: String, phase: String?, index: Int): String {
        val padded = index.toString().padStart(if (index >= 100) 3 else 2, '0')
        return if (phase != null) "$DATA_AVATAR_PATH/$agentId/frames/$state/$phase/$padded"
        else "$DATA_AVATAR_PATH/$agentId/frames/$state/$padded"
    }
    fun atlasImagePath(agentId: String): String = "$DATA_AVATAR_PATH/$agentId/atlas/image"
    fun atlasManifestPath(agentId: String): String = "$DATA_AVATAR_PATH/$agentId/atlas/manifest"
}
